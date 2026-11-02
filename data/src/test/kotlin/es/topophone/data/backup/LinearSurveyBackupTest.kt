package es.topophone.data.backup

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.core.formats.backup.BackupKind
import es.topophone.core.model.alignment.AlignmentElement
import es.topophone.core.model.alignment.AlignmentExtent
import es.topophone.core.model.alignment.AlignmentSource
import es.topophone.core.model.point.GeodeticPosition
import es.topophone.core.model.point.Measurement
import es.topophone.core.model.point.MeasurementMode
import es.topophone.core.model.point.Point
import es.topophone.core.model.point.PointCoordinates
import es.topophone.core.model.point.PointQuality
import es.topophone.core.model.project.ProjectDefaults
import es.topophone.core.model.survey.linear.LinearSurvey
import es.topophone.core.model.survey.linear.LinearSurveyKind
import es.topophone.core.model.survey.linear.SurveyFlag
import es.topophone.core.model.survey.linear.SurveyPoint
import es.topophone.data.alignment.AlignmentToSave
import es.topophone.data.alignment.RoomAlignmentRepository
import es.topophone.data.backup.BackupFixture.Companion.columns
import es.topophone.data.backup.BackupFixture.Companion.rows
import es.topophone.data.db.TopoPhoneDatabase
import es.topophone.data.linear.RoomLinearSurveyRepository
import es.topophone.data.point.RoomPointRepository
import es.topophone.data.project.SaveResult
import es.topophone.data.section.RoomSectionRepository
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

