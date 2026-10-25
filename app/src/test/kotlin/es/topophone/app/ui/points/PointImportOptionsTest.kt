package es.topophone.app.ui.points

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.app.log.RecordingLog
import es.topophone.app.ui.map.FakePointRepository
import es.topophone.core.model.point.ColumnMapping
import es.topophone.core.model.point.ColumnRole
import es.topophone.core.model.point.DuplicatePolicy
import es.topophone.core.model.point.EntryForm
import es.topophone.core.model.point.HeightKind
import es.topophone.core.model.point.ImportCoordinates
import es.topophone.core.model.point.ImportDecimal
import es.topophone.core.model.point.ImportEncoding
import es.topophone.core.model.point.ImportSeparator
import es.topophone.core.model.point.MappingProblem
import es.topophone.core.model.project.LinearUnit
import es.topophone.core.model.project.ProjectCrs
import es.topophone.core.model.project.ProjectDefaults
import es.topophone.data.db.TopoPhoneDatabase
import es.topophone.data.project.RoomProjectRepository
import es.topophone.data.project.SaveResult
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
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

/** Importar CSV o TXT, parte 2: cambiar las opciones y la asignación y recalcular (S028/RF-8, RF-10 a RF-20, RF-22). */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class PointImportOptionsTest {

    private val db = Room.inMemoryDatabaseBuilder(
        ApplicationProvider.getApplicationContext<Context>(),
        TopoPhoneDatabase::class.java,
    ).build()
    private val projects = RoomProjectRepository(db)
    private val projectId = newProject(ProjectCrs.ETRS89_UTM30)
    private val repository = FakePointRepository()
    private val dispatcher = UnconfinedTestDispatcher()

    private fun newProject(crs: ProjectCrs): String = runBlocking {
        projects.create(ProjectDefaults().toDraft().copy(name = "Proyecto ${crs.name}", crs = crs))
            .shouldBeInstanceOf<SaveResult.Saved>().id
    }

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        db.close()
    }

    private fun open(name: String, project: String = projectId): PointImportViewModel {
        val vm = PointImportViewModel(repository, projects, dispatcher, RecordingLog())
        vm.debounceMs = 0
        vm.start(project, ImportTestFiles.fixture(name).asSource())
        vm.settled()
        return vm
    }

    private val PointImportViewModel.now get() = state.value

    @Test
    fun `S028 RF-7 y RF-22 cambiar el signo decimal recalcula los recuentos`() {
        val vm = open("etrs89-utm30-windows1252-puntoycoma.txt")
        vm.now.counts.valid shouldBe 6

        vm.setDecimal(ImportDecimal.POINT)
        val wrong = vm.settled()

        wrong.options.decimal shouldBe ImportDecimal.POINT
        wrong.counts.valid shouldBe 0
        wrong.counts.errors shouldBe 6
        wrong.canImport shouldBe false

        vm.setDecimal(ImportDecimal.COMMA)
        vm.settled().counts.valid shouldBe 6
        vm.now.canImport shouldBe true
    }

    @Test
    fun `S028 RF-8 la coma de separador y la coma decimal a la vez no dejan importar`() {
        val vm = open("etrs89-utm30-utf8-bom-coma.csv")

        vm.setDecimal(ImportDecimal.COMMA)

        vm.settled().options.decimalConflict shouldBe true
        vm.now.canImport shouldBe false
        vm.setDecimal(ImportDecimal.POINT)
        vm.settled().canImport shouldBe true
    }

    @Test
    fun `S028 RF-5 cambiar el separador recalcula las columnas y la asignacion`() {
        val vm = open("etrs89-utm30-utf8-bom-coma.csv")

        vm.setSeparator(ImportSeparator.SEMICOLON)
        val state = vm.settled()

        state.options.separator shouldBe ImportSeparator.SEMICOLON
        state.columnCount shouldBe 1
        state.canImport shouldBe false
        state.options.mapping.problem shouldBe MappingProblem.MISSING_FIRST

        vm.setSeparator(ImportSeparator.COMMA)
        val back = vm.settled()
        back.columnCount shouldBe 5
        back.options.mapping shouldBe ColumnMapping.penzd(5)
        back.canImport shouldBe true
    }

    @Test
    fun `S028 RF-4 cambiar la codificacion relee el texto y la cabecera`() {
        val vm = open("etrs89-utm30-windows1252-puntoycoma.txt")
        vm.now.preview[2].description shouldBe "Árbol ñandú"

        vm.setEncoding(ImportEncoding.UTF8)
        val utf8 = vm.settled()

        utf8.options.encoding shouldBe ImportEncoding.UTF8
        utf8.headerCells[4].contains("�") shouldBe true
        utf8.options.mapping.roleOf(4) shouldBe ColumnRole.IGNORE // con la codificación equivocada ya no se reconoce la descripción
        utf8.counts.valid shouldBe 6

        vm.setEncoding(ImportEncoding.WINDOWS_1252)
        vm.settled().preview[2].description shouldBe "Árbol ñandú"
    }

    @Test
    fun `S028 RF-9 la casilla de la cabecera cambia que fila es de datos`() {
        val vm = open("etrs89-utm30-utf8-bom-coma.csv")
        vm.now.counts.rows shouldBe 6

        vm.setHasHeader(false)
        val without = vm.settled()

        without.counts.rows shouldBe 7
        without.counts.errors shouldBe 1
        without.errors.single().line shouldBe 1

        vm.setHasHeader(true)
        vm.settled().counts.rows shouldBe 6
    }

    @Test
    fun `S028 RF-11 se cambia el papel de una columna sin tocar las demas`() {
        val vm = open("etrs89-utm30-utf8-bom-coma.csv")

        vm.setColumnRole(3, ColumnRole.IGNORE)
        val state = vm.settled()

        state.options.mapping.roles shouldBe
            listOf(ColumnRole.NAME, ColumnRole.FIRST, ColumnRole.SECOND, ColumnRole.IGNORE, ColumnRole.DESCRIPTION)
        state.canImport shouldBe true
    }

    @Test
    fun `S028 RF-11 sin nombre o con un papel repetido no se puede importar`() {
        val vm = open("etrs89-utm30-utf8-bom-coma.csv")

        vm.setColumnRole(0, ColumnRole.IGNORE)
        vm.settled().canImport shouldBe false
        vm.now.options.mapping.problem shouldBe MappingProblem.MISSING_NAME

        vm.setColumnRole(0, ColumnRole.NAME)
        vm.setColumnRole(4, ColumnRole.FIRST)
        vm.settled().canImport shouldBe false
        vm.now.options.mapping.problem shouldBe MappingProblem.REPEATED_ROLE
    }

    @Test
    fun `S028 RF-11 PENZD y PNEZD se ponen a un toque`() {
        val vm = open("etrs89-utm30-utf8-bom-coma.csv")

        vm.applyPnezd()
        val pnezd = vm.settled()
        pnezd.options.mapping shouldBe ColumnMapping.pnezd(5)
        pnezd.counts.errors shouldBe 6 // el Norte donde va el Este: fuera del ámbito del SC

        vm.applyPenzd()
        val penzd = vm.settled()
        penzd.options.mapping shouldBe ColumnMapping.penzd(5)
        penzd.counts.valid shouldBe 6
    }

    @Test
    fun `S028 RF-11 el fichero PNEZD sin cabecera se importa tras ponerlo en PNEZD`() {
        val vm = open("etrs89-utm30-espacios-pnezd.txt")
        vm.now.counts.errors shouldBe 6

        vm.applyPnezd()

        vm.settled().counts.valid shouldBe 6
    }

    @Test
    fun `S028 RF-10 una asignacion hecha a mano se conserva al cambiar la cabecera`() {
        val vm = open("etrs89-utm30-utf8-bom-coma.csv")
        vm.setColumnRole(3, ColumnRole.IGNORE)
        vm.settled()

        vm.setCoordinates(EntryForm.PROJECT_CRS, HeightKind.PROJECT)
        vm.settled().options.mapping.roleOf(3) shouldBe ColumnRole.IGNORE
    }

    @Test
    fun `S028 RF-12 en geograficas la asignacion se propone con la latitud y la longitud de la cabecera`() {
        val vm = open("etrs89-geograficas.csv")
        vm.now.counts.errors shouldBe 6 // latitud y longitud leídas como Este y Norte

        vm.setCoordinates(EntryForm.GEOGRAPHIC, HeightKind.ELLIPSOIDAL)
        val state = vm.settled()

        state.options.coordinates shouldBe ImportCoordinates(EntryForm.GEOGRAPHIC, HeightKind.ELLIPSOIDAL)
        state.options.mapping shouldBe ColumnMapping.penzd(5)
        state.counts.valid shouldBe 6
        state.canImport shouldBe true
    }

    @Test
    fun `S028 RF-13 la altura elipsoidal no vale con el SC del proyecto, y al volver a el se pasa a altitud`() {
        val vm = open("etrs89-geograficas.csv")

        vm.setCoordinates(EntryForm.PROJECT_CRS, HeightKind.ELLIPSOIDAL)
        vm.settled().options.coordinates.heightKind shouldBe HeightKind.PROJECT

        vm.setCoordinates(EntryForm.GEOGRAPHIC, HeightKind.ELLIPSOIDAL)
        vm.settled()
        vm.setCoordinates(EntryForm.PROJECT_CRS, HeightKind.PROJECT)
        vm.settled().options.coordinates shouldBe ImportCoordinates(EntryForm.PROJECT_CRS, HeightKind.PROJECT)
    }

    @Test
    fun `S028 RF-13 un proyecto en ED50 no deja importar con altura elipsoidal`() {
        val vm = open("ed50-utm30.csv", newProject(ProjectCrs.ED50_UTM30))
        vm.now.canImport shouldBe true

        vm.setCoordinates(EntryForm.GEOGRAPHIC, HeightKind.ELLIPSOIDAL)
        vm.settled().canImport shouldBe false

        vm.setCoordinates(EntryForm.GEOGRAPHIC, HeightKind.PROJECT)
        vm.settled().options.coordinates.isAllowed(ed50 = true) shouldBe true
    }

    @Test
    fun `S028 RF-14 cambiar la unidad recalcula los puntos`() {
        val vm = open("etrs89-utm30-pies.csv")
        vm.now.counts.errors shouldBe 6 // pies leídos como metros: fuera del ámbito

        vm.setUnit(LinearUnit.INTERNATIONAL_FOOT)
        val feet = vm.settled()

        feet.options.unit shouldBe LinearUnit.INTERNATIONAL_FOOT
        feet.counts.valid shouldBe 6
        feet.counts.errors shouldBe 0
    }

    @Test
    fun `S028 RF-16 la casilla de tratar cero como sin altura se recuerda y viene desmarcada`() {
        val vm = open("etrs89-utm30-utf8-bom-coma.csv")
        vm.now.options.zeroAsNoHeight shouldBe false

        vm.setZeroAsNoHeight(true)

        vm.settled().options.zeroAsNoHeight shouldBe true
        vm.now.counts.valid shouldBe 6
    }

    @Test
    fun `S028 RF-17 saltar, renombrar y cancelar ante un repetido cambian los recuentos`() {
        repository.points.value = listOf(PointFixtures.manual("1", projectId = projectId), PointFixtures.manual("2", projectId = projectId))
        val vm = open("etrs89-utm30-utf8-bom-coma.csv")

        vm.now.options.duplicates shouldBe DuplicatePolicy.SKIP
        vm.now.counts.skipped shouldBe 2
        vm.now.counts.valid shouldBe 4

        vm.setDuplicates(DuplicatePolicy.RENAME)
        val renamed = vm.settled()
        renamed.counts.renamed shouldBe 6 // los nombres nuevos ocupan el sitio de los siguientes: se renombran todos
        renamed.counts.valid shouldBe 6
        renamed.preview.map { it.renamedTo }.take(3) shouldBe listOf("3", "4", "5")

        vm.setDuplicates(DuplicatePolicy.CANCEL)
        val cancel = vm.settled()
        cancel.blockedByDuplicates shouldBe true
        cancel.canImport shouldBe false

        vm.setDuplicates(DuplicatePolicy.SKIP)
        vm.settled().canImport shouldBe true
    }

    @Test
    fun `S028 RF-17 cancelar sin repetidos deja importar`() {
        val vm = open("etrs89-utm30-utf8-bom-coma.csv")

        vm.setDuplicates(DuplicatePolicy.CANCEL)

        vm.settled().canImport shouldBe true
    }

    @Test
    fun `S028 RF-19 elegir opciones no modifica los puntos del proyecto`() {
        val existing = listOf(PointFixtures.manual("1", projectId = projectId))
        repository.points.value = existing
        val vm = open("etrs89-utm30-utf8-bom-coma.csv")

        vm.setDuplicates(DuplicatePolicy.RENAME)
        vm.settled()

        repository.points.value shouldBe existing
    }

    @Test
    fun `S028 RF-22 varios cambios seguidos lanzan un solo analisis`() {
        val source = ImportTestFiles.fixture("etrs89-utm30-windows1252-puntoycoma.txt")
        val vm = PointImportViewModel(repository, projects, dispatcher, RecordingLog())
        vm.debounceMs = 150
        vm.start(projectId, source.asSource())
        dispatcher.scheduler.advanceUntilIdle()
        vm.settled()
        val opened = source.opened

        vm.setDecimal(ImportDecimal.POINT)
        vm.setZeroAsNoHeight(true)
        vm.setDecimal(ImportDecimal.COMMA)
        vm.now.analyzing shouldBe true
        dispatcher.scheduler.advanceUntilIdle()
        val state = vm.settled()

        source.opened shouldBe opened + 1
        state.options.decimal shouldBe ImportDecimal.COMMA
        state.options.zeroAsNoHeight shouldBe true
        state.counts.valid shouldBe 6
    }

    @Test
    fun `S028 RF-22 un analisis nuevo cancela el que estaba en marcha y manda el ultimo`() {
        val release = CountDownLatch(1)
        var opens = 0
        val bytes = ImportTestFiles.bytes("etrs89-utm30-windows1252-puntoycoma.txt")
        // La cuarta apertura (el analisis que lanza el primer cambio) se bloquea hasta que se suelte; las demas son normales.
        val source = ImportSource("lento.txt", bytes.size.toLong()) {
            val n = synchronized(this) { ++opens }
            object : java.io.ByteArrayInputStream(bytes) {
                override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                    if (n == 4) release.await(5, TimeUnit.SECONDS)
                    return super.read(buffer, offset, length)
                }
            }
        }
        val vm = PointImportViewModel(repository, projects, Dispatchers.IO, RecordingLog())
        vm.debounceMs = 0
        vm.start(projectId, source)
        runBlocking { withTimeout(10_000) { vm.state.first { it.stage == ImportStage.WIZARD && !it.analyzing } } }

        vm.setDecimal(ImportDecimal.POINT) // abre por tercera vez y se queda bloqueado leyendo
        vm.setDecimal(ImportDecimal.COMMA) // lo cancela y lanza otro
        release.countDown()
        val state = runBlocking {
            withTimeout(10_000) { vm.state.first { !it.analyzing && it.options.decimal == ImportDecimal.COMMA && it.counts.valid == 6 } }
        }

        state.options.decimal shouldBe ImportDecimal.COMMA
        state.counts.valid shouldBe 6
        state.counts.errors shouldBe 0
    }
}
