package es.topophone.app.ui.cad

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.app.ui.map.MapCamera
import es.topophone.app.ui.map.MapTap
import es.topophone.core.geodesy.Utm
import es.topophone.core.model.cad.CadChunkCodec
import es.topophone.core.model.cad.CadColor
import es.topophone.core.model.cad.CadDrawing
import es.topophone.core.model.cad.CadEntity
import es.topophone.core.model.cad.CadEntityKind
import es.topophone.core.model.cad.CadExtent
import es.topophone.core.model.cad.CadOrigin
import es.topophone.core.model.cad.CadSourceCrs
import es.topophone.core.model.cad.DesignLayer
import es.topophone.core.model.cad.DesignLayerKind
import es.topophone.core.model.cad.GpkgAttributesCodec
import es.topophone.core.model.cad.GpkgValue
import es.topophone.core.model.log.AppLog
import es.topophone.core.model.log.LogCategory
import es.topophone.core.model.log.LogLevel
import es.topophone.core.model.project.LinearUnit
import es.topophone.core.model.project.ProjectCrs
import es.topophone.core.model.project.ProjectDefaults
import es.topophone.data.cad.CadChunkContent
import es.topophone.data.cad.CadChunkWrite
import es.topophone.data.cad.RoomCadRepository
import es.topophone.data.db.TopoPhoneDatabase
import es.topophone.data.project.RoomProjectRepository
import es.topophone.data.project.SaveResult
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
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

