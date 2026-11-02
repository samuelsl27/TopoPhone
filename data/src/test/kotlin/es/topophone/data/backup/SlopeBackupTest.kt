package es.topophone.data.backup

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.core.formats.backup.BackupKind
import es.topophone.core.model.alignment.AlignmentElement
import es.topophone.core.model.alignment.AlignmentExtent
import es.topophone.core.model.alignment.AlignmentSource
import es.topophone.core.model.alignment.VerticalProfile
import es.topophone.core.model.alignment.VerticalVertex
import es.topophone.core.model.alignment.slope.SlopeRule
import es.topophone.core.model.alignment.slope.SlopeSide
import es.topophone.core.model.project.ProjectDefaults
import es.topophone.data.alignment.AlignmentToSave
import es.topophone.data.alignment.RoomAlignmentRepository
import es.topophone.data.backup.BackupFixture.Companion.columns
import es.topophone.data.backup.BackupFixture.Companion.rows
import es.topophone.data.db.TopoPhoneDatabase
import es.topophone.data.project.SaveResult
import es.topophone.data.slope.RoomSlopeRepository
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

/** Los taludes viajan en las copias y los paquetes con su eje (S051/RF-4). */
@RunWith(AndroidJUnit4::class)
class SlopeBackupTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val mine = "slop-a-${System.nanoTime()}.db"
    private val theirs = "slop-b-${System.nanoTime()}.db"
    private lateinit var db: TopoPhoneDatabase
    private lateinit var other: TopoPhoneDatabase
    private lateinit var fixture: BackupFixture
    private lateinit var settings: TestSettings
    private lateinit var repository: BackupRepository
    private lateinit var otherRepository: BackupRepository
    private lateinit var alignments: RoomAlignmentRepository
    private lateinit var slopes: RoomSlopeRepository
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
        slopes = RoomSlopeRepository(db)
        project = (fixture.projects.create(ProjectDefaults().toDraft().copy(name = "Con taludes")) as SaveResult.Saved).id
        ids = alignments.add(project, listOf(item("Eje A")), 1_792_346_000_000L).ids
        slopes.add(ids[0], rule(1000.5, 1100.0))
        slopes.add(ids[0], rule(1100.0, 1180.5).copy(side = SlopeSide.LEFT, cutRatio = 1.2500000000000002))
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

    private fun rule(from: Double, to: Double) = SlopeRule(SlopeSide.RIGHT, from, to, 1.5, 2.0, "BD")

    private fun item(name: String) = AlignmentToSave(
        name = name,
        fileName = null,
        source = AlignmentSource.MANUAL,
        elements = listOf(AlignmentElement.line(1000.5, 180.0, 440000.0, 4474000.0, 1.0471975511965976)),
        profile = VerticalProfile(listOf(VerticalVertex(1000.5, 50.0), VerticalVertex(1180.5, 49.0))),
        extent = AlignmentExtent(440000.0, 440200.0, 4474000.0, 4474100.0),
        approximated = false,
    )

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
    fun `S051 RF-4 el paquete lleva los taludes con su relacion sin redondear`() {
        import(writePackage()).shouldBeInstanceOf<ImportResult.Imported>()

        count(theirs, "slope_rules") shouldBe 2
        val read = runBlocking { RoomSlopeRepository(other).rules(ids[0]).first() }
        read.map { it.rule.fromChainage } shouldBe listOf(1100.0, 1000.5) // por lado y PK
        read.first { it.rule.side == SlopeSide.LEFT }.rule.cutRatio.toRawBits() shouldBe 1.2500000000000002.toRawBits()
    }

    @Test
    fun `S051 RF-4 importar como nuevo copia los taludes al eje nuevo`() {
        val pkg = writePackage()
        import(pkg).shouldBeInstanceOf<ImportResult.Imported>()

        import(pkg, ImportChoice.AS_NEW).shouldBeInstanceOf<ImportResult.Imported>()

        count(theirs, "slope_rules") shouldBe 4
        val cols = columns(file(theirs), "slope_rules")
        val owners = rows(file(theirs), "slope_rules").map { it[cols.indexOf("alignment_id")] }.toSet()
        owners.size shouldBe 2
        val alignmentCols = columns(file(theirs), "alignments")
        val axes = rows(file(theirs), "alignments").map { it[alignmentCols.indexOf("id")] }.toSet()
        (owners - axes).isEmpty() shouldBe true
    }

    @Test
    fun `S051 RF-4 la copia completa restaura los taludes borrados`() {
        val backup = ByteArrayOutputStream().also { runBlocking { repository.writeBackup(it) } shouldBe true }.toByteArray()
        runBlocking { alignments.delete(ids[0]) }
        count(mine, "slope_rules") shouldBe 0

        val valid = runBlocking { repository.read({ backup.inputStream() }, BackupKind.BACKUP) }.shouldBeInstanceOf<BackupRead.Valid>()
        runBlocking { repository.restore(valid) }.shouldBeInstanceOf<RestoreResult.Restored>()

        runBlocking { slopes.rules(ids[0]).first() }.size shouldBe 2
    }
}
