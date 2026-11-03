package es.topophone.data.backup

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.core.formats.backup.BackupKind
import es.topophone.core.model.codes.AttributeType
import es.topophone.core.model.codes.CodeAttribute
import es.topophone.core.model.codes.CodeGeometry
import es.topophone.core.model.codes.LibraryChoice
import es.topophone.core.model.codes.SurveyCode
import es.topophone.core.model.project.ProjectDefaults
import es.topophone.data.backup.BackupFixture.Companion.columns
import es.topophone.data.backup.BackupFixture.Companion.rows
import es.topophone.data.codes.CodeLibraryRepository
import es.topophone.data.codes.LibraryResult
import es.topophone.data.codes.RoomCodeLibraryRepository
import es.topophone.data.db.TopoPhoneDatabase
import es.topophone.data.project.SaveResult
import io.kotest.matchers.nulls.shouldNotBeNull
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

/** Las bibliotecas viajan en la copia completa y la copia de cada proyecto, en los paquetes (S023/RF-22). */
@RunWith(AndroidJUnit4::class)
class CodeLibraryBackupTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val mine = "cod-a-${System.nanoTime()}.db"
    private val theirs = "cod-b-${System.nanoTime()}.db"
    private lateinit var db: TopoPhoneDatabase
    private lateinit var other: TopoPhoneDatabase
    private lateinit var fixture: BackupFixture
    private lateinit var settings: TestSettings
    private lateinit var repository: BackupRepository
    private lateinit var otherRepository: BackupRepository
    private lateinit var libraries: CodeLibraryRepository
    private lateinit var library: String
    private lateinit var project: String

    private val attributes = listOf(
        CodeAttribute("Material", AttributeType.LIST, "PVC", listOf("PVC", "Hormigón")),
        CodeAttribute("Diámetro", AttributeType.NUMBER, "0.1234567890123"),
    )

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
        libraries = RoomCodeLibraryRepository(db)
        library = (libraries.create("Mi biblioteca") as LibraryResult.Saved).id
        libraries.saveCode(library, SurveyCode("", "ARQ", "Arqueta", CodeGeometry.POINT, 0x1E88E5, attributes))
        libraries.saveCode(library, SurveyCode("", "BOR", "Bordillo", CodeGeometry.LINE, 0xE64A19))
        project =
            (
                fixture.projects.create(
                    ProjectDefaults().toDraft().copy(name = "Con códigos", libraryChoice = LibraryChoice.Library(library)),
                ) as SaveResult.Saved
                ).id
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

    private fun file(name: String): File = context.getDatabasePath(name)

    private fun count(db: String, table: String) = rows(file(db), table).size

    private fun writePackage() = ByteArrayOutputStream().also {
        runBlocking { repository.writePackage(project, it) } shouldBe true
    }.toByteArray()

    private fun import(pkg: ByteArray, choice: ImportChoice? = null): ImportResult {
        val valid = runBlocking { otherRepository.read({ pkg.inputStream() }, BackupKind.PACKAGE) }.shouldBeInstanceOf<BackupRead.Valid>()
        return runBlocking { otherRepository.importPackage(valid, choice, importedLabel = "importado") }
    }

    @Test
    fun `S023 RF-22 el paquete lleva la copia sin la original`() {
        val imported = import(writePackage()).shouldBeInstanceOf<ImportResult.Imported>()

        val copy = runBlocking { RoomCodeLibraryRepository(other).projectCopy(imported.projectId).first() }.shouldNotBeNull()
        copy.name shouldBe "Mi biblioteca"
        copy.codes.map { it.code } shouldBe listOf("ARQ", "BOR")
        copy.codes.first().attributes shouldBe attributes
        // En el otro móvil no hay ninguna biblioteca del usuario: solo viaja la copia del proyecto.
        runBlocking { RoomCodeLibraryRepository(other).libraries().first() } shouldBe emptyList()
        count(theirs, "code_libraries") shouldBe 1
        count(theirs, "library_codes") shouldBe 2
        count(theirs, "code_attributes") shouldBe 2
        count(theirs, "attribute_options") shouldBe 2
    }

    @Test
    fun `S023 RF-22 importar como nuevo da ids propios`() {
        val pkg = writePackage()
        import(pkg).shouldBeInstanceOf<ImportResult.Imported>()

        import(pkg, ImportChoice.AS_NEW).shouldBeInstanceOf<ImportResult.Imported>()

        count(theirs, "code_libraries") shouldBe 2
        count(theirs, "library_codes") shouldBe 4
        count(theirs, "code_attributes") shouldBe 4
        count(theirs, "attribute_options") shouldBe 4
        val cols = columns(file(theirs), "library_codes")
        rows(file(theirs), "library_codes").map { it[cols.indexOf("id")] }.toSet().size shouldBe 4
    }

    @Test
    fun `S023 RF-22 sustituir deja la copia vigente y la vieja en papelera`() {
        val pkg = writePackage()
        val first = import(pkg).shouldBeInstanceOf<ImportResult.Imported>()

        import(pkg, ImportChoice.REPLACE).shouldBeInstanceOf<ImportResult.Imported>()

        count(theirs, "code_libraries") shouldBe 2
        count(theirs, "library_codes") shouldBe 4
        runBlocking { RoomCodeLibraryRepository(other).projectCopy(first.projectId).first() }.shouldNotBeNull().codes.size shouldBe 2
    }

    @Test
    fun `S023 RF-22 la copia completa lleva bibliotecas y copias`() {
        val backup = ByteArrayOutputStream().also { runBlocking { repository.writeBackup(it) } shouldBe true }.toByteArray()
        runBlocking { libraries.delete(library) }
        db.openHelper.writableDatabase.execSQL("DELETE FROM code_libraries")
        count(mine, "code_libraries") shouldBe 0

        val valid = runBlocking { repository.read({ backup.inputStream() }, BackupKind.BACKUP) }.shouldBeInstanceOf<BackupRead.Valid>()
        runBlocking { repository.restore(valid) }.shouldBeInstanceOf<RestoreResult.Restored>()

        runBlocking { libraries.libraries().first().map { it.name to it.codeCount } } shouldBe listOf("Mi biblioteca" to 2)
        runBlocking { libraries.library(library).first() }.shouldNotBeNull().codes.first().attributes shouldBe attributes
        runBlocking { libraries.projectCopy(project).first() }.shouldNotBeNull().codes.size shouldBe 2
        count(mine, "code_libraries") shouldBe 2
        count(mine, "attribute_options") shouldBe 4
    }

    @Test
    fun `S023 RF-22 el paquete no lleva la biblioteca del usuario`() {
        writePackage()
        import(writePackage()).shouldBeInstanceOf<ImportResult.Imported>()

        val cols = columns(file(theirs), "code_libraries")
        rows(file(theirs), "code_libraries").single()[cols.indexOf("project_id")].shouldNotBeNull()
    }
}
