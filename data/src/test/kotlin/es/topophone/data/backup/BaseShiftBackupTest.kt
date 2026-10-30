package es.topophone.data.backup

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.core.formats.backup.BackupKind
import es.topophone.core.model.point.AppliedShift
import es.topophone.core.model.point.BaseShift
import es.topophone.core.model.point.BaseShiftVector
import es.topophone.core.model.point.GeodeticPosition
import es.topophone.core.model.point.Measurement
import es.topophone.core.model.point.MeasurementMode
import es.topophone.core.model.point.Point
import es.topophone.core.model.point.PointCoordinates
import es.topophone.core.model.point.PointQuality
import es.topophone.core.model.project.CorrectionsDatum
import es.topophone.core.model.project.ProjectDefaults
import es.topophone.data.backup.BackupFixture.Companion.columns
import es.topophone.data.backup.BackupFixture.Companion.rows
import es.topophone.data.baseshift.RoomBaseShiftRepository
import es.topophone.data.db.TopoPhoneDatabase
import es.topophone.data.point.RoomPointRepository
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

/** Los desplazamientos de base viajan en las copias y los paquetes con el activo y con el vector de cada punto (S020/RF-8, RF-12; RNF-T6). */
@RunWith(AndroidJUnit4::class)
class BaseShiftBackupTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val mine = "shift-a-${System.nanoTime()}.db"
    private val theirs = "shift-b-${System.nanoTime()}.db"
    private lateinit var db: TopoPhoneDatabase
    private lateinit var other: TopoPhoneDatabase
    private lateinit var fixture: BackupFixture
    private lateinit var settings: TestSettings
    private lateinit var repository: BackupRepository
    private lateinit var otherRepository: BackupRepository
    private lateinit var shifts: RoomBaseShiftRepository
    private lateinit var project: String

    private val vector = BaseShiftVector(-2.147_247_769_869_864, -1.965_396_083_076_484_5, 0.516_280_195_210_129)

    private fun shift(id: String, at: Long) = BaseShift(
        id = id,
        projectId = project,
        createdAtMs = at,
        fromReplay = false,
        knownName = "V-$id",
        knownPointId = null,
        knownEasting = 440_290.123_456_789,
        knownNorthing = 4_474_257.000_000_001,
        knownHeightM = null,
        measured = GeodeticPosition(40.416_784_692_131_955, -3.703_775_259_501_478, 651.323_000_573_553_1),
        epochsUsed = 60,
        vector = vector,
        horizontalOnly = true,
        correctionsDatum = CorrectionsDatum.ETRS89,
        active = false,
        base = null,
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
        shifts = RoomBaseShiftRepository(db)
        project = (fixture.projects.create(ProjectDefaults().toDraft().copy(name = "Con shift")) as SaveResult.Saved).id
        shifts.add(shift("s1", 1), activate = false)
        shifts.add(shift("s2", 2), activate = true)
        RoomPointRepository(db).create(
            Point.measured(
                id = "p1",
                projectId = project,
                name = "P1",
                description = "",
                measurement = Measurement(
                    antenna = GeodeticPosition(40.4, -3.7, 701.3),
                    poleHeightM = 1.8,
                    quality = PointQuality("FIX", 24, 0.6, 1.0, 0.009, 0.014, MeasurementMode.INSTANT, 1, 0, fromReplay = false),
                    rawNmea = "",
                    outOfTolerance = emptySet(),
                    measuredFromMs = 1,
                    measuredToMs = 2,
                    coordinates = PointCoordinates(GeodeticPosition(40.4, -3.7, 699.4), 440_290.4, 4_474_257.4, 648.3),
                    shift = AppliedShift("s2", "V-s2", vector, GeodeticPosition(40.4, -3.7, 699.0)),
                ),
                createdAtMs = 3,
            ),
        )
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
    fun `S020 RF-12 el paquete lleva los shifts y el activo`() {
        import(writePackage()).shouldBeInstanceOf<ImportResult.Imported>()

        count(theirs, "base_shifts") shouldBe 2
        val read = runBlocking { RoomBaseShiftRepository(other).shifts(project).first() }
        read.map { it.id } shouldBe listOf("s2", "s1")
        read.first { it.active }.id shouldBe "s2"
        read.first().vector.dx.toRawBits() shouldBe vector.dx.toRawBits()
        read.first().knownNorthing.toRawBits() shouldBe 4_474_257.000_000_001.toRawBits()
    }

    @Test
    fun `S020 RF-8 como nuevo remapea el calculo de cada punto`() {
        val pkg = writePackage()
        import(pkg).shouldBeInstanceOf<ImportResult.Imported>()

        val second = import(pkg, ImportChoice.AS_NEW).shouldBeInstanceOf<ImportResult.Imported>()

        count(theirs, "base_shifts") shouldBe 4
        val shiftCols = columns(file(theirs), "base_shifts")
        val newShiftIds = rows(file(theirs), "base_shifts")
            .filter { it[shiftCols.indexOf("project_id")] == second.projectId }
            .map { it[shiftCols.indexOf("id")] as String }
        newShiftIds.size shouldBe 2
        (newShiftIds.none { it == "s1" || it == "s2" }) shouldBe true
        val pointCols = columns(file(theirs), "points")
        val copiedPoint = rows(file(theirs), "points").first { it[pointCols.indexOf("project_id")] == second.projectId }
        (copiedPoint[pointCols.indexOf("shift_id")] in newShiftIds) shouldBe true
        copiedPoint[pointCols.indexOf("shift_dx")] shouldBe vector.dx
    }

    @Test
    fun `S020 RF-12 sustituir deja los shifts del paquete`() {
        val pkg = writePackage()
        runBlocking { shifts.delete("s1") }
        runBlocking { shifts.deactivate(project) }
        val valid = runBlocking { repository.read({ pkg.inputStream() }, BackupKind.PACKAGE) }.shouldBeInstanceOf<BackupRead.Valid>()

        runBlocking {
            repository.importPackage(valid, ImportChoice.REPLACE, importedLabel = "importado")
        }.shouldBeInstanceOf<ImportResult.Imported>()

        runBlocking { shifts.shifts(project).first() }.map { it.id } shouldBe listOf("s2", "s1")
        runBlocking { shifts.active(project).first() }!!.id shouldBe "s2"
    }

    @Test
    fun `S020 RF-12 la copia restaura los shifts y el activo`() {
        val backup = ByteArrayOutputStream().also { runBlocking { repository.writeBackup(it) } shouldBe true }.toByteArray()
        runBlocking { shifts.delete("s2") }
        runBlocking { shifts.active(project).first() }.shouldBeNull()

        val valid = runBlocking { repository.read({ backup.inputStream() }, BackupKind.BACKUP) }.shouldBeInstanceOf<BackupRead.Valid>()
        runBlocking { repository.restore(valid) }.shouldBeInstanceOf<RestoreResult.Restored>()

        runBlocking { shifts.shifts(project).first() }.map { it.id } shouldBe listOf("s2", "s1")
        runBlocking { shifts.active(project).first() }!!.id shouldBe "s2"
    }
}
