package es.topophone.app.ui.cad

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.app.log.RecordingLog
import es.topophone.app.ui.map.FakePointRepository
import es.topophone.app.ui.points.ImportSource
import es.topophone.core.model.cad.CadEntityKind
import es.topophone.core.model.cad.CadIgnored
import es.topophone.core.model.cad.CadIgnoredReason
import es.topophone.core.model.log.LogCategory
import es.topophone.core.model.log.LogLevel
import es.topophone.core.model.project.ProjectCrs
import es.topophone.core.model.project.ProjectDefaults
import es.topophone.data.cad.CadChunkWrite
import es.topophone.data.cad.CadDrawingImporter
import es.topophone.data.cad.CadImportFailure
import es.topophone.data.cad.CadRepository
import es.topophone.data.cad.RoomCadRepository
import es.topophone.data.db.TopoPhoneDatabase
import es.topophone.data.map.RoomMapViewportRepository
import es.topophone.data.project.RoomProjectRepository
import es.topophone.data.project.SaveResult
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
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

/** Importar un DXF, parte 2: importar con progreso, cancelar, fallar, el resumen y el log (S040/RF-11, RF-12, RF-13, RF-18, RF-32). */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class CadImportRunTest {

    private val db = Room.inMemoryDatabaseBuilder(
        ApplicationProvider.getApplicationContext<Context>(),
        TopoPhoneDatabase::class.java,
    ).build()
    private val projects = RoomProjectRepository(db)
    private val cad = RoomCadRepository(db)
    private val viewports = RoomMapViewportRepository(db)
    private val points = FakePointRepository()
    private val dispatcher = UnconfinedTestDispatcher()
    private val log = RecordingLog()
    private val projectId = runBlocking {
        projects.create(ProjectDefaults().toDraft().copy(name = "Calle Mayor 12", crs = ProjectCrs.ETRS89_UTM30))
            .shouldBeInstanceOf<SaveResult.Saved>().id
    }

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        db.close()
    }

    private fun open(source: ImportSource, repository: CadRepository = cad): CadImportViewModel {
        val vm = CadImportViewModel(cad, CadDrawingImporter(repository), projects, points, viewports, dispatcher, log)
        vm.start(projectId, source)
        vm.settled()
        return vm
    }

    private fun open(name: String, repository: CadRepository = cad) = open(DxfTestFiles.source(name), repository)

    private fun done(vm: CadImportViewModel): CadImportUiState = runBlocking {
        withTimeout(10_000) { vm.state.first { it.stage != CadImportStage.IMPORTING && it.stage != CadImportStage.LOADING } }
    }

    private fun count(table: String): Int = db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM $table").use {
        it.moveToFirst()
        it.getInt(0)
    }

    @Test
    fun `S040 RF-18 importar da el resumen y deja el dibujo en el proyecto`() {
        val vm = open("r2018.dxf")

        vm.import()
        val state = done(vm)

        state.stage shouldBe CadImportStage.RESULT
        val summary = state.result!!.summary
        summary.imported[CadEntityKind.SPLINE] shouldBe 2
        summary.importedTotal shouldBe 15
        summary.layers shouldBe 5
        summary.incomplete shouldBe false
        val drawing = runBlocking { cad.drawings(projectId).first() }.single()
        drawing.drawing.id shouldBe state.result!!.drawingId
        drawing.drawing.fileName shouldBe "r2018.dxf"
    }

    @Test
    fun `S040 RF-11 el progreso llega al tamano del fichero`() {
        val vm = open("r2018.dxf")

        vm.import()

        done(vm).importBytes shouldBe DxfTestFiles.bytes("r2018.dxf").size.toLong()
    }

    @Test
    fun `S040 RF-12 sin poder importar no se importa nada`() {
        val vm = open("sin-elementos.dxf")

        vm.import()

        vm.state.value.stage shouldBe CadImportStage.PROBLEM
        count("cad_drawings") shouldBe 0
    }

    @Test
    fun `S040 RF-10 mientras un aviso de lejania no se confirme no se importa`() {
        runBlocking { viewports.save(projectId, es.topophone.core.model.map.MapViewport(37.3891, -5.9845, 15.0)) }
        val vm = open("r2000.dxf")

        vm.import()
        vm.state.value.stage shouldBe CadImportStage.CONFIG
        count("cad_drawings") shouldBe 0

        vm.confirmFar()
        vm.import()
        done(vm).stage shouldBe CadImportStage.RESULT
    }

    @Test
    fun `S040 RF-11 y RF-12 cancelar durante la lectura no deja nada`() {
        lateinit var vm: CadImportViewModel
        val data = DxfTestFiles.bytes("r2018.dxf")
        var opens = 0
        val source = ImportSource("r2018.dxf", data.size.toLong()) {
            opens++
            if (opens <= 2) {
                ByteArrayInputStream(data) // la lectura del principio y la exploración
            } else {
                object : ByteArrayInputStream(data) {
                    override fun read(b: ByteArray, off: Int, len: Int): Int {
                        vm.cancel() // el usuario pulsa «Cancelar» con la importación a medias
                        return super.read(b, off, len)
                    }
                }
            }
        }
        vm = open(source)

        vm.import()
        val state = done(vm)

        state.stage shouldBe CadImportStage.CONFIG
        state.cancelled shouldBe true
        state.result shouldBe null
        count("cad_drawings") shouldBe 0
        count("cad_layers") shouldBe 0
        count("cad_chunks") shouldBe 0
        log.entries.last { it.second == LogCategory.APP }.third shouldContain "cancelada"
    }

    @Test
    fun `S040 RF-12 un fallo de escritura vuelve a la configuracion sin guardar nada`() {
        val failing = object : CadRepository by cad {
            override suspend fun addChunks(chunks: List<CadChunkWrite>) = throw IOException("disco lleno")
        }
        val vm = open("r2018.dxf", failing)

        vm.import()
        val state = done(vm)

        state.stage shouldBe CadImportStage.CONFIG
        state.failure shouldBe CadImportFailure.WRITE_ERROR
        state.cancelled shouldBe false
        count("cad_drawings") shouldBe 0
        log.entries.last { it.first == LogLevel.ERROR }.third shouldContain "fallida"
    }

    @Test
    fun `S040 RF-12 tras un fallo se puede volver a intentar`() {
        var fail = true
        val flaky = object : CadRepository by cad {
            override suspend fun addChunks(chunks: List<CadChunkWrite>) {
                if (fail) throw IOException("disco lleno")
                cad.addChunks(chunks)
            }
        }
        val vm = open("r2018.dxf", flaky)
        vm.import()
        done(vm).failure shouldBe CadImportFailure.WRITE_ERROR

        fail = false
        vm.import()

        done(vm).stage shouldBe CadImportStage.RESULT
        count("cad_drawings") shouldBe 1
    }

    @Test
    fun `S040 RF-13 un fichero cortado se importa con lo leido y el resumen dice que esta incompleto`() {
        val vm = open("cortado.dxf")

        vm.import()
        val state = done(vm)

        state.stage shouldBe CadImportStage.RESULT
        state.result!!.summary.incomplete shouldBe true
        state.result!!.summary.importedTotal shouldBe 3
    }

    @Test
    fun `S040 RF-16 y RF-18 el resumen cuenta lo ignorado por tipo`() {
        val vm = open("ignorados.dxf")

        vm.import()
        val summary = done(vm).result!!.summary

        summary.ignoredTotal shouldBe 9
        summary.ignored[CadIgnored(CadIgnoredReason.UNSUPPORTED_TYPE, "TEXT")] shouldBe 2
        summary.importedTotal shouldBe 2
    }

    @Test
    fun `S040 RF-18 el resumen se guarda como un fichero de texto con una linea por concepto`() {
        val vm = open("ignorados.dxf")
        vm.import()
        done(vm)
        val labels = CadSummaryLabels(
            title = "Importación de DXF",
            imported = "Importados",
            ignored = "Ignorados",
            layers = "Capas",
            incomplete = "Dibujo incompleto",
            kind = { it.name },
            ignoredItem = { "${it.reason.name} ${it.dxfType.orEmpty()}".trim() },
        )

        val out = ByteArrayOutputStream()
        runBlocking { vm.writeSummary(out, labels) } shouldBe true

        val text = out.toString(Charsets.UTF_8)
        text.lines() shouldBe listOf(
            "Importación de DXF",
            "Importados: 2",
            "  POINT: 1",
            "  LINE: 1",
            "Ignorados: 9",
            "  UNSUPPORTED_TYPE 3DFACE: 1",
            "  UNSUPPORTED_TYPE DIMENSION: 1",
            "  UNSUPPORTED_TYPE ELLIPSE: 1",
            "  UNSUPPORTED_TYPE HATCH: 1",
            "  UNSUPPORTED_TYPE LEADER: 1",
            "  UNSUPPORTED_TYPE MTEXT: 1",
            "  UNSUPPORTED_TYPE SOLID: 1",
            "  UNSUPPORTED_TYPE TEXT: 2",
            "Capas: 2",
            "",
        )
    }

    @Test
    fun `S040 RF-18 sin importar no hay resumen que escribir`() {
        val vm = open("r2018.dxf")

        runBlocking { vm.writeSummary(ByteArrayOutputStream(), CadSummaryLabels("", "", "", "", "", { "" }, { "" })) } shouldBe false
    }

    @Test
    fun `S040 RF-32 el log anota la importacion con recuentos y sin nombres ni coordenadas`() {
        val vm = open("r2018.dxf")

        vm.import()
        done(vm)

        val line = log.entries.last { it.second == LogCategory.APP }.third
        line shouldContain "15 elementos"
        line shouldContain "5 capas"
        line shouldNotContain "r2018"
        line shouldNotContain "Acera"
        line shouldNotContain "4474"
        line shouldNotContain "440"
    }

    @Test
    fun `S040 RF-20 importar otra vez el mismo fichero da otro dibujo`() {
        val first = open("r2018.dxf")
        first.import()
        done(first)

        val second = open("r2018.dxf")
        second.state.value.duplicateName shouldBe true
        second.state.value.canImport shouldBe false
        second.confirmDuplicate()
        second.import()
        done(second)

        runBlocking { cad.drawings(projectId).first() }.size shouldBe 2
    }

    @Test
    fun `S040 RF-12 despues del resultado importar otra vez no hace nada`() {
        val vm = open("r2018.dxf")
        vm.import()
        done(vm)

        vm.import()

        count("cad_drawings") shouldBe 1
    }
}
