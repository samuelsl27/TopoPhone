package es.topophone.data.backup

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.core.formats.backup.BackupKind
import es.topophone.core.model.point.GeodeticPosition
import es.topophone.core.model.point.LineStakeoutMode
import es.topophone.core.model.point.Point
import es.topophone.core.model.point.PointCoordinates
import es.topophone.core.model.point.PointOrigin
import es.topophone.core.model.point.StakeSource
import es.topophone.core.model.point.StakeoutRef
import es.topophone.core.model.project.ProjectDefaults
import es.topophone.data.backup.BackupFixture.Companion.columns
import es.topophone.data.backup.BackupFixture.Companion.rows
import es.topophone.data.db.TopoPhoneDatabase
import es.topophone.data.point.RoomPointRepository
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

/** Las copias y los paquetes llevan el replanteo de una línea con su línea, su modo, su PK y su error lateral (S027/RF-12, RF-16, RNF-T6). */
@RunWith(AndroidJUnit4::class)
class LineStakeoutBackupTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val mine = "ls-a-${System.nanoTime()}.db"
    private val theirs = "ls-b-${System.nanoTime()}.db"
    private lateinit var db: TopoPhoneDatabase
    private lateinit var other: TopoPhoneDatabase
    private lateinit var fixture: BackupFixture
    private lateinit var settings: TestSettings
    private lateinit var repository: BackupRepository
    private lateinit var otherRepository: BackupRepository
    private lateinit var project: String

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
        project = (fixture.projects.create(ProjectDefaults().toDraft().copy(name = "Con linea")) as SaveResult.Saved).id
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

    private fun staked(): Point {
        val base = fixture.measured("BORD_1_1_stkd", project)
        val ref = StakeoutRef(
            designId = "line:l1",
            designName = "BORD_1",
            design = PointCoordinates(GeodeticPosition(40.4168, -3.7038, 699.4), 440_000.123456789012, 4_474_000.987654321098, 648.19),
            dEastM = -0.004,
            dNorthM = 0.012,
            dHorizontalM = 0.0126,
            dHeightM = -0.021,
            designElevationM = 648.19,
            toleranceHM = 0.025,
            toleranceVM = 0.03,
            withinTolerance = true,
            source = StakeSource.ProjectLine("l1", "BORD_1"),
            mode = LineStakeoutMode.ALONG,
            chainageM = 45.123456789,
            lateralOffsetM = -1.5,
            dLateralM = 0.012345678,
        )
        return base.copy(origin = PointOrigin.STAKED, staked = ref)
    }

    private fun stakeRow(db: String): Map<String, Any?> {
        val cols = columns(File(context.getDatabasePath(db).path), "points")
        val row = rows(File(context.getDatabasePath(db).path), "points").first { it[cols.indexOf("name")] == "BORD_1_1_stkd" }
        return cols.zip(row).toMap()
    }

    @Test
    fun `S027 RF-12 el paquete lleva el replanteo de una linea`() {
        runBlocking { fixture.points.create(staked()) }
        val pkg = ByteArrayOutputStream().also { runBlocking { repository.writePackage(project, it) } shouldBe true }.toByteArray()

        val valid = runBlocking { otherRepository.read({ pkg.inputStream() }, BackupKind.PACKAGE) }.shouldBeInstanceOf<BackupRead.Valid>()
        runBlocking { otherRepository.importPackage(valid, null, importedLabel = "importado") }

        val row = stakeRow(theirs)
        row["stake_line_id"] shouldBe "l1"
        row["stake_line_name"] shouldBe "BORD_1"
        row["stake_mode"] shouldBe "ALONG"
        row["stake_chainage"] shouldBe 45.123456789
        row["stake_offset"] shouldBe -1.5
        row["stake_d_lateral"] shouldBe 0.012345678
    }

    @Test
    fun `S027 RF-16 la copia restaura el replanteo aunque la linea ya no exista`() {
        runBlocking { fixture.points.create(staked()) }
        val backup = ByteArrayOutputStream().also { runBlocking { repository.writeBackup(it) } shouldBe true }.toByteArray()

        val valid = runBlocking { repository.read({ backup.inputStream() }, BackupKind.BACKUP) }.shouldBeInstanceOf<BackupRead.Valid>()
        runBlocking { repository.restore(valid) }.shouldBeInstanceOf<RestoreResult.Restored>()

        val restored = runBlocking { RoomPointRepository(db).point("id-BORD_1_1_stkd").first() }.shouldNotBeNull().staked.shouldNotBeNull()
        restored shouldBe staked().staked
    }
}
