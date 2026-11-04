package es.topophone.data.backup

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.core.formats.backup.BackupKind
import es.topophone.core.model.codes.AttributeType
import es.topophone.core.model.codes.CodeAttribute
import es.topophone.core.model.codes.CodeGeometry
import es.topophone.core.model.codes.CodeSymbol
import es.topophone.core.model.codes.PointAttribute
import es.topophone.core.model.codes.PointCode
import es.topophone.core.model.codes.SurveyCode
import es.topophone.core.model.point.GeodeticPosition
import es.topophone.core.model.point.Point
import es.topophone.core.model.point.PointChange
import es.topophone.core.model.point.PointCoordinates
import es.topophone.core.model.point.PointOrigin
import es.topophone.core.model.project.ProjectDefaults
import es.topophone.data.backup.BackupFixture.Companion.rows
import es.topophone.data.codes.CodeSaveResult
import es.topophone.data.codes.ProjectLibraryLinker
import es.topophone.data.codes.RoomCodeLibraryRepository
import es.topophone.data.db.TopoPhoneDatabase
import es.topophone.data.point.PointSaveResult
import es.topophone.data.point.RoomPointRepository
import es.topophone.data.project.RoomProjectRepository
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

/** El codigo de los puntos, sus atributos y los codigos del proyecto viajan en la copia y en los paquetes (S024/RNF-T6). */
@RunWith(AndroidJUnit4::class)
class CodeBackupTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val mine = "cb-a-${System.nanoTime()}.db"
    private val theirs = "cb-b-${System.nanoTime()}.db"
    private lateinit var db: TopoPhoneDatabase
    private lateinit var other: TopoPhoneDatabase
    private lateinit var fixture: BackupFixture
    private lateinit var settings: TestSettings
    private lateinit var repository: BackupRepository
    private lateinit var otherRepository: BackupRepository
    private lateinit var project: String

    private val stored = PointCode(
        code = "POS",
        description = "Poste",
        color = 0xD32F2F,
        symbol = CodeSymbol.TRIANGLE,
        attributes = listOf(
            PointAttribute("Altura", AttributeType.NUMBER, "7.123456789012"),
            PointAttribute("Material", AttributeType.TEXT, "Acero"),
        ),
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
        project = (fixture.projects.create(ProjectDefaults().toDraft().copy(name = "Con codigos")) as SaveResult.Saved).id
        val points = RoomPointRepository(db)
        val point = Point(
            "pc1", project, "1", "", PointOrigin.MANUAL,
            PointCoordinates(GeodeticPosition(40.4, -3.7, null), 1.0, 2.0, null), null, null, false, false, null, 1, null,
            code = stored,
        )
        (points.create(point) as PointSaveResult.Saved)
        points.update(point.copy(code = stored.copy(attributes = stored.attributes.map { it.copy(value = "0") })), PointChange.EDIT)
        RoomCodeLibraryRepository(db).saveProjectCode(
            project,
            SurveyCode(
                "",
                "VAL",
                "Valla",
                CodeGeometry.POINT,
                0x1E88E5,
                listOf(CodeAttribute("Largo", AttributeType.NUMBER)),
                CodeSymbol.STAR,
            ),
        ).shouldBeInstanceOf<CodeSaveResult.Saved>()
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
    fun `S024 RNF-T6 el paquete lleva el codigo de los puntos, sus atributos, el historial y los codigos del proyecto`() {
        val imported = import(writePackage()).shouldBeInstanceOf<ImportResult.Imported>()

        val point = runBlocking { RoomPointRepository(other).points(imported.projectId).first() }.single()
        point.code shouldBe stored.copy(attributes = stored.attributes.map { it.copy(value = "0") })
        val history = runBlocking { RoomPointRepository(other).history(point.id).first() }.single()
        history.previous.code shouldBe stored
        val codes = runBlocking { RoomCodeLibraryRepository(other).projectCodes(imported.projectId).first() }
        codes.map { it.code } shouldBe listOf("VAL")
        codes.single().symbol shouldBe CodeSymbol.STAR
        count(theirs, "point_attributes") shouldBe 2
        count(theirs, "point_history_attributes") shouldBe 2
    }

    @Test
    fun `S024 RNF-T6 importar como nuevo da ids propios a los historiales y a los codigos del proyecto`() {
        val pkg = writePackage()
        val first = import(pkg).shouldBeInstanceOf<ImportResult.Imported>()

        val second = import(pkg, ImportChoice.AS_NEW).shouldBeInstanceOf<ImportResult.Imported>()

        count(theirs, "point_attributes") shouldBe 4
        count(theirs, "point_history_attributes") shouldBe 4
        count(theirs, "library_codes") shouldBe 2
        listOf(first, second).forEach { result ->
            val point = runBlocking { RoomPointRepository(other).points(result.projectId).first() }.single()
            runBlocking { RoomPointRepository(other).history(point.id).first() }.single().previous.code shouldBe stored
            runBlocking { RoomCodeLibraryRepository(other).projectCodes(result.projectId).first() }.size shouldBe 1
        }
    }

    @Test
    fun `S024 RNF-T6 sustituir deja los codigos vigentes y los viejos en la papelera`() {
        val pkg = writePackage()
        val first = import(pkg).shouldBeInstanceOf<ImportResult.Imported>()

        import(pkg, ImportChoice.REPLACE).shouldBeInstanceOf<ImportResult.Imported>()

        count(theirs, "point_attributes") shouldBe 4
        runBlocking { RoomCodeLibraryRepository(other).projectCodes(first.projectId).first() }.size shouldBe 1
    }

    @Test
    fun `S024 RNF-T6 la copia completa restaura el codigo de los puntos y los codigos del proyecto`() {
        val backup = ByteArrayOutputStream().also { runBlocking { repository.writeBackup(it) } shouldBe true }.toByteArray()
        db.openHelper.writableDatabase.execSQL("DELETE FROM code_libraries")
        db.openHelper.writableDatabase.execSQL("DELETE FROM point_attributes")
        count(mine, "point_attributes") shouldBe 0

        val valid = runBlocking { repository.read({ backup.inputStream() }, BackupKind.BACKUP) }.shouldBeInstanceOf<BackupRead.Valid>()
        runBlocking { repository.restore(valid) }.shouldBeInstanceOf<RestoreResult.Restored>()

        count(mine, "point_attributes") shouldBe 2
        count(mine, "point_history_attributes") shouldBe 2
        runBlocking { RoomCodeLibraryRepository(db).projectCodes(project).first() }.map { it.code } shouldBe listOf("VAL")
        runBlocking { RoomPointRepository(db).point("pc1").first() }.shouldNotBeNull().code.shouldNotBeNull().code shouldBe "POS"
    }

    @Test
    fun `S024 RNF-T6 duplicar el proyecto lleva sus codigos`() {
        val projects = RoomProjectRepository(db, libraries = ProjectLibraryLinker(db))

        val copy = runBlocking { projects.duplicate(project, "copia") }.shouldBeInstanceOf<SaveResult.Saved>().id

        runBlocking { RoomCodeLibraryRepository(db).projectCodes(copy).first() }.map { it.code } shouldBe listOf("VAL")
        runBlocking { RoomCodeLibraryRepository(db).projectCodes(project).first() }.map { it.code } shouldBe listOf("VAL")
    }
}
