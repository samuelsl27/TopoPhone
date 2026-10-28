package es.topophone.app.ui.export

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.app.log.RecordingLog
import es.topophone.app.ui.map.FakePointRepository
import es.topophone.app.ui.points.PointFixtures.manual
import es.topophone.app.ui.points.PointFixtures.measured
import es.topophone.core.model.project.ProjectCrs
import es.topophone.core.model.project.ProjectDefaults
import es.topophone.data.db.TopoPhoneDatabase
import es.topophone.data.gpkg.GpkgExportCrs
import es.topophone.data.project.RoomProjectRepository
import es.topophone.data.project.SaveResult
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
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

/** Exportar a GeoPackage: qué puntos, el sistema, el nombre, escribir el fichero entero o nada y el resumen (S053/RF-1, RF-2, RF-7, RF-9 a RF-13). */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class GpkgExportViewModelTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val db = Room.inMemoryDatabaseBuilder(
        ApplicationProvider.getApplicationContext<Context>(),
        TopoPhoneDatabase::class.java,
    ).build()
    private val projects = RoomProjectRepository(db)
    private val projectId = newProject(ProjectCrs.ETRS89_UTM30)
    private val points = listOf(
        measured("2", projectId = projectId, description = "Peña", createdAtMs = 2_000),
        measured("1", projectId = projectId, createdAtMs = 1_000).copy(hidden = true),
        manual("E1", projectId = projectId, createdAtMs = 3_000),
        measured("borrado", projectId = projectId, createdAtMs = 4_000).copy(deletedAtMs = 5_000),
    )
    private val repository = FakePointRepository(points)
    private val selection = ExportSelection()
    private val log = RecordingLog()

    private fun newProject(crs: ProjectCrs): String = runBlocking {
        projects.create(ProjectDefaults().toDraft().copy(name = "Obra ${crs.name}", crs = crs))
            .shouldBeInstanceOf<SaveResult.Saved>().id
    }

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        db.close()
    }

    private fun viewModel(project: String = projectId, selectionOnly: Boolean = false): GpkgExportViewModel {
        val vm = GpkgExportViewModel(repository, projects, selection, log)
        CoroutineScope(Dispatchers.Unconfined).launch { vm.state.collect {} }
        vm.start(project, selectionOnly)
        return vm
    }

    private fun GpkgExportViewModel.await(predicate: (GpkgExportUiState) -> Boolean): GpkgExportUiState =
        runBlocking { withTimeout(5_000) { state.first(predicate) } }

    private fun work() = folder.newFolder("work")

    private fun countPoints(bytes: ByteArray): Int {
        val file = folder.newFile().apply { writeBytes(bytes) }
        return SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).use {
            it.rawQuery("SELECT COUNT(*) FROM points", null).use { c ->
                c.moveToFirst()
                c.getInt(0)
            }
        }
    }

    @Test
    fun `S053 RF-1 desde el proyecto se exportan todos los activos, ocultos incluidos, y nunca la papelera`() {
        val state = viewModel().await { it.loaded }

        state.pointCount shouldBe 3
        state.projectName shouldBe "Obra ETRS89_UTM30"
        state.canExport shouldBe true
    }

    @Test
    fun `S053 RF-2 desde la seleccion solo se exportan los elegidos`() {
        selection.ids = setOf("id-E1", "id-2", "id-borrado")

        viewModel(selectionOnly = true).await { it.loaded }.pointCount shouldBe 2
    }

    @Test
    fun `S053 RF-11 sin puntos que exportar se dice y no se escribe nada`() {
        selection.ids = emptySet()
        val vm = viewModel(selectionOnly = true)
        val state = vm.await { it.loaded }
        val out = ByteArrayOutputStream()
        val dir = work()

        state.canExport shouldBe false
        runBlocking { vm.writeTo(out, dir) } shouldBe false

        out.size() shouldBe 0
        dir.listFiles().orEmpty().toList() shouldBe emptyList()
    }

    @Test
    fun `S053 RF-6 por defecto se escribe en el sistema del proyecto y se ve su codigo`() {
        val state = viewModel().await { it.loaded }

        state.crs shouldBe GpkgExportCrs.PROJECT
        state.srsId shouldBe 25830
        state.canChooseCrs shouldBe true
    }

    @Test
    fun `S053 RF-7 se puede elegir las geograficas del datum y el codigo cambia`() {
        val vm = viewModel()
        vm.await { it.loaded }

        vm.setCrs(GpkgExportCrs.GEOGRAPHIC)

        val state = vm.await { it.crs == GpkgExportCrs.GEOGRAPHIC }
        state.srsId shouldBe 4258
        vm.setCrs(GpkgExportCrs.PROJECT)
        vm.await { it.srsId == 25830 }
    }

    @Test
    fun `S053 RF-6 un proyecto en geograficas no ofrece elegir porque ya lo esta`() {
        val geographic = newProject(ProjectCrs.ETRS89_GEOGRAPHIC)

        val state = viewModel(geographic).await { it.loaded }

        state.canChooseCrs shouldBe false
        state.srsId shouldBe 4258
    }

    @Test
    fun `S053 RF-7 un proyecto de Canarias en geograficas declara REGCAN95`() {
        val canarias = newProject(ProjectCrs.REGCAN95_UTM28)
        val vm = viewModel(canarias)
        vm.await { it.srsId == 4083 }

        vm.setCrs(GpkgExportCrs.GEOGRAPHIC)

        vm.await { it.srsId == 4081 }
    }

    @Test
    fun `S053 RF-9 el nombre propuesto es el del proyecto, geopackage y la fecha y hora`() {
        val vm = viewModel()
        vm.await { it.loaded }

        vm.fileName(Instant.parse("2026-10-28T10:30:00Z"), ZoneOffset.UTC) shouldBe "Obra ETRS89_UTM30_geopackage_20261028-1030.gpkg"
    }

    @Test
    fun `S053 RF-10 escribir en el destino da un GeoPackage completo con los puntos y no deja nada en la carpeta de trabajo`() {
        val vm = viewModel()
        vm.await { it.loaded }
        val out = ByteArrayOutputStream()
        val dir = work()

        runBlocking { vm.writeTo(out, dir) } shouldBe true

        String(out.toByteArray(), 0, 15, Charsets.US_ASCII) shouldBe "SQLite format 3"
        countPoints(out.toByteArray()) shouldBe 3
        dir.listFiles().orEmpty().toList() shouldBe emptyList() // el temporal se borra
    }

    @Test
    fun `S053 RF-10 para compartir se escribe el fichero entero en la ruta dada`() {
        val vm = viewModel()
        vm.await { it.loaded }
        val shared = File(work(), "Obra.gpkg")

        runBlocking { vm.writeFile(shared) } shouldBe true

        countPoints(shared.readBytes()) shouldBe 3
    }

    @Test
    fun `S053 RF-12 si el destino falla a medias se dice que no se ha exportado y no queda temporal`() {
        val vm = viewModel()
        vm.await { it.loaded }
        val failing = object : OutputStream() {
            override fun write(b: Int) = throw IOException("sin espacio")

            override fun write(b: ByteArray, off: Int, len: Int) = throw IOException("sin espacio")
        }
        val dir = work()

        runBlocking { vm.writeTo(failing, dir) } shouldBe false

        dir.listFiles().orEmpty().toList() shouldBe emptyList()
    }

    @Test
    fun `S053 RF-12 si no se puede escribir el fichero no se toca el destino`() {
        val vm = viewModel()
        vm.await { it.loaded }
        val notADirectory = folder.newFile("no-es-carpeta")
        val out = ByteArrayOutputStream()

        runBlocking { vm.writeTo(out, notADirectory) } shouldBe false

        out.size() shouldBe 0
        runBlocking { vm.writeFile(File(notADirectory, "x.gpkg")) } shouldBe false
    }

    @Test
    fun `S053 RF-8 tras exportar el estado lleva el resumen con los puntos y el sistema declarado`() {
        val vm = viewModel()
        vm.await { it.loaded }

        runBlocking { vm.writeTo(ByteArrayOutputStream(), work()) } shouldBe true

        val summary = vm.await { it.summary != null }.summary.shouldNotBeNull()
        summary.points shouldBe 3
        summary.srsId shouldBe 25830
        summary.customDefinition shouldBe false
    }

    @Test
    fun `S053 RF-13 exportar no modifica ningun punto del proyecto`() {
        val vm = viewModel()
        vm.await { it.loaded }
        val before = runBlocking { repository.pointsForExport(projectId) }

        runBlocking { vm.writeTo(ByteArrayOutputStream(), work()) }

        runBlocking { repository.pointsForExport(projectId) } shouldBe before
        repository.points(projectId).let { runBlocking { it.first() } }.size shouldBe 3
    }

    @Test
    fun `S053 RF-12 el resultado se muestra y queda en el registro sin los puntos`() {
        val vm = viewModel()
        vm.await { it.loaded }

        vm.report(ExportResult.SAVED)
        vm.await { it.result == ExportResult.SAVED }
        vm.report(ExportResult.FAILED)

        vm.await { it.result == ExportResult.FAILED }
        log.messages.any { it.contains("GeoPackage exportado") } shouldBe true
        log.messages.any { it.contains("No se pudo exportar el GeoPackage") } shouldBe true
        vm.report(null)
        vm.await { it.result == null }.result.shouldBeNull()
    }
}