/** Las tomas lineales viajan en las copias y los paquetes con su eje y sus puntos (S052/RF-23). */
@RunWith(AndroidJUnit4::class)
class LinearSurveyBackupTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val mine = "lin-a-${System.nanoTime()}.db"
    private val theirs = "lin-b-${System.nanoTime()}.db"
    private lateinit var db: TopoPhoneDatabase
    private lateinit var other: TopoPhoneDatabase
    private lateinit var fixture: BackupFixture
    private lateinit var settings: TestSettings
    private lateinit var repository: BackupRepository
    private lateinit var otherRepository: BackupRepository
    private lateinit var surveys: RoomLinearSurveyRepository
    private lateinit var project: String
    private lateinit var axis: String

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
        val alignments = RoomAlignmentRepository(db)
        surveys = RoomLinearSurveyRepository(db, alignments, RoomSectionRepository(db))
        project = (fixture.projects.create(ProjectDefaults().toDraft().copy(name = "Con tomas")) as SaveResult.Saved).id
        axis = alignments.add(project, listOf(item()), 1_792_346_000_000L).ids.single()
        val points = RoomPointRepository(db)
        points.create(point("p1"))
        points.create(point("p2"))
        surveys.open(
            LinearSurvey("t1", project, LinearSurveyKind.LONGITUDINAL, axis, "Perfil", guided = true, toleranceM = 0.3, createdAtMs = 5),
        )
        surveys.addPoint("t1", SurveyPoint("p1", 0, true, 12.345678901234567, -0.1234567890123, 0.5, SurveyFlag.NONE))
        surveys.addPoint("t1", SurveyPoint("p2", 1, false, 99.9, 0.9, null, SurveyFlag.OUT_OF_AXIS))
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

    private fun item() = AlignmentToSave(
        name = "Eje A",
        fileName = null,
        source = AlignmentSource.FIELD,
        elements = listOf(AlignmentElement.line(0.0, 180.0, 440000.0, 4474000.0, 1.0471975511965976)),
        profile = null,
        extent = AlignmentExtent(440000.0, 440200.0, 4474000.0, 4474100.0),
        approximated = false,
    )

    private fun point(id: String) = Point.measured(
        id = id,
        projectId = project,
        name = id,
        description = "",
        measurement = Measurement(
            antenna = GeodeticPosition(40.416_801_234_567_89, -3.703_712_345_678_9, 701.357_123_456),
            poleHeightM = 1.8,
            quality = PointQuality("FIX", 24, 0.6, 1.0, 0.009, 0.014, MeasurementMode.INSTANT, 1, 0, fromReplay = false),
            rawNmea = "",
            outOfTolerance = emptySet(),
            measuredFromMs = 1,
            measuredToMs = 2,
            coordinates = PointCoordinates(GeodeticPosition(40.4168, -3.7037, 699.4), 440_290.4, 4_474_257.4, 648.3),
        ),
        createdAtMs = 3,
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
    fun `S052 RF-23 el paquete lleva la toma y sus puntos con los numeros sin redondear`() {
        import(writePackage()).shouldBeInstanceOf<ImportResult.Imported>()

        count(theirs, "linear_surveys") shouldBe 1
        count(theirs, "linear_survey_points") shouldBe 2
        val read = runBlocking { RoomLinearSurveyRepository(other, RoomAlignmentRepository(other), RoomSectionRepository(other)) }
        val survey = runBlocking { read.survey("t1") }!!
        survey.guided shouldBe true
        survey.toleranceM shouldBe 0.3
        survey.alignmentId shouldBe axis
        val members = runBlocking { read.points("t1").first() }
        members.map { it.pointId to it.included } shouldBe listOf("p1" to true, "p2" to false)
        members[0].chainageM!!.toRawBits() shouldBe 12.345678901234567.toRawBits()
        members[1].flag shouldBe SurveyFlag.OUT_OF_AXIS
    }

    @Test
    fun `S052 RF-23 importar como nuevo copia la toma al proyecto, al eje y a los puntos nuevos`() {
        val pkg = writePackage()
        import(pkg).shouldBeInstanceOf<ImportResult.Imported>()

        import(pkg, ImportChoice.AS_NEW).shouldBeInstanceOf<ImportResult.Imported>()

        count(theirs, "linear_surveys") shouldBe 2
        count(theirs, "linear_survey_points") shouldBe 4
        val surveyCols = columns(file(theirs), "linear_surveys")
        val surveyRows = rows(file(theirs), "linear_surveys")
        val axes = rows(file(theirs), "alignments").map { it[columns(file(theirs), "alignments").indexOf("id")] }.toSet()
        surveyRows.map { it[surveyCols.indexOf("id")] }.toSet().size shouldBe 2
        (surveyRows.map { it[surveyCols.indexOf("alignment_id")] }.toSet() - axes).isEmpty() shouldBe true
        val memberCols = columns(file(theirs), "linear_survey_points")
        val pointIds = rows(file(theirs), "points").map { it[columns(file(theirs), "points").indexOf("id")] }.toSet()
        (rows(file(theirs), "linear_survey_points").map { it[memberCols.indexOf("point_id")] }.toSet() - pointIds).isEmpty() shouldBe true
    }

    @Test
    fun `S052 RF-23 la copia completa restaura las tomas borradas`() {
        val backup = ByteArrayOutputStream().also { runBlocking { repository.writeBackup(it) } shouldBe true }.toByteArray()
        runBlocking { surveys.delete("t1") }
        count(mine, "linear_surveys") shouldBe 0

        val valid = runBlocking { repository.read({ backup.inputStream() }, BackupKind.BACKUP) }.shouldBeInstanceOf<BackupRead.Valid>()
        runBlocking { repository.restore(valid) }.shouldBeInstanceOf<RestoreResult.Restored>()

        runBlocking { surveys.points("t1").first() }.size shouldBe 2
    }

    @Test
    fun `S052 RF-23 una toma sin eje sigue sin eje al copiarla`() {
        runBlocking { RoomAlignmentRepository(db).delete(axis) }

        import(writePackage()).shouldBeInstanceOf<ImportResult.Imported>()

        runBlocking { RoomLinearSurveyRepository(other, RoomAlignmentRepository(other), RoomSectionRepository(other)).survey("t1") }!!
            .alignmentId shouldBe null
    }
}
