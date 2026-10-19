package es.topophone.data.backup

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.core.formats.backup.BackupArchive
import es.topophone.core.formats.backup.BackupKind
import es.topophone.core.formats.backup.BackupReadResult
import es.topophone.core.model.project.ProjectDefaults
import es.topophone.data.backup.BackupFixture.Companion.columns
import es.topophone.data.backup.BackupFixture.Companion.rows
import es.topophone.data.db.TopoPhoneDatabase
import io.kotest.matchers.collections.shouldNotContainAnyOf
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.io.ByteArrayOutputStream
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith

/** Paquetes de proyecto: exportar e importar con sus ids o como proyecto nuevo (S013/RF-24, RF-25, RF-26, RF-28). */
@RunWith(AndroidJUnit4::class)
class PackageTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val mine = "pkg-a-${System.nanoTime()}.db"
    private val theirs = "pkg-b-${System.nanoTime()}.db"
    private lateinit var db: TopoPhoneDatabase
    private lateinit var other: TopoPhoneDatabase
    private lateinit var fixture: BackupFixture
    private lateinit var settings: TestSettings
    private lateinit var repository: BackupRepository
    private lateinit var otherRepository: BackupRepository
    private lateinit var pkg: ByteArray

    @Before
    fun open() {
        db = TopoPhoneDatabase.build(context, mine)
        other = TopoPhoneDatabase.build(context, theirs)
        fixture = BackupFixture(db).fill()
        settings = TestSettings(folder.newFolder("settings"))
        repository =
            BackupRepository(context, db, settings.archive, "0.13.0", workDir = folder.newFolder("wa"), undoDir = folder.newFolder("ua"))
        otherRepository =
            BackupRepository(context, other, settings.archive, "0.13.0", workDir = folder.newFolder("wb"), undoDir = folder.newFolder("ub"))
        pkg = ByteArrayOutputStream().also { runBlocking { repository.writePackage(fixture.streetId, it) } shouldBe true }.toByteArray()
    }

    @After
    fun close() {
        settings.close()
        db.close()
        other.close()
        context.deleteDatabase(mine)
        context.deleteDatabase(theirs)
    }

    private fun file(name: String): File = context.getDatabasePath(name)

    private fun import(into: BackupRepository, choice: ImportChoice? = null, bytes: ByteArray = pkg): ImportResult = runBlocking {
        val valid = into.read({ bytes.inputStream() }, BackupKind.PACKAGE).shouldBeInstanceOf<BackupRead.Valid>()
        into.importPackage(valid, choice, importedLabel = "importado")
    }

    private fun column(db: String, table: String, column: String, where: (List<Any?>, List<String>) -> Boolean = { _, _ -> true }) =
        columns(file(db), table).let { cols -> rows(file(db), table).filter { where(it, cols) }.map { it[cols.indexOf(column)] } }

    @Test
    fun `S013 RF-24 el paquete lleva el proyecto y su recuento`() {
        val read = BackupArchive.read(pkg.inputStream(), BackupKind.PACKAGE, TopoPhoneDatabase.VERSION, folder.newFolder("out"))

        val valid = read.shouldBeInstanceOf<BackupReadResult.Valid>()
        valid.manifest.projectId shouldBe fixture.streetId
        valid.manifest.projectName shouldBe "Calle Mayor 12"
        valid.manifest.projectCount shouldBe 1
        valid.manifest.pointCount shouldBe 4
        valid.files.keys shouldBe setOf(BackupArchive.DATABASE)
    }

    @Test
    fun `S013 RF-24 un proyecto que no existe no se exporta`() {
        runBlocking { repository.writePackage("no-existe", ByteArrayOutputStream()) } shouldBe false
    }

    @Test
    fun `S013 RF-25 en otro movil se importa entero y con sus ids`() {
        import(otherRepository) shouldBe ImportResult.Imported(fixture.streetId, "Calle Mayor 12")

        for (table in listOf("points", "point_history", "map_viewports")) {
            rows(file(theirs), table) shouldBe
                rows(file(mine), table).let { all ->
                    if (table == "points") all.filter { it[columns(file(mine), table).indexOf("project_id")] == fixture.streetId } else all
                }
        }
        column(theirs, "projects", "folder_id") shouldBe listOf(null)
        column(theirs, "projects", "name") shouldBe listOf("Calle Mayor 12")
    }

    @Test
    fun `S013 RF-26 si el proyecto ya esta en el movil se pregunta`() {
        val before = rows(file(mine), "points")

        import(repository) shouldBe ImportResult.NeedsChoice("Calle Mayor 12")

        rows(file(mine), "points") shouldBe before
    }

    @Test
    fun `S013 RF-28 como nuevo lleva ids nuevos y el nombre importado`() {
        val result = import(repository, ImportChoice.AS_NEW).shouldBeInstanceOf<ImportResult.Imported>()

        result.name shouldBe "Calle Mayor 12 (importado)"
        result.projectId shouldNotBe fixture.streetId
        val isNew = { row: List<Any?>, cols: List<String> -> row[cols.indexOf("project_id")] == result.projectId }
        val newIds = column(mine, "points", "id", isNew)
        newIds.size shouldBe 5 // también el de la papelera
        newIds shouldNotContainAnyOf listOf("id-1", "id-E1", "id-2", "id-3", "id-4")
        // El de la papelera sigue en la papelera de su proyecto, con su id nuevo como clave.
        column(mine, "points", "trash_key", isNew).filter { it != "" } shouldBe column(mine, "points", "id") { row, cols ->
            isNew(row, cols) && row[cols.indexOf("trash_key")] != ""
        }
        // El historial apunta a los puntos nuevos.
        val history = column(mine, "point_history", "point_id")
        history.count { it in newIds } shouldBe 1
        column(mine, "map_viewports", "project_id").contains(result.projectId) shouldBe true
        column(mine, "projects", "folder_id") { row, cols -> row[cols.indexOf("id")] == result.projectId } shouldBe listOf(null)
    }

    @Test
    fun `S013 RF-28 importado dos veces como nuevo`() {
        import(repository, ImportChoice.AS_NEW).shouldBeInstanceOf<ImportResult.Imported>().name shouldBe "Calle Mayor 12 (importado)"
        import(repository, ImportChoice.AS_NEW).shouldBeInstanceOf<ImportResult.Imported>().name shouldBe "Calle Mayor 12 (importado 2)"
    }

    @Test
    fun `S013 RF-28 un nombre ocupado por otro proyecto se cambia`() {
        runBlocking {
            es.topophone.data.project.RoomProjectRepository(other).create(ProjectDefaults().toDraft().copy(name = "calle mayor 12"))
        }

        import(otherRepository) shouldBe ImportResult.Imported(fixture.streetId, "Calle Mayor 12 (importado)")
    }

    @Test
    fun `S013 RF-26 con el id en la papelera se importa como otro`() {
        runBlocking { fixture.projects.moveToTrash(fixture.streetId) }

        val result = import(repository).shouldBeInstanceOf<ImportResult.Imported>()

        result.projectId shouldNotBe fixture.streetId
        result.name shouldBe "Calle Mayor 12"
    }

    @Test
    fun `S013 RF-27 sustituir pasa el del movil a la papelera`() {
        runBlocking { fixture.points.create(fixture.measured("5", fixture.streetId)) } // Medido después de exportar el paquete.

        import(repository, ImportChoice.REPLACE) shouldBe ImportResult.Imported(fixture.streetId, "Calle Mayor 12")

        val projectCols = columns(file(mine), "projects")
        val projects = rows(file(mine), "projects").filter { it[projectCols.indexOf("name")] == "Calle Mayor 12" }
        val active = projects.single { it[projectCols.indexOf("deleted_at")] == null }
        val trashed = projects.single { it[projectCols.indexOf("deleted_at")] != null }
        active[projectCols.indexOf("id")] shouldBe fixture.streetId
        active[projectCols.indexOf("folder_id")] shouldBe fixture.folderId // RF-27: en su misma carpeta.
        trashed[projectCols.indexOf("id")] shouldNotBe fixture.streetId
        trashed[projectCols.indexOf("folder_id")] shouldBe fixture.folderId
        val inProject = { id: Any? -> { row: List<Any?>, cols: List<String> -> row[cols.indexOf("project_id")] == id } }
        column(mine, "points", "name", inProject(fixture.streetId)).toSet() shouldBe setOf("1", "E1", "2", "3", "4")
        column(mine, "points", "name", inProject(trashed[projectCols.indexOf("id")])).toSet() shouldBe setOf("1", "E1", "2", "3", "4", "5")
        column(mine, "points", "id", inProject(trashed[projectCols.indexOf("id")])) shouldNotContainAnyOf listOf("id-1", "id-5")
        column(mine, "point_history", "id").size shouldBe 2 // el del importado y el del que está en la papelera
    }

    @Test
    fun `S013 RF-27 el mismo paquete otra vez se reconoce`() {
        import(repository, ImportChoice.REPLACE).shouldBeInstanceOf<ImportResult.Imported>()

        import(repository) shouldBe ImportResult.NeedsChoice("Calle Mayor 12")
    }

    @Test
    fun `S013 RF-27 al sustituir, un nombre ocupado por otro se cambia`() {
        db.openHelper.writableDatabase.execSQL("UPDATE projects SET name = 'Calle Mayor 12 v2' WHERE id = ?", arrayOf(fixture.streetId))
        runBlocking { fixture.projects.create(ProjectDefaults().toDraft().copy(name = "Calle Mayor 12")) }

        import(repository, ImportChoice.REPLACE) shouldBe ImportResult.Imported(fixture.streetId, "Calle Mayor 12 (importado)")
    }
}
