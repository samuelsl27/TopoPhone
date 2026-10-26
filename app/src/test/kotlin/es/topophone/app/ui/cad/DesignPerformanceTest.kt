package es.topophone.app.ui.cad

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.app.ui.map.MapCamera
import es.topophone.core.geodesy.WebMercator
import es.topophone.core.model.cad.CadImportOptions
import es.topophone.core.model.project.ProjectCrs
import es.topophone.core.model.project.ProjectDefaults
import es.topophone.core.survey.CadPlacement
import es.topophone.data.cad.CadDrawingImporter
import es.topophone.data.cad.CadImportResult
import es.topophone.data.cad.RoomCadRepository
import es.topophone.data.db.TopoPhoneDatabase
import es.topophone.data.project.RoomProjectRepository
import es.topophone.data.project.SaveResult
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.time.measureTime
import kotlin.time.measureTimedValue
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

/**
 * Con un dibujo de 100 MB (1,4 millones de elementos), el proyecto muestra su primera escena en menos de 3 s y, al mover el mapa,
 * la escena se reconstruye en menos de 200 ms; alejado, la escena no pasa de 60.000 vértices (S040, RNF). Robolectric sirve de cota;
 * la medida en el móvil de referencia es de la demo.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class DesignPerformanceTest {

    private val db = Room.inMemoryDatabaseBuilder(
        ApplicationProvider.getApplicationContext<Context>(),
        TopoPhoneDatabase::class.java,
    ).build()
    private val projects = RoomProjectRepository(db)
    private val cad = RoomCadRepository(db)
    private val crs = ProjectCrs.ETRS89_UTM30
    private val dispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        db.close()
    }

    private fun importBig(): String {
        val projectId = runBlocking {
            projects.create(ProjectDefaults().toDraft().copy(name = "Obra grande", crs = crs)).shouldBeInstanceOf<SaveResult.Saved>().id
        }
        val result = runBlocking {
            CadDrawingImporter(cad).import(projectId, crs, "obra.dxf", CadImportOptions(), { GeneratedDxf(SIZE) })
        }
        result.shouldBeInstanceOf<CadImportResult.Imported>().summary.importedTotal shouldBe 1_383_346
        return projectId
    }

    /** El centro de la malla del generador (de 20 por 6,9 km), en la cámara del mapa. */
    private fun camera(zoom: Double, eastShiftM: Double = 0.0): MapCamera {
        val (lat, lon) = CadPlacement.geographic(crs, 410_000.0 + eastShiftM, 4_463_500.0)!!
        return MapCamera(lat, lon, zoom)
    }

    private fun rebuild(projectId: String, camera: MapCamera): DesignScene {
        val layers = runBlocking { cad.drawings(projectId).first() }.flatMap { it.layers }.filter { it.visible }
        val area = DesignSceneBuilder.viewport(camera, crs)!!
        val chunks = runBlocking { cad.chunkSequenceIn(layers.map { it.id }, area) }
        val mpp = WebMercator.metresPerPixel(camera.latitudeDeg, camera.zoom)
        return DesignSceneBuilder.build(crs, layers, chunks, mpp, 0xFF000000.toInt())
    }

    @Test
    fun `S040 RNF el proyecto con 100 MB muestra su primera escena en menos de 3 s`() {
        val projectId = importBig()
        val vm = DesignMapViewModel(cad, projects, dispatcher)

        val time = measureTime {
            vm.start(projectId)
            vm.onCamera(camera(17.0))
            runBlocking { withTimeout(30_000) { vm.scene.first { it != null } } }
        }

        println("S040 primera escena con 100 MB: ${time.inWholeMilliseconds} ms")
        (time.inWholeMilliseconds < 3_000) shouldBe true
    }

    @Test
    fun `S040 RNF al mover el mapa la escena se reconstruye en menos de 200 ms`() {
        val projectId = importBig()
        rebuild(projectId, camera(17.0)) // la primera vez calienta la máquina virtual

        val (scene, time) = measureTimedValue { rebuild(projectId, camera(17.0, eastShiftM = 1_000.0)) }

        println("S040 reconstruir la escena a zoom 17: ${time.inWholeMilliseconds} ms, ${scene.vertexCount} vértices")
        (scene.vertexCount > 1_000) shouldBe true
        (time.inWholeMilliseconds < 200) shouldBe true
    }

    @Test
    fun `S040 RNF alejado, la escena cabe en 60000 vertices`() {
        val projectId = importBig()

        val (scene, time) = measureTimedValue { rebuild(projectId, camera(13.0)) }

        println("S040 reconstruir la escena a zoom 13: ${time.inWholeMilliseconds} ms, ${scene.vertexCount} vértices")
        (scene.vertexCount <= DesignSceneBuilder.MAX_VERTICES) shouldBe true
        (scene.vertexCount > 10_000) shouldBe true
        (time.inWholeSeconds < 10) shouldBe true
    }

    private companion object {
        const val SIZE = 100L * 1024 * 1024
    }
}
