package es.topophone.app.ui.gpkg

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.core.geodesy.Utm
import es.topophone.core.model.cad.CadExtent
import es.topophone.core.model.cad.CadOrigin
import es.topophone.core.model.cad.GpkgIgnoredReason
import es.topophone.core.model.log.AppLog
import es.topophone.core.model.log.LogCategory
import es.topophone.core.model.log.LogLevel
import es.topophone.core.model.project.CentralPoint
import es.topophone.core.model.project.ProjectCrs
import es.topophone.core.model.project.ProjectDefaults
import es.topophone.data.cad.CadChunkWrite
import es.topophone.data.cad.CadRepository
import es.topophone.data.cad.RoomCadRepository
import es.topophone.data.db.TopoPhoneDatabase
import es.topophone.data.gpkg.GpkgImportFailure
import es.topophone.data.map.RoomMapViewportRepository
import es.topophone.data.project.RoomProjectRepository
import es.topophone.data.project.SaveResult
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import java.io.ByteArrayOutputStream
import java.io.File
import kotlinx.coroutines.CompletableDeferred
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
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith

/** Importar un Shapefile, parte 4: zona, cotas, nombre, importar con progreso y cancelar, y el resumen por capa (S030/RF-11 a RF-30). */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class ShpImportRunTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val db = Room.inMemoryDatabaseBuilder(
        ApplicationProvider.getApplicationContext<Context>(),
        TopoPhoneDatabase::class.java,
    ).build()
    private val projects = RoomProjectRepository(db)
    private val viewports = RoomMapViewportRepository(db)
    private val cad = RoomCadRepository(db)
    private val dispatcher = UnconfinedTestDispatcher()
    private val madrid = Utm.inverse(440_000.0, 4_474_000.0, 30).let { CentralPoint(it.latitudeDeg, it.longitudeDeg) }
    private val projectId = runBlocking {
        projects.create(ProjectDefaults().toDraft().copy(name = "Calle Mayor 12", crs = ProjectCrs.ETRS89_UTM30, centralPoint = madrid))
            .shouldBeInstanceOf<SaveResult.Saved>().id
    }
    private val logged = mutableListOf<String>()
    private val log = object : AppLog {
        override fun record(level: LogLevel, category: LogCategory, message: String) {
            logged += "$level $category $message"
        }
    }
    private val cache: File by lazy { folder.newFolder("cache") }
    private val everywhere = CadExtent(-1e9, 1e9, -1e9, 1e9)

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() {
        disposeGpkgViewModels()
        Dispatchers.resetMain()
        db.close()
    }

    private val points = arrayOf("puntos-25830/vertices.shp", "puntos-25830/vertices.dbf", "puntos-25830/vertices.prj")

    private fun open(vararg paths: String, repo: CadRepository = cad): GpkgImportViewModel {
        val viewModel = gpkgViewModel(cache, projects, viewports, dispatcher, repo, log = log)
        viewModel.startShapefile(projectId, ShpTestFiles.sources(*paths))
        viewModel.settled()
        return viewModel
    }

    private fun GpkgImportViewModel.finished(): GpkgImportUiState = runBlocking {
        withTimeout(15_000) {
            state.first {
                it.stage == GpkgImportStage.RESULT ||
                    (it.stage == GpkgImportStage.CONFIG && !it.zone.counting && (it.failure != null || it.cancelled))
            }
        }
    }

    private fun drawings() = runBlocking { cad.drawings(projectId).first() }

    private fun count(table: String): Int = db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM $table").use {
        it.moveToFirst()
        it.getInt(0)
    }

    private val labels = GpkgSummaryLabels(
        title = "RESUMEN",
        layer = "Capa",
        imported = "Importados",
        outsideArea = "Fuera",
        ignored = "Ignorados",
        omittedAttributes = "Omitidos",
        total = "Total",
        leftOut = "Dejados fuera",
        wgs84 = "WGS84",
        reason = { it.name },
        withoutAttributes = "Sin atributos",
    )

    @Test
    fun `S030 RF-22 la capa llega con el campo de nombre propuesto y se puede cambiar o quitar`() {
        val viewModel = open(*points)
        viewModel.state.value.layers.single().nameField shouldBe "nombre"

        viewModel.setNameField("vertices", "cota")
        viewModel.state.value.layers.single().nameField shouldBe "cota"
        viewModel.setNameField("vertices", null)
        viewModel.state.value.layers.single().nameField shouldBe null
    }

    @Test
    fun `S030 RF-17 las cotas se usan por defecto y sin ellas no se guarda ninguna altura`() {
        val files = arrayOf("altura-25830/lineas_z.shp", "altura-25830/lineas_z.dbf", "altura-25830/lineas_z.prj")
        val viewModel = open(*files)
        viewModel.state.value.useElevations shouldBe true

        viewModel.import()
        viewModel.finished().stage shouldBe GpkgImportStage.RESULT
        drawings().single().drawing.hasElevations shouldBe true
        runBlocking { cad.deleteDrawing(drawings().single().drawing.id) }

        val without = open(*files)
        without.setUseElevations(false)
        without.import()
        without.finished().stage shouldBe GpkgImportStage.RESULT
        drawings().single().drawing.useElevations shouldBe false
        drawings().single().drawing.hasElevations shouldBe false
    }

    @Test
    fun `S030 RF-29 Importar guarda un dibujo de origen Shapefile y deja el resumen`() {
        val viewModel = open(*points)

        viewModel.import()
        val state = viewModel.finished()

        state.stage shouldBe GpkgImportStage.RESULT
        state.result.shouldNotBeNull().summary.importedTotal shouldBe 5
        drawings().single().drawing.origin shouldBe CadOrigin.SHAPEFILE
        drawings().single().drawing.fileName shouldBe "vertices.shp"
        drawings().single().layers.single().nameField shouldBe "nombre"
        count("cad_chunk_attributes") shouldBe 1
    }

    @Test
    fun `S030 RF-26 un ZIP da un dibujo con todas sus capas y el nombre del ZIP`() {
        val viewModel = open("zip/completo.zip")

        viewModel.import()
        val state = viewModel.finished()

        state.result.shouldNotBeNull().summary.layers.map { it.table to it.imported } shouldBe
            listOf("ejes" to 3, "parcelas" to 2, "vertices" to 5)
        drawings().single().drawing.fileName shouldBe "completo.zip"
        drawings().single().layers.map { it.sourceTable }.toSet() shouldBe setOf("ejes", "parcelas", "vertices")
    }

    @Test
    fun `S030 RF-13 solo se importa lo que cae en la zona`() {
        val viewModel = open(*points)
        viewModel.setRadius(250.0)
        viewModel.settled()

        viewModel.import()
        val state = viewModel.finished()

        val layer = state.result.shouldNotBeNull().summary.layers.single()
        layer.imported shouldBe 3
        layer.outsideArea shouldBe 2
    }

    @Test
    fun `S030 RF-12 sin centro hay que fijarlo o importar todo el contenido`() {
        val withoutCenter = runBlocking {
            projects.create(ProjectDefaults().toDraft().copy(name = "Sin centro", crs = ProjectCrs.ETRS89_UTM30))
                .shouldBeInstanceOf<SaveResult.Saved>().id
        }
        val viewModel = gpkgViewModel(cache, projects, viewports, dispatcher, cad, log = log)
        viewModel.startShapefile(withoutCenter, ShpTestFiles.sources(*points))

        val state = viewModel.settled()

        state.hasCenter shouldBe false
        state.needsCenter shouldBe true
        state.canImport shouldBe false
        viewModel.setAllContent(true)
        viewModel.settled().canImport shouldBe true
    }

    @Test
    fun `S030 RF-29 el progreso llega al total de objetos de las capas marcadas`() {
        val viewModel =
            open(
                "poligonos-25830/parcelas.shp",
                "poligonos-25830/parcelas.prj",
                "poligonos-25830/manzanas.shp",
                "poligonos-25830/manzanas.prj",
            )

        viewModel.import()
        val state = viewModel.finished()

        state.importTotal shouldBe 4
        state.importDone shouldBe 4
    }

    @Test
    fun `S030 RF-30 cancelar no guarda nada y vuelve a la configuracion`() {
        val gate = CompletableDeferred<Unit>()
        val slow = object : CadRepository by cad {
            override suspend fun begin(drawing: es.topophone.core.model.cad.CadDrawing) {
                gate.await()
                cad.begin(drawing)
            }
        }
        val viewModel = open(*points, repo = slow)

        viewModel.import()
        viewModel.state.value.stage shouldBe GpkgImportStage.IMPORTING
        viewModel.cancel()

        val state = viewModel.finished()
        state.stage shouldBe GpkgImportStage.CONFIG
        state.cancelled shouldBe true
        count("cad_drawings") shouldBe 0
        count("cad_chunks") shouldBe 0
        logged.any { it.contains("Shapefile cancelada") } shouldBe true
    }

    @Test
    fun `S030 RF-30 si no se puede escribir no se guarda nada y lo dice`() {
        val failing = object : CadRepository by cad {
            override suspend fun addChunks(chunks: List<CadChunkWrite>) = throw IllegalStateException("disco lleno")
        }
        val viewModel = open(*points, repo = failing)

        viewModel.import()
        val state = viewModel.finished()

        state.stage shouldBe GpkgImportStage.CONFIG
        state.failure shouldBe GpkgImportFailure.WRITE_ERROR
        count("cad_drawings") shouldBe 0
        logged.any { it.startsWith("ERROR") && it.contains("Shapefile fallida") && it.contains("write_error") } shouldBe true
    }

    @Test
    fun `S030 RF-30 si ningun objeto cae en la zona no se guarda nada`() {
        val viewModel = open(*points)
        viewModel.setRadius(100.0)
        viewModel.settled()
        viewModel.setCentralPoint(CentralPoint(40.5, -3.6)) // unos 15 km al nordeste: ningún punto cae a 100 m
        viewModel.settled()
        runBlocking { withTimeout(5_000) { viewModel.state.first { it.centralPoint != null && !it.zone.counting } } }

        viewModel.import()
        val state = viewModel.finished()

        state.failure shouldBe GpkgImportFailure.NO_OBJECTS
        count("cad_drawings") shouldBe 0
    }

    @Test
    fun `S030 RF-28 el log anota la importacion con recuentos y sin nombres ni coordenadas`() {
        val viewModel = open(*points)

        viewModel.import()
        viewModel.finished()

        val line = logged.single { it.contains("importado") }
        line shouldContain "Shapefile importado"
        line shouldContain "5 objetos"
        line shouldContain "1 capas"
        for (secret in listOf("vertices", "Farola", "Esquina", "puntos-25830", "440", "4474")) line shouldNotContain secret
    }

    @Test
    fun `S030 RF-25 el resumen dice los registros borrados y las capas ignoradas con su motivo`() {
        val viewModel = open("borrados-y-nulos/borrados.shp", "borrados-y-nulos/borrados.dbf", "borrados-y-nulos/borrados.prj")
        viewModel.import()
        viewModel.finished()

        val out = ByteArrayOutputStream()
        val ok = runBlocking { viewModel.writeSummary(out, labels) }

        ok shouldBe true
        val text = out.toString(Charsets.UTF_8)
        text shouldContain "RESUMEN"
        text shouldContain "Capa: borrados"
        text shouldContain "  Importados: 3"
        text shouldContain "  Ignorados: 2"
        text shouldContain "    ${GpkgIgnoredReason.DELETED_RECORD.name}: 2"
        text shouldContain "Total: 3"
    }

    @Test
    fun `S030 RF-25 el resumen dice los objetos sin atributos y los ficheros dejados fuera`() {
        val short = open("incompletos/dbf_corto.shp", "incompletos/dbf_corto.dbf", "incompletos/dbf_corto.prj")
        short.import()
        short.finished()
        val out = ByteArrayOutputStream()
        runBlocking { short.writeSummary(out, labels) }
        out.toString(Charsets.UTF_8) shouldContain "  Sin atributos: 2"

        val orphans = open("puntos-25830/vertices.shp", "puntos-25830/vertices.prj", "incompletos/dbf_largo.dbf")
        orphans.import()
        orphans.finished()
        val out2 = ByteArrayOutputStream()
        runBlocking { orphans.writeSummary(out2, labels) }
        out2.toString(Charsets.UTF_8) shouldContain "Dejados fuera: dbf_largo.dbf"
    }

    @Test
    fun `S030 RNF-T5 al terminar la importacion se borra la copia temporal`() {
        val viewModel = open(*points)
        File(cache, "shp").listFiles().orEmpty().size shouldBe 1

        viewModel.import()
        viewModel.finished()

        File(cache, "shp").listFiles().orEmpty().size shouldBe 0
        runBlocking { cad.chunksIn(drawings().single().layers.map { it.id }, everywhere) }.isNotEmpty() shouldBe true
    }
}
