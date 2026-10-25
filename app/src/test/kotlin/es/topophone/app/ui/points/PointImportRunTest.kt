package es.topophone.app.ui.points

import android.content.Context
import android.database.sqlite.SQLiteException
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.app.log.RecordingLog
import es.topophone.app.ui.map.FakePointRepository
import es.topophone.core.model.log.LogCategory
import es.topophone.core.model.log.LogLevel
import es.topophone.core.model.point.DuplicatePolicy
import es.topophone.core.model.point.ImportCounts
import es.topophone.core.model.point.ImportErrorReason
import es.topophone.core.model.point.Point
import es.topophone.core.model.point.PointOrigin
import es.topophone.core.model.project.ProjectCrs
import es.topophone.core.model.project.ProjectDefaults
import es.topophone.data.db.TopoPhoneDatabase
import es.topophone.data.point.PointRepository
import es.topophone.data.point.PointSaveResult
import es.topophone.data.project.RoomProjectRepository
import es.topophone.data.project.SaveResult
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Importar CSV o TXT, parte 3: importar, el resultado, deshacer, el informe y el log (S028/RF-24 a RF-30, RF-34). */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class PointImportRunTest {

    /** Un repositorio que falla al guardar el lote, o dice que un nombre ya está. */
    private class FlakyRepository(private val delegate: PointRepository, var failure: (() -> PointSaveResult)? = null) :
        PointRepository by delegate {
        override suspend fun createAll(points: List<Point>): PointSaveResult = failure?.invoke() ?: delegate.createAll(points)
    }

    private val db = Room.inMemoryDatabaseBuilder(
        ApplicationProvider.getApplicationContext<Context>(),
        TopoPhoneDatabase::class.java,
    ).build()
    private val projects = RoomProjectRepository(db)
    private val projectId = runBlocking {
        projects.create(ProjectDefaults().toDraft().copy(name = "Calle Mayor 12", crs = ProjectCrs.ETRS89_UTM30))
            .shouldBeInstanceOf<SaveResult.Saved>().id
    }
    private val fake = FakePointRepository(clock = { 77_000 })
    private val repository = FlakyRepository(fake)
    private val log = RecordingLog()
    private val dispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        db.close()
    }

    private fun open(name: String = "etrs89-utm30-utf8-bom-coma.csv"): PointImportViewModel {
        val vm = PointImportViewModel(repository, projects, dispatcher, log)
        vm.debounceMs = 0
        vm.clock = { 123_456 }
        var next = 0
        vm.newId = { "id-${++next}" }
        vm.start(projectId, ImportTestFiles.fixture(name).asSource())
        vm.settled()
        return vm
    }

    private fun imported() = fake.points.value.filter { it.origin == PointOrigin.IMPORTED }

    private val labels = ImportReportLabels(
        title = "Errores de importación",
        summary = "resumen",
        empty = "Ninguna fila con error",
        lineFormat = { line, reason -> "Línea $line: $reason" },
        reason = { "motivo ${it.name}" },
    )

    @Test
    fun `S028 RF-25 importar crea los puntos de las filas validas en el orden del fichero`() {
        val vm = open()

        vm.import()
        val state = vm.settled()

        state.stage shouldBe ImportStage.RESULT
        imported().map { it.name } shouldBe listOf("1", "2", "3", "4", "5", "6")
        imported().all { it.projectId == projectId } shouldBe true
    }

    @Test
    fun `S028 RF-27 los puntos importados llevan lo escrito sin redondear, el origen, la importacion y la fecha`() {
        val vm = open()

        vm.import()
        vm.settled()

        val first = imported().first()
        first.origin shouldBe PointOrigin.IMPORTED
        first.typed.shouldNotBeNull().first shouldBe 440297.988
        first.typed.shouldNotBeNull().second shouldBe 4474244.001
        first.measurement.shouldBeNull()
        first.createdAtMs shouldBe 123_456
        first.description shouldBe "Esquina norte"
        imported().map { it.importId }.toSet().size shouldBe 1
        imported().all { it.importId == vm.state.value.result?.importId } shouldBe true
        imported().map { it.id } shouldBe listOf("id-2", "id-3", "id-4", "id-5", "id-6", "id-7")
    }

    @Test
    fun `S028 RF-28 el resultado da los cuatro recuentos`() {
        fake.points.value = listOf(PointFixtures.manual("1", projectId = projectId), PointFixtures.manual("2", projectId = projectId))
        val vm = open()
        vm.setDuplicates(DuplicatePolicy.SKIP)
        vm.settled()

        vm.import()

        vm.settled().result.shouldNotBeNull().counts shouldBe ImportCounts(rows = 6, valid = 4, skipped = 2, renamed = 0, errors = 0)
    }

    @Test
    fun `S028 RF-28 el resultado cuenta los renombrados y los errores del fichero`() {
        fake.points.value = listOf(PointFixtures.manual("1", projectId = projectId))
        val vm = open("etrs89-utm30-errores.csv")
        vm.setDuplicates(DuplicatePolicy.RENAME)
        vm.settled()

        vm.import()

        val counts = vm.settled().result.shouldNotBeNull().counts
        counts.rows shouldBe 9
        counts.errors shouldBe 5
        counts.renamed shouldBe 3
        counts.valid shouldBe 4
        imported().map { it.name } shouldBe listOf("2", "3", "4", "5")
    }

    @Test
    fun `S028 RF-17 y RF-19 saltar los repetidos no toca los puntos que ya estaban`() {
        val existing = listOf(PointFixtures.manual("1", projectId = projectId), PointFixtures.manual("2", projectId = projectId))
        fake.points.value = existing
        val vm = open()

        vm.import()
        vm.settled()

        fake.points.value.take(2) shouldBe existing
        imported().map { it.name } shouldBe listOf("3", "4", "5", "6")
    }

    @Test
    fun `S028 RF-17 cancelar ante un repetido no deja importar y no crea nada`() {
        fake.points.value = listOf(PointFixtures.manual("1", projectId = projectId))
        val vm = open()
        vm.setDuplicates(DuplicatePolicy.CANCEL)
        vm.settled()

        vm.import()
        val state = vm.settled()

        state.stage shouldBe ImportStage.WIZARD
        state.canImport shouldBe false
        imported() shouldBe emptyList()
    }

    @Test
    fun `S028 RF-17 si entre el analisis y el boton aparece un repetido y se eligio cancelar, no se importa`() {
        val vm = open()
        vm.setDuplicates(DuplicatePolicy.CANCEL)
        vm.settled().canImport shouldBe true
        fake.points.value = listOf(PointFixtures.manual("3", projectId = projectId))

        vm.import()
        val state = vm.settled()

        state.stage shouldBe ImportStage.WIZARD
        state.blockedByDuplicates shouldBe true
        imported() shouldBe emptyList()
    }

    @Test
    fun `S028 RF-26 si no se puede escribir no se crea ningun punto y se dice que no se ha importado`() {
        repository.failure = { throw SQLiteException("disco lleno") }
        val vm = open()

        vm.import()
        val state = vm.settled()

        state.stage shouldBe ImportStage.WIZARD
        state.writeFailed shouldBe true
        state.result.shouldBeNull()
        imported() shouldBe emptyList()
        log.entries.any { it.first == LogLevel.ERROR && it.second == LogCategory.POINTS } shouldBe true
    }

    @Test
    fun `S028 RF-26 tras un fallo se puede volver a intentar`() {
        repository.failure = { throw SQLiteException("disco lleno") }
        val vm = open()
        vm.import()
        vm.settled().writeFailed shouldBe true

        repository.failure = null
        vm.import()

        vm.settled().stage shouldBe ImportStage.RESULT
        vm.state.value.writeFailed shouldBe false
        imported().size shouldBe 6
    }

    @Test
    fun `S028 RF-26 si otro punto ocupa un nombre justo antes de guardar no se importa nada y se vuelve a analizar`() {
        val vm = open()
        repository.failure = {
            fake.points.value = listOf(PointFixtures.manual("2", projectId = projectId))
            PointSaveResult.DuplicateName
        }

        vm.import()
        val state = vm.settled()

        state.stage shouldBe ImportStage.WIZARD
        state.writeFailed shouldBe true
        imported() shouldBe emptyList()
        state.counts.skipped shouldBe 1
    }

    @Test
    fun `S028 RF-30 salir sin pulsar Importar no crea ningun punto`() {
        val before = fake.points.value
        val vm = open()
        vm.setUnit(es.topophone.core.model.project.LinearUnit.INTERNATIONAL_FOOT)
        vm.settled()
        runBlocking { vm.writeReport(ByteArrayOutputStream(), labels) }

        fake.points.value shouldBe before
    }

    @Test
    fun `S028 RF-25 pulsar Importar dos veces seguidas no duplica nada`() {
        val vm = open()

        vm.import()
        vm.import()
        vm.settled()

        imported().size shouldBe 6
    }

    @Test
    fun `S028 RF-25 sin nada que importar el boton no hace nada`() {
        val vm = open("etrs89-utm30-windows1252-puntoycoma.txt")
        vm.setDecimal(es.topophone.core.model.point.ImportDecimal.POINT) // todas las filas fallan
        vm.settled().canImport shouldBe false

        vm.import()

        vm.settled().stage shouldBe ImportStage.WIZARD
        imported() shouldBe emptyList()
    }

    @Test
    fun `S028 RF-29 deshacer manda a la papelera los puntos de la importacion y solo esos`() {
        fake.points.value = listOf(PointFixtures.manual("M1", projectId = projectId))
        val vm = open()
        vm.import()
        vm.settled()

        vm.undo()
        val state = vm.settled()

        state.result.shouldNotBeNull().undone shouldBe true
        state.result.shouldNotBeNull().undoneCount shouldBe 6
        fake.points.value.filter { it.deletedAtMs != null }.map { it.name } shouldBe listOf("1", "2", "3", "4", "5", "6")
        fake.points.value.single { it.name == "M1" }.deletedAtMs.shouldBeNull()
    }

    @Test
    fun `S028 RF-29 deshacer solo cuenta los puntos que siguen en el proyecto`() {
        val vm = open()
        vm.import()
        vm.settled()
        runBlocking { fake.moveToTrash(listOf("id-2", "id-3")) }

        vm.undo()

        vm.settled().result.shouldNotBeNull().undoneCount shouldBe 4
    }

    @Test
    fun `S028 RF-29 deshacer dos veces no hace nada la segunda`() {
        val vm = open()
        vm.import()
        vm.settled()

        vm.undo()
        vm.undo()

        vm.settled().result.shouldNotBeNull().undoneCount shouldBe 6
        vm.state.value.result.shouldNotBeNull().canUndo shouldBe false
    }

    @Test
    fun `S028 RF-29 antes de importar no hay nada que deshacer`() {
        val vm = open()

        vm.undo()

        vm.settled().result.shouldBeNull()
        imported() shouldBe emptyList()
    }

    @Test
    fun `S028 RF-24 el informe lleva todas las filas erroneas con su linea y su motivo`() {
        val vm = open("etrs89-utm30-errores.csv")
        val out = ByteArrayOutputStream()

        val saved = runBlocking { vm.writeReport(out, labels) }

        saved shouldBe true
        out.toString(Charsets.UTF_8) shouldBe
            "Errores de importación\nresumen\n\n" +
            "Línea 3: motivo MISSING_NAME\nLínea 4: motivo NOT_A_NUMBER\nLínea 5: motivo MISSING_COLUMNS\n" +
            "Línea 6: motivo OUTSIDE_CRS_AREA\nLínea 10: motivo NOT_A_NUMBER\n"
    }

    @Test
    fun `S028 RF-24 el informe de un fichero sin errores lo dice`() {
        val vm = open()
        val out = ByteArrayOutputStream()

        runBlocking { vm.writeReport(out, labels) }

        out.toString(Charsets.UTF_8) shouldBe "Errores de importación\nresumen\n\nNinguna fila con error\n"
    }

    @Test
    fun `S028 RF-24 el informe sigue disponible tras importar`() {
        val vm = open("etrs89-utm30-errores.csv")
        vm.import()
        vm.settled()
        val out = ByteArrayOutputStream()

        runBlocking { vm.writeReport(out, labels) } shouldBe true

        out.toString(Charsets.UTF_8).lines().count { it.startsWith("Línea") } shouldBe 5
    }

    @Test
    fun `S028 RF-24 el informe lista mas de 500 errores si los hay`() {
        val text = "a,b\n" + (1..700).joinToString("") { "x$it,y,z\n" }
        val vm = PointImportViewModel(repository, projects, dispatcher, log)
        vm.debounceMs = 0
        vm.start(projectId, ImportTestFiles.of("malo.csv", text).asSource())
        val state = vm.settled()
        val out = ByteArrayOutputStream()

        runBlocking { vm.writeReport(out, labels) }

        state.errors.size shouldBe 500
        state.counts.errors shouldBe 700
        out.toString(Charsets.UTF_8).lines().count { it.startsWith("Línea") } shouldBe 700
    }

    @Test
    fun `S028 RF-24 si no se puede escribir el informe lo dice`() {
        val vm = open("etrs89-utm30-errores.csv")
        val broken = object : OutputStream() {
            override fun write(b: Int) = throw IOException("sin sitio")
        }

        runBlocking { vm.writeReport(broken, labels) } shouldBe false
    }

    @Test
    fun `S028 RF-34 el log anota la importacion con sus recuentos y sin nombres ni coordenadas`() {
        val vm = open("etrs89-utm30-errores.csv")

        vm.import()
        vm.settled()

        val entry = log.entries.single { it.second == LogCategory.POINTS }
        entry.first shouldBe LogLevel.INFO
        entry.third shouldBe "Importación CSV/TXT: 3 importados, 1 saltados, 0 renombrados, 5 con error"
        val text = log.messages.joinToString("\n")
        listOf("etrs89", "Calle Mayor", "440297", "Esquina", "Árbol").forEach { text.contains(it) shouldBe false }
    }

    @Test
    fun `S028 RF-34 el log anota cuando se deshace una importacion`() {
        val vm = open()
        vm.import()
        vm.settled()

        vm.undo()
        vm.settled()

        log.messages.last() shouldBe "Importación CSV/TXT deshecha: 6 puntos a la papelera"
    }

    @Test
    fun `S028 RF-34 el log anota el fallo al escribir sin detalles de los puntos`() {
        repository.failure = { throw SQLiteException("disco lleno") }
        val vm = open()

        vm.import()
        vm.settled()

        val entry = log.entries.single { it.first == LogLevel.ERROR }
        entry.second shouldBe LogCategory.POINTS
        entry.third shouldBe "Importación CSV/TXT fallida: no se pudo escribir en la base de datos"
    }

    @Test
    fun `S028 RF-23 un motivo de error de cada tipo se puede nombrar en el informe`() {
        ImportErrorReason.entries.forEach { labels.reason(it).startsWith("motivo") shouldBe true }
    }
}
