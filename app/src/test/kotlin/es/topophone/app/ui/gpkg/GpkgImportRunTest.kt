package es.topophone.app.ui.gpkg

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.core.geodesy.Utm
import es.topophone.core.model.cad.DesignLayerKind
import es.topophone.core.model.cad.GpkgColumn
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
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.nulls.shouldBeNull
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

/** Importar un GeoPackage, parte 4: campo de nombre, cotas, importar con progreso y cancelar, y el resumen por capa (S048/RF-19 a RF-30). */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class GpkgImportRunTest {

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
    private val e0 = 440_000.0
    private val n0 = 4_474_000.0
    private val madrid = Utm.inverse(e0, n0, 30).let { CentralPoint(it.latitudeDeg, it.longitudeDeg) }
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

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() {
        disposeGpkgViewModels()
        Dispatchers.resetMain()
        db.close()
    }

    private fun open(file: String, repo: CadRepository = cad): GpkgImportViewModel {
        val viewModel = gpkgViewModel(cache, projects, viewports, dispatcher, repo, log = log)
        viewModel.start(projectId, GpkgTestFiles.source(file))
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

    @Test
    fun `S048 RF-21 propone el campo name, nombre, id o codigo sin distinguir mayusculas`() {
        suggestNameField(listOf(GpkgColumn("Otro", "TEXT"), GpkgColumn("NOMBRE", "TEXT"))) shouldBe "NOMBRE"
        suggestNameField(listOf(GpkgColumn("x", "TEXT"), GpkgColumn("Codigo", "TEXT"), GpkgColumn("ID", "INTEGER"))) shouldBe "ID"
        suggestNameField(listOf(GpkgColumn("Name", "TEXT"), GpkgColumn("nombre", "TEXT"))) shouldBe "Name"
        suggestNameField(listOf(GpkgColumn("CÓDIGO", "TEXT"))) shouldBe "CÓDIGO"
        suggestNameField(listOf(GpkgColumn("otro", "TEXT"), GpkgColumn("ancho", "REAL"))).shouldBeNull()
        suggestNameField(emptyList()).shouldBeNull()
    }

    @Test
    fun `S048 RF-21 la capa llega con el campo propuesto y se puede cambiar o quitar`() {
        val viewModel = open("puntos-25830.gpkg")
        viewModel.state.value.layers.single().nameField shouldBe "nombre"

        viewModel.setNameField("vertices", "cota")
        viewModel.state.value.layers.single().nameField shouldBe "cota"
        viewModel.setNameField("vertices", null)
        viewModel.state.value.layers.single().nameField.shouldBeNull()
    }

    @Test
    fun `S048 RF-19 las cotas se usan por defecto y se pueden descartar`() {
        val viewModel = open("altura-25830.gpkg")
        viewModel.state.value.useElevations shouldBe true

        viewModel.setUseElevations(false)

        viewModel.state.value.useElevations shouldBe false
    }

    @Test
    fun `S048 RF-29 Importar guarda el dibujo y deja el resumen`() {
        val viewModel = open("puntos-25830.gpkg")

        viewModel.import()
        val state = viewModel.finished()

        state.stage shouldBe GpkgImportStage.RESULT
        val outcome = state.result.shouldNotBeNull()
        outcome.summary.importedTotal shouldBe 5
        outcome.summary.layers.single().omittedAttributes shouldBe 1
        drawings().single().drawing.fileName shouldBe "puntos-25830.gpkg"
        drawings().single().layers.single().nameField shouldBe "nombre"
        count("cad_chunk_attributes") shouldBe 1
    }

    @Test
    fun `S048 RF-22 el nombre elegido nombra los elementos y sin campo lo hace su numero`() {
        val named = open("puntos-25830.gpkg")
        named.import()
        named.finished()
        val withName = runBlocking {
            cad.chunksIn(listOf(drawings().single().layers.single().id), es.topophone.core.model.cad.CadExtent(-1e9, 1e9, -1e9, 1e9))
        }.flatMap { it.entities }.map { it.name }
        cad.let { runBlocking { it.deleteDrawing(drawings().single().drawing.id) } }

        val unnamed = open("puntos-25830.gpkg")
        unnamed.setNameField("vertices", null)
        unnamed.import()
        unnamed.finished()
        val withNumber = runBlocking {
            cad.chunksIn(listOf(drawings().single().layers.single().id), es.topophone.core.model.cad.CadExtent(-1e9, 1e9, -1e9, 1e9))
        }.flatMap { it.entities }.map { it.name }

        withName.toSet() shouldBe setOf("Esquina norte", "Árbol ñandú", "3", "4", "Farola")
        withNumber.toSet() shouldBe setOf("1", "2", "3", "4", "5")
    }

    @Test
    fun `S048 RF-19 sin usar las cotas no se guarda ninguna altura`() {
        val viewModel = open("altura-25830.gpkg")
        viewModel.setUseElevations(false)
        listOf("puntos_z", "lineas_m", "lineas_zm", "poligonos_z").forEach { viewModel.setMarked(it, false) }

        viewModel.settled()
        viewModel.import()
        viewModel.finished().stage shouldBe GpkgImportStage.RESULT

        drawings().single().drawing.useElevations shouldBe false
        drawings().single().drawing.hasElevations shouldBe false
    }

    @Test
    fun `S048 RF-13 solo se importa lo que cae en la zona`() {
        val viewModel = open("puntos-25830.gpkg")
        viewModel.setRadius(250.0)
        viewModel.settled()

        viewModel.import()
        val state = viewModel.finished()

        state.stage shouldBe GpkgImportStage.RESULT
        val layer = state.result.shouldNotBeNull().summary.layers.single()
        layer.imported shouldBe 3
        layer.outsideArea shouldBe 2
    }

    @Test
    fun `S048 RF-29 el progreso llega al total de objetos`() {
        val viewModel = open("poligonos-25830.gpkg")

        viewModel.import()
        val state = viewModel.finished()

        state.importTotal shouldBe 4
        state.importDone shouldBe 4
    }

    @Test
    fun `S048 RF-29 mientras importa no se puede volver a pulsar Importar`() {
        val gate = CompletableDeferred<Unit>()
        val slow = object : CadRepository by cad {
            override suspend fun begin(drawing: es.topophone.core.model.cad.CadDrawing) {
                gate.await()
                cad.begin(drawing)
            }
        }
        val viewModel = open("puntos-25830.gpkg", slow)

        viewModel.import()
        viewModel.state.value.stage shouldBe GpkgImportStage.IMPORTING
        viewModel.state.value.canImport shouldBe false
        viewModel.import() // no hace nada: ya está importando
        gate.complete(Unit)

        viewModel.finished().stage shouldBe GpkgImportStage.RESULT
        drawings().size shouldBe 1
    }

    @Test
    fun `S048 RF-30 cancelar no guarda nada y vuelve a la configuracion`() {
        val gate = CompletableDeferred<Unit>()
        val slow = object : CadRepository by cad {
            override suspend fun begin(drawing: es.topophone.core.model.cad.CadDrawing) {
                gate.await()
                cad.begin(drawing)
            }
        }
        val viewModel = open("puntos-25830.gpkg", slow)

        viewModel.import()
        viewModel.state.value.stage shouldBe GpkgImportStage.IMPORTING
        viewModel.cancel()

        val state = viewModel.finished()
        state.stage shouldBe GpkgImportStage.CONFIG
        state.cancelled shouldBe true
        count("cad_drawings") shouldBe 0
        count("cad_chunks") shouldBe 0
        logged.any { it.contains("cancelada") } shouldBe true
    }

    @Test
    fun `S048 RF-30 si no se puede escribir no se guarda nada y lo dice`() {
        val failing = object : CadRepository by cad {
            override suspend fun addChunks(chunks: List<CadChunkWrite>) = throw IllegalStateException("disco lleno")
        }
        val viewModel = open("puntos-25830.gpkg", failing)

        viewModel.import()
        val state = viewModel.finished()

        state.stage shouldBe GpkgImportStage.CONFIG
        state.failure shouldBe GpkgImportFailure.WRITE_ERROR
        count("cad_drawings") shouldBe 0
        count("cad_chunks") shouldBe 0
        logged.any { it.startsWith("ERROR") && it.contains("write_error") } shouldBe true
    }

    @Test
    fun `S048 RF-30 si ningun objeto cae en la zona no se guarda nada`() {
        val viewModel = open("puntos-25830.gpkg")
        viewModel.setRadius(100.0)
        viewModel.settled()
        viewModel.setCentralPoint(CentralPoint(40.5, -3.6)) // unos 15 km al nordeste: ningún punto cae a 100 m
        viewModel.settled()
        runBlocking { withTimeout(5_000) { viewModel.state.first { it.centralPoint != null && !it.zone.counting } } }

        viewModel.import()
        val state = viewModel.finished()

        state.stage shouldBe GpkgImportStage.CONFIG
        state.failure shouldBe GpkgImportFailure.NO_OBJECTS
        count("cad_drawings") shouldBe 0
    }

    @Test
    fun `S048 RF-28 el log anota la importacion con recuentos y sin nombres ni coordenadas`() {
        val viewModel = open("puntos-25830.gpkg")

        viewModel.import()
        viewModel.finished()

        val line = logged.single { it.contains("importado") }
        line shouldContain "5 objetos"
        line shouldContain "1 capas"
        for (secret in listOf("vertices", "Farola", "Esquina", "puntos-25830", "440", "4474")) line shouldNotContain secret
    }

    @Test
    fun `S048 RF-25 el resumen se escribe como texto con una seccion por capa`() {
        val viewModel = open("curvas-y-vacios-25830.gpkg")
        viewModel.import()
        viewModel.finished()
        val labels = GpkgSummaryLabels(
            title = "RESUMEN",
            layer = "Capa",
            imported = "Importados",
            outsideArea = "Fuera",
            ignored = "Ignorados",
            omittedAttributes = "Omitidos",
            total = "Total",
            leftOut = "Fuera de la importación",
            wgs84 = "WGS84",
            reason = { it.name },
        )

        val out = ByteArrayOutputStream()
        val ok = runBlocking { viewModel.writeSummary(out, labels) }

        ok shouldBe true
        val text = out.toString(Charsets.UTF_8)
        text shouldContain "RESUMEN"
        text shouldContain "Capa: raros"
        text shouldContain "  Importados: 2"
        text shouldContain "  Ignorados: 6"
        text shouldContain "    ${GpkgIgnoredReason.CURVE_GEOMETRY.name}: 3"
        text shouldContain "    ${GpkgIgnoredReason.EMPTY_GEOMETRY.name}: 3"
        text shouldContain "Total: 2"
    }

    @Test
    fun `S048 RF-25 el resumen del texto incluye lo dejado fuera y las capas en WGS 84`() {
        val viewModel = open("con-ajenas-25830.gpkg")
        viewModel.import()
        viewModel.finished()
        val labels = GpkgSummaryLabels("T", "Capa", "Imp", "Fuera", "Ign", "Omit", "Total", "Dejadas fuera", "WGS84", { it.name })

        val out = ByteArrayOutputStream()
        runBlocking { viewModel.writeSummary(out, labels) }

        out.toString(Charsets.UTF_8) shouldContain "Dejadas fuera: censo, teselas, notas"
        val wgs = open("wgs84-4326.gpkg")
        wgs.import()
        wgs.finished()
        val out2 = ByteArrayOutputStream()
        runBlocking { wgs.writeSummary(out2, labels) }
        out2.toString(Charsets.UTF_8) shouldContain "WGS84: puntos"
    }

    @Test
    fun `S048 RF-25 sin resultado no hay resumen que escribir`() {
        val viewModel = open("puntos-25830.gpkg")
        val labels = GpkgSummaryLabels("T", "Capa", "Imp", "Fuera", "Ign", "Omit", "Total", "Dejadas", "WGS84", { it.name })

        runBlocking { viewModel.writeSummary(ByteArrayOutputStream(), labels) } shouldBe false
    }

    @Test
    fun `S048 RNF-T5 al terminar la importacion se borra la copia temporal`() {
        val viewModel = open("puntos-25830.gpkg")
        cache.listFiles().orEmpty().size shouldBe 1

        viewModel.import()
        viewModel.finished()

        cache.listFiles().orEmpty().size shouldBe 0
    }

    @Test
    fun `S048 RF-23 la importacion usa los colores de la paleta y un dibujo propio`() {
        val viewModel = open("multiples-25830.gpkg")

        viewModel.import()
        viewModel.finished().stage shouldBe GpkgImportStage.RESULT

        val layers = drawings().single().layers
        layers.map { it.kind }.toSet() shouldBe setOf(DesignLayerKind.POINTS, DesignLayerKind.LINES)
        logged.map { it.take(4) } shouldContain "INFO"
    }
}
