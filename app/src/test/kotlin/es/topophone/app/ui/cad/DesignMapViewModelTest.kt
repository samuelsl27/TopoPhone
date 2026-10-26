package es.topophone.app.ui.cad

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.app.ui.map.MapCamera
import es.topophone.core.model.cad.CadImportOptions
import es.topophone.core.model.cad.DesignLayerKind
import es.topophone.core.model.map.Basemap
import es.topophone.core.model.project.ProjectCrs
import es.topophone.core.model.project.ProjectDefaults
import es.topophone.data.cad.CadDrawingImporter
import es.topophone.data.cad.CadImportResult
import es.topophone.data.cad.RoomCadRepository
import es.topophone.data.db.TopoPhoneDatabase
import es.topophone.data.project.RoomProjectRepository
import es.topophone.data.project.SaveResult
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.io.ByteArrayInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Lo que el mapa dibuja del dibujo: la vista, las capas visibles, el cambio de cámara y el color de contraste (S040/RF-24, RF-25, RF-27). */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class DesignMapViewModelTest {

    private val db = Room.inMemoryDatabaseBuilder(
        ApplicationProvider.getApplicationContext<Context>(),
        TopoPhoneDatabase::class.java,
    ).build()
    private val projects = RoomProjectRepository(db)
    private val cad = RoomCadRepository(db)
    private val dispatcher = UnconfinedTestDispatcher()
    private val projectId = runBlocking {
        projects.create(ProjectDefaults().toDraft().copy(name = "Calle Mayor 12", crs = ProjectCrs.ETRS89_UTM30))
            .shouldBeInstanceOf<SaveResult.Saved>().id
    }
    private val madrid = MapCamera(40.4175, -3.7030, 15.0)
    private val sevilla = MapCamera(37.3891, -5.9845, 15.0)

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        db.close()
    }

    private fun import(file: String): String = runBlocking {
        val bytes = DxfTestFiles.bytes(file)
        val result = CadDrawingImporter(cad).import(projectId, ProjectCrs.ETRS89_UTM30, file, CadImportOptions(), {
            ByteArrayInputStream(bytes)
        })
        result.shouldBeInstanceOf<CadImportResult.Imported>().drawingId
    }

    private fun open(camera: MapCamera? = madrid): DesignMapViewModel {
        val vm = DesignMapViewModel(cad, projects, dispatcher)
        vm.start(projectId)
        if (camera != null) vm.onCamera(camera)
        return vm
    }

    private fun DesignMapViewModel.await(condition: (DesignScene?) -> Boolean): DesignScene? =
        runBlocking { withTimeout(10_000) { scene.first(condition) } }

    @Test
    fun `S040 RF-24 con un dibujo y la camara en su zona el mapa recibe sus lineas y sus puntos`() {
        import("r2000.dxf")
        val vm = open()

        val scene = vm.await { it != null }!!

        (scene.lines.size > 5) shouldBe true
        (scene.points.size >= 4) shouldBe true
        scene.lines.all { it.vertexCount >= 2 } shouldBe true
    }

    @Test
    fun `S040 RF-24 sin dibujos o sin camara no hay escena`() {
        open().scene.value shouldBe null
        import("r2000.dxf")
        val withoutCamera = open(camera = null)

        withoutCamera.scene.value shouldBe null
    }

    @Test
    fun `S040 RF-24 con la camara lejos del dibujo no hay nada que dibujar`() {
        import("r2000.dxf")
        val vm = open(sevilla)

        vm.scene.value shouldBe null
        vm.onCamera(madrid)
        (vm.await { it != null }!!.lines.isNotEmpty()) shouldBe true
        vm.onCamera(sevilla)
        vm.await { it == null } shouldBe null
    }

    @Test
    fun `S040 RF-24 con la camara fuera del ambito del sistema tampoco`() {
        import("r2000.dxf")

        open(MapCamera(0.0, 0.0, 10.0)).scene.value shouldBe null
    }

    @Test
    fun `S040 RF-27 ocultar una capa la quita del mapa y mostrarla la devuelve`() {
        val drawing = import("r2000.dxf")
        val vm = open()
        val before = vm.await { it != null }!!
        val zanja = runBlocking { cad.drawings(projectId).first() }.single { it.drawing.id == drawing }
            .layers.single { it.name == "Eje-Zanja" && it.kind == DesignLayerKind.LINES }

        runBlocking { cad.setLayerVisible(zanja.id, false) }
        val hidden = vm.await { it != null && it.lines.size < before.lines.size }!!

        hidden.lines.size shouldBe before.lines.size - 2 // la capa tiene dos líneas
        runBlocking { cad.setLayerVisible(zanja.id, true) }
        vm.await { it != null && it.lines.size == before.lines.size }
    }

    @Test
    fun `S040 RF-27 con todas las capas ocultas no hay escena`() {
        val drawing = import("r2000.dxf")
        val vm = open()
        vm.await { it != null }

        runBlocking {
            cad.drawings(projectId).first().single { it.drawing.id == drawing }.layers.forEach { cad.setLayerVisible(it.id, false) }
        }

        vm.await { it == null } shouldBe null
    }

    @Test
    fun `S040 RF-30 borrar el dibujo lo quita del mapa`() {
        val drawing = import("r2000.dxf")
        val vm = open()
        vm.await { it != null }

        runBlocking { cad.deleteDrawing(drawing) }

        vm.await { it == null } shouldBe null
    }

    @Test
    fun `S040 RF-28 dos dibujos con capas del mismo nombre se dibujan los dos`() {
        import("r2000.dxf")
        val vm = open()
        val one = vm.await { it != null }!!

        import("r2010.dxf")

        vm.await { it != null && it.lines.size == one.lines.size * 2 }
    }

    @Test
    fun `S040 RF-25 el blanco del DXF se pinta de negro sobre el mapa de calles y de blanco sobre la ortofoto`() {
        import("capas.dxf")
        val vm = open(MapCamera(40.4166, -3.7040, 16.0))
        vm.setBasemap(Basemap.OSM_VECTOR)

        val dark = vm.await { it != null && it.lines.any { l -> l.argb == 0xFF000000.toInt() } }!!
        dark.lines.none { it.argb == 0xFFFFFFFF.toInt() } shouldBe true

        vm.setBasemap(Basemap.PNOA_ORTHOPHOTO)
        val light = vm.await { it != null && it.lines.any { l -> l.argb == 0xFFFFFFFF.toInt() } }!!
        light.lines.none { it.argb == 0xFF000000.toInt() } shouldBe true
    }
}
