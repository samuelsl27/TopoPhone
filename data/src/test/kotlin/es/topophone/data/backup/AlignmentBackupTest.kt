package es.topophone.data.backup

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.core.formats.backup.BackupKind
import es.topophone.core.model.alignment.AlignmentElement
import es.topophone.core.model.alignment.AlignmentExtent
import es.topophone.core.model.alignment.AlignmentSource
import es.topophone.core.model.alignment.VerticalCurve
import es.topophone.core.model.alignment.VerticalProfile
import es.topophone.core.model.alignment.VerticalVertex
import es.topophone.core.model.project.ProjectDefaults
import es.topophone.data.alignment.AlignmentToSave
import es.topophone.data.alignment.RoomAlignmentRepository
import es.topophone.data.backup.BackupFixture.Companion.columns
import es.topophone.data.backup.BackupFixture.Companion.rows
import es.topophone.data.db.TopoPhoneDatabase
import es.topophone.data.project.SaveResult
import io.kotest.matchers.nulls.shouldBeNull
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

/** Los ejes viajan en las copias y los paquetes con su planta, su rasante y el eje activo del proyecto (S049/RF-16). */
@RunWith(AndroidJUnit4::class)
class AlignmentBackupTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val mine = "axis-a-${System.nanoTime()}.db"
    private val theirs = "axis-b-${System.nanoTime()}.db"
    private lateinit var db: TopoPhoneDatabase
    private lateinit var other: TopoPhoneDatabase
    private lateinit var fixture: BackupFixture
    private lateinit var settings: TestSettings
    private lateinit var repository: BackupRepository
    private lateinit var otherRepository: BackupRepository
    private lateinit var alignments: RoomAlignmentRepository
    private lateinit var project: String
    private lateinit var ids: List<String>

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
        alignments = RoomAlignmentRepository(db)
        project = (fixture.projects.create(ProjectDefaults().toDraft().copy(name = "Con ejes")) as SaveResult.Saved).id
        ids = alignments.add(project, listOf(item("Eje A"), item("Eje B")), 1_792_346_000_000L).ids
        alignments.activate(project, ids[1])
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

    private fun item(name: String) = AlignmentToSave(
        name = name,
        fileName = "ejes.xml",
        source = AlignmentSource.LANDXML,
        elements = listOf(
            AlignmentElement.line(1000.5, 120.0, 440000.123456789012, 4474000.987654321098, 1.0471975511965976),
            AlignmentElement.clothoid(1120.5, 60.0, 440103.9230484541, 4474060.0, 1.0471975511965976, 0.0, -0.0066666666666666),
        ),
        profile = VerticalProfile(
            listOf(VerticalVertex(1000.5, 50.0), VerticalVertex(1100.0, 52.0, VerticalCurve.Parabola(40.0)), VerticalVertex(1180.5, 49.0)),
        ),
        extent = AlignmentExtent(440000.0, 440200.0, 4474000.0, 4474100.0),
        approximated = false,
    )

    private fun file(name: String): File = context.getDatabasePath(name)

    private fun count(db: String, table: String) = rows(file(db), table).size

    private fun projectColumn(db: String, projectId: String, column: String): Any? {
        val cols = columns(file(db), "projects")
        val row = rows(file(db), "projects").first { it[cols.indexOf("id")] == projectId }
        return row[cols.indexOf(column)]
    }

    private fun writePackage() = ByteArrayOutputStream().also {
        runBlocking { repository.writePackage(project, it) } shouldBe true
    }.toByteArray()

    private fun import(pkg: ByteArray, choice: ImportChoice? = null): ImportResult {
        val valid = runBlocking { otherRepository.read({ pkg.inputStream() }, BackupKind.PACKAGE) }.shouldBeInstanceOf<BackupRead.Valid>()
        return runBlocking { otherRepository.importPackage(valid, choice, importedLabel = "importado") }
    }

    @Test
    fun `S049 RF-16 el paquete lleva los ejes con su planta y su rasante`() {
        import(writePackage()).shouldBeInstanceOf<ImportResult.Imported>()

        count(theirs, "alignments") shouldBe 2
        val read = runBlocking { RoomAlignmentRepository(other).alignment(ids[0]) }!!
        read.elements shouldBe item("Eje A").elements
        read.elements[0].startNorthing.toRawBits() shouldBe 4474000.987654321098.toRawBits()
        read.profile shouldBe item("Eje A").profile
        read.fileName shouldBe "ejes.xml"
    }

    @Test
    fun `S049 RF-16 el paquete conserva el eje activo`() {
        import(writePackage()).shouldBeInstanceOf<ImportResult.Imported>()

        runBlocking { RoomAlignmentRepository(other).activeAlignmentId(project).first() } shouldBe ids[1]
        runBlocking { RoomAlignmentRepository(other).summaries(project).first() }.map { it.name } shouldBe listOf("Eje A", "Eje B")
    }

    @Test
    fun `S049 RF-16 importar como nuevo da ids nuevos y el activo es uno de ellos`() {
        val pkg = writePackage()
        import(pkg).shouldBeInstanceOf<ImportResult.Imported>()

        val second = import(pkg, ImportChoice.AS_NEW).shouldBeInstanceOf<ImportResult.Imported>()

        count(theirs, "alignments") shouldBe 4
        val newActive = projectColumn(theirs, second.projectId, "active_alignment_id") as String
        (newActive !in ids) shouldBe true
        val newIds = columns(file(theirs), "alignments").let { cols ->
            rows(file(theirs), "alignments").filter { it[cols.indexOf("project_id")] == second.projectId }.map { it[cols.indexOf("id")] }
        }
        newIds.size shouldBe 2
        (newActive in newIds) shouldBe true
    }

    @Test
    fun `S049 RF-16 sustituir deja los ejes del paquete`() {
        val pkg = writePackage()
        runBlocking { alignments.delete(ids[0]) }
        runBlocking { alignments.activate(project, null) }
        val valid = runBlocking { repository.read({ pkg.inputStream() }, BackupKind.PACKAGE) }.shouldBeInstanceOf<BackupRead.Valid>()

        runBlocking {
            repository.importPackage(valid, ImportChoice.REPLACE, importedLabel = "importado")
        }.shouldBeInstanceOf<ImportResult.Imported>()

        runBlocking { alignments.summaries(project).first() }.map { it.id } shouldBe ids
        runBlocking { alignments.activeAlignmentId(project).first() } shouldBe ids[1]
    }

    @Test
    fun `S049 RF-16 la copia completa restaura los ejes y el activo`() {
        val backup = ByteArrayOutputStream().also { runBlocking { repository.writeBackup(it) } shouldBe true }.toByteArray()
        runBlocking { alignments.delete(ids[1]) }
        runBlocking { alignments.activeAlignmentId(project).first() }.shouldBeNull()

        val valid = runBlocking { repository.read({ backup.inputStream() }, BackupKind.BACKUP) }.shouldBeInstanceOf<BackupRead.Valid>()
        runBlocking { repository.restore(valid) }.shouldBeInstanceOf<RestoreResult.Restored>()

        runBlocking { alignments.summaries(project).first() }.map { it.id } shouldBe ids
        runBlocking { alignments.activeAlignmentId(project).first() } shouldBe ids[1]
        runBlocking { alignments.alignment(ids[1]) }!!.profile shouldBe item("Eje B").profile
    }
}
