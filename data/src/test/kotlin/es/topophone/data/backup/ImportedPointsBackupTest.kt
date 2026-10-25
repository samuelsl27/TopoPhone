package es.topophone.data.backup

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.core.formats.backup.BackupKind
import es.topophone.core.model.point.EntryForm
import es.topophone.core.model.point.GeodeticPosition
import es.topophone.core.model.point.HeightKind
import es.topophone.core.model.point.Point
import es.topophone.core.model.point.PointCoordinates
import es.topophone.core.model.point.PointOrigin
import es.topophone.core.model.point.TypedCoordinates
import es.topophone.core.model.project.ProjectDefaults
import es.topophone.data.backup.BackupFixture.Companion.columns
import es.topophone.data.backup.BackupFixture.Companion.rows
import es.topophone.data.db.TopoPhoneDatabase
import es.topophone.data.project.SaveResult
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.io.ByteArrayOutputStream
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith

/** Los puntos importados viajan en las copias y los paquetes con su origen y su importación (S028/RF-33). */
@RunWith(AndroidJUnit4::class)
class ImportedPointsBackupTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val mine = "imp-a-${System.nanoTime()}.db"
    private val theirs = "imp-b-${System.nanoTime()}.db"
    private lateinit var db: TopoPhoneDatabase
    private lateinit var other: TopoPhoneDatabase
    private lateinit var fixture: BackupFixture
    private lateinit var settings: TestSettings
    private lateinit var repository: BackupRepository
    private lateinit var otherRepository: BackupRepository
    private lateinit var importedProject: String

    @Before
    fun open() = runBlocking {
        db = TopoPhoneDatabase.build(context, mine)
        other = TopoPhoneDatabase.build(context, theirs)
        fixture = BackupFixture(db).fill()
        settings = TestSettings(folder.newFolder("settings"))
        repository =
            BackupRepository(context, db, settings.archive, "0.13.0", workDir = folder.newFolder("wa"), undoDir = folder.newFolder("ua"))
        otherRepository =
            BackupRepository(context, other, settings.archive, "0.13.0", workDir = folder.newFolder("wb"), undoDir = folder.newFolder("ub"))
        importedProject = (fixture.projects.create(ProjectDefaults().toDraft().copy(name = "Importado")) as SaveResult.Saved).id
        fixture.points.createAll(listOf(imported("1"), imported("2"), imported("3")))
        Unit
    }

    @After
    fun close() {
        settings.close()
        db.close()
        other.close()
        context.deleteDatabase(mine)
        context.deleteDatabase(theirs)
    }

    private fun imported(name: String) = Point(
        id = "imp-$name",
        projectId = importedProject,
        name = name,
        description = "importado $name",
        origin = PointOrigin.IMPORTED,
        coordinates = PointCoordinates(
            GeodeticPosition(40.416815093712345, -3.7035330167891234, 699.42312345678),
            440_313.12211953723,
            4_474_258.8768800592,
            648.31136667548324,
        ),
        measurement = null,
        typed = TypedCoordinates(EntryForm.PROJECT_CRS, 440_313.12211953723, 4_474_258.8768800592, 648.31136667548324, HeightKind.PROJECT),
        edited = false,
        hidden = false,
        copyOf = null,
        createdAtMs = 1_792_346_000_000,
        deletedAtMs = null,
        importId = "imp-1",
    )

    private fun file(name: String): File = context.getDatabasePath(name)

    private fun importedRows(db: String): List<List<Any?>> {
        val cols = columns(file(db), "points")
        return rows(file(db), "points").filter { it[cols.indexOf("project_id")] == importedProject }
    }

    private fun column(db: String, name: String): List<Any?> {
        val cols = columns(file(db), "points")
        return importedRows(db).map { it[cols.indexOf(name)] }
    }

    @Test
    fun `S028 RF-33 el paquete conserva los importados`() {
        val pkg = ByteArrayOutputStream().also { runBlocking { repository.writePackage(importedProject, it) } shouldBe true }.toByteArray()

        val valid = runBlocking { otherRepository.read({ pkg.inputStream() }, BackupKind.PACKAGE) }.shouldBeInstanceOf<BackupRead.Valid>()
        runBlocking { otherRepository.importPackage(valid, null, importedLabel = "importado") }.shouldBeInstanceOf<ImportResult.Imported>()

        importedRows(theirs) shouldBe importedRows(mine)
        column(theirs, "origin") shouldBe listOf("IMPORTED", "IMPORTED", "IMPORTED")
        column(theirs, "import_id") shouldBe listOf("imp-1", "imp-1", "imp-1")
        column(theirs, "typed_first") shouldBe listOf(440_313.12211953723, 440_313.12211953723, 440_313.12211953723)
    }

    @Test
    fun `S028 RF-33 la copia restaura los importados`() {
        val backup = ByteArrayOutputStream().also { runBlocking { repository.writeBackup(it) } shouldBe true }.toByteArray()
        val before = importedRows(mine)
        runBlocking { fixture.points.trashImport("imp-1") } shouldBe 3
        column(mine, "deleted_at").all { it != null } shouldBe true

        val valid = runBlocking { repository.read({ backup.inputStream() }, BackupKind.BACKUP) }.shouldBeInstanceOf<BackupRead.Valid>()
        runBlocking { repository.restore(valid) }.shouldBeInstanceOf<RestoreResult.Restored>()

        importedRows(mine) shouldBe before
        column(mine, "origin") shouldBe listOf("IMPORTED", "IMPORTED", "IMPORTED")
        column(mine, "deleted_at") shouldBe listOf(null, null, null)
    }

    @Test
    fun `S028 RF-29 tras restaurar se puede deshacer`() {
        val backup = ByteArrayOutputStream().also { runBlocking { repository.writeBackup(it) } shouldBe true }.toByteArray()
        val valid = runBlocking { repository.read({ backup.inputStream() }, BackupKind.BACKUP) }.shouldBeInstanceOf<BackupRead.Valid>()
        runBlocking { repository.restore(valid) }

        val restored = es.topophone.data.point.RoomPointRepository(db)

        runBlocking { restored.trashImport("imp-1") } shouldBe 3
        runBlocking { restored.points(importedProject).first() } shouldBe emptyList()
    }
}