/** Las etiquetas de nombre y los contornos de polígono en la escena del mapa, y los atributos al tocar un objeto (S048/RF-20, RF-22, RF-23, RF-28). */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class DesignLabelsTest {

    private val db = Room.inMemoryDatabaseBuilder(
        ApplicationProvider.getApplicationContext<Context>(),
        TopoPhoneDatabase::class.java,
    ).build()
    private val projects = RoomProjectRepository(db)
    private val cad = RoomCadRepository(db)
    private val dispatcher = UnconfinedTestDispatcher()
    private val crs = ProjectCrs.ETRS89_UTM30
    private val logged = mutableListOf<String>()
    private val log = object : AppLog {
        override fun record(level: LogLevel, category: LogCategory, message: String) {
            logged += "$level $category $message"
        }
    }
    private val projectId = runBlocking {
        projects.create(ProjectDefaults().toDraft().copy(name = "Calle Mayor 12", crs = crs)).shouldBeInstanceOf<SaveResult.Saved>().id
    }

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        db.close()
    }

    private fun layer(id: String, kind: DesignLayerKind) = DesignLayer(id, "d", "Capa $id", kind, CadColor.rgb(0x3CB44B))

    private fun chunk(layerId: String, vararg entities: CadEntity) =
        CadChunkContent(1L, layerId, CadExtent(0.0, 1.0, 0.0, 1.0), entities.toList())

    private fun point(e: Double, n: Double, name: String?) =
        CadEntity(CadEntityKind.POINT, doubleArrayOf(e), doubleArrayOf(n), null, null, name = name)

    private fun polyline(name: String?, ring: Boolean, vararg en: Double) = CadEntity(
        CadEntityKind.POLYLINE,
        DoubleArray(en.size / 2) { en[it * 2] },
        DoubleArray(en.size / 2) { en[it * 2 + 1] },
        null,
        null,
        name = name,
        polygonRing = ring,
    )

    private fun build(layers: List<DesignLayer>, chunks: List<CadChunkContent>) =
        DesignSceneBuilder.build(crs, layers, chunks, metresPerPixel = 0.5, contrastArgb = 0xFF000000.toInt())

    @Test
    fun `S048 RF-22 los puntos llevan su nombre al mapa`() {
        val scene = build(
            listOf(layer("p", DesignLayerKind.POINTS)),
            listOf(chunk("p", point(440_000.0, 4_474_000.0, "Farola"), point(440_050.0, 4_474_050.0, null))),
        )

        scene.points.map { it.name } shouldBe listOf("Farola", null)
    }

    @Test
    fun `S048 RF-22 las lineas llevan su nombre al mapa`() {
        val scene = build(
            listOf(layer("l", DesignLayerKind.LINES)),
            listOf(chunk("l", polyline("Calle Mayor", false, 440_000.0, 4_474_000.0, 440_200.0, 4_474_100.0))),
        )

        scene.lines.single().name shouldBe "Calle Mayor"
        scene.lines.single().ring shouldBe false
    }

    @Test
    fun `S048 RF-17 y RF-23 el contorno y los huecos de un poligono se marcan como contorno`() {
        val scene = build(
            listOf(layer("l", DesignLayerKind.LINES)),
            listOf(
                chunk(
                    "l",
                    polyline(
                        "Parcela 2",
                        true,
                        440_000.0, 4_474_000.0, 440_200.0, 4_474_000.0, 440_200.0, 4_474_200.0, 440_000.0, 4_474_000.0,
                    ),
                    polyline(
                        "Parcela 2",
                        true,
                        440_050.0, 4_474_050.0, 440_100.0, 4_474_050.0, 440_100.0, 4_474_100.0, 440_050.0, 4_474_050.0,
                    ),
                    polyline("Eje", false, 440_000.0, 4_474_500.0, 440_300.0, 4_474_500.0),
                ),
            ),
        )

        scene.lines.map { it.ring } shouldBe listOf(true, true, false)
        scene.lines.map { it.name } shouldBe listOf("Parcela 2", "Parcela 2", "Eje")
    }

    @Test
    fun `S048 RF-22 un dibujo de DXF no lleva nombres ni contornos`() {
        val dxf = CadEntity(CadEntityKind.LINE, doubleArrayOf(440_000.0, 440_100.0), doubleArrayOf(4_474_000.0, 4_474_000.0), null, null)

        val scene = build(listOf(layer("l", DesignLayerKind.LINES)), listOf(chunk("l", dxf)))

        scene.lines.single().name shouldBe null
        scene.lines.single().ring shouldBe false
    }

    @Test
    fun `S048 RF-22 una escena de antes de S048 se construye igual`() {
        val line = DesignLine(doubleArrayOf(-3.7, 40.4, -3.69, 40.41), 0xFFFF0000.toInt())
        val point = DesignPoint(40.4, -3.7, 0xFF000000.toInt())

        line.name shouldBe null
        line.ring shouldBe false
        point.name shouldBe null
    }

    // ---- Atributos y borrado con el ViewModel ----

    private fun drawing(id: String, origin: CadOrigin) = CadDrawing(
        id = id,
        projectId = projectId,
        fileName = "capas.gpkg",
        importedAtMs = 1_000L,
        source = CadSourceCrs.Project,
        unit = LinearUnit.METRE,
        useElevations = true,
        hasElevations = false,
        origin = origin,
    )

    private val columns = listOf("nombre", "cota")

    private fun saveDrawing(id: String, origin: CadOrigin, withAttributes: Boolean) = runBlocking {
        cad.begin(drawing(id, origin))
        cad.addLayer(DesignLayer("l-$id", id, "capa", DesignLayerKind.POINTS, CadColor.rgb(0), sourceTable = "capa"))
        val entity = CadEntity(CadEntityKind.POINT, doubleArrayOf(440_000.0), doubleArrayOf(4_474_000.0), null, null, name = "Farola")
        val block = if (withAttributes) {
            GpkgAttributesCodec.encodeBlock(
                columns,
                listOf(GpkgAttributesCodec.encodeRow(listOf(GpkgValue.Text("Farola"), GpkgValue.Real(646.5)))),
            )
        } else {
            null
        }
        cad.addChunks(listOf(CadChunkWrite("l-$id", entity.bounds, 1, CadChunkCodec.encode(listOf(entity)), block)))
        cad.finish(id, mapOf("l-$id" to 1), false, false, CadExtent(440_000.0, 440_000.0, 4_474_000.0, 4_474_000.0))
    }

    private fun viewModel(): DesignMapViewModel = DesignMapViewModel(cad, projects, dispatcher, log).also {
        it.start(projectId)
        it.onCamera(MapCamera(40.4166, -3.7040, 18.0))
        runBlocking { withTimeout(5_000) { it.drawings.first { drawings -> drawings.isNotEmpty() } } }
    }

    private fun DesignMapViewModel.tapAtPoint(): DesignSelection? {
        val ground = Utm.inverse(440_000.0, 4_474_000.0, 30)
        onTap(MapTap(ground.latitudeDeg, ground.longitudeDeg, toleranceM = 60.0))
        return runBlocking { withTimeout(5_000) { selected.first { it != null } } }
    }

    @Test
    fun `S048 RF-20 al tocar un objeto de un GeoPackage la ficha trae todos sus atributos`() {
        saveDrawing("g1", CadOrigin.GEOPACKAGE, withAttributes = true)

        val selection = viewModel().tapAtPoint().shouldNotBeNull()

        selection.attributes shouldBe linkedMapOf("nombre" to GpkgValue.Text("Farola"), "cota" to GpkgValue.Real(646.5))
        selection.entity.name shouldBe "Farola"
    }

    @Test
    fun `S048 RF-20 un elemento de un DXF no trae atributos`() {
        saveDrawing("d1", CadOrigin.DXF, withAttributes = false)

        viewModel().tapAtPoint().shouldNotBeNull().attributes shouldBe emptyMap()
    }

    @Test
    fun `S048 RF-28 borrar un dibujo se anota sin nombres ni coordenadas`() {
        saveDrawing("g1", CadOrigin.GEOPACKAGE, withAttributes = true)
        val viewModel = viewModel()

        viewModel.deleteDrawing("g1")

        runBlocking { withTimeout(5_000) { viewModel.drawings.first { it.isEmpty() } } }
        val line = logged.single { it.contains("borrado") }
        line shouldBe "INFO APP Dibujo borrado (geopackage)"
        for (secret in listOf("capas.gpkg", "capa", "Farola", "440")) line shouldNotContain secret
    }

    @Test
    fun `S048 RF-27 borrar el dibujo no toca los puntos del proyecto`() {
        saveDrawing("g1", CadOrigin.GEOPACKAGE, withAttributes = true)
        val before = db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM points").use {
            it.moveToFirst()
            it.getInt(0)
        }
        val viewModel = viewModel()

        viewModel.deleteDrawing("g1")
        runBlocking { withTimeout(5_000) { viewModel.drawings.first { it.isEmpty() } } }

        db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM points").use {
            it.moveToFirst()
            it.getInt(0)
        } shouldBe before
    }
}
