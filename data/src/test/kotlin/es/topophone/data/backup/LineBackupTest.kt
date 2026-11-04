package es.topophone.data.backup

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.core.formats.backup.BackupKind
import es.topophone.core.model.codes.AttributeType
import es.topophone.core.model.codes.CodeGeometry
import es.topophone.core.model.codes.CodeSymbol
import es.topophone.core.model.codes.PointAttribute
import es.topophone.core.model.codes.PointCode
import es.topophone.core.model.point.GeodeticPosition
import es.topophone.core.model.point.Measurement
import es.topophone.core.model.point.MeasurementMode
import es.topophone.core.model.point.Point
import es.topophone.core.model.point.PointCoordinates
import es.topophone.core.model.point.PointQuality
import es.topophone.core.model.project.ProjectDefaults
import es.topophone.data.backup.BackupFixture.Companion.columns
import es.topophone.data.backup.BackupFixture.Companion.rows
import es.topophone.data.db.TopoPhoneDatabase
import es.topophone.data.line.LineSaveResult
import es.topophone.data.line.LineUpdate
import es.topophone.data.line.NewLine
import es.topophone.data.line.RoomLineRepository
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

/** Las líneas del proyecto viajan en las copias y los paquetes con sus vértices, atributos e historial (S025/RF-26, RNF-T6). */
@RunWith(AndroidJUnit4::class)
class LineBackupTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val mine = "line-a-${System.nanoTime()}.db"
    private val theirs = "line-b-${System.nanoTime()}.db"
    private lateinit var db: TopoPhoneDatabase
    private lateinit var other: TopoPhoneDatabase
    private lateinit var fixture: BackupFixture
    private lateinit var settings: TestSettings
    private lateinit var repository: BackupRepository
    private lateinit var otherRepository: BackupRepository
    private lateinit var lines: RoomLineRepository
    private lateinit var project: String
    private lateinit var lineId: String

    private val bord = PointCode(
        "BORD",
        "Bordillo",
        0x1E88E5,
        CodeSymbol.CIRCLE,
        listOf(PointAttribute("Alto", AttributeType.NUMBER, "0.123456789012345")),
    )

    @Before
    fun open() = runBlocking<Unit> {
        db = TopoPhoneDatabase.build(context, mine)
        other = TopoPhoneDatabase.build(context, theirs)
        fixture = BackupFixture(db).fill()
        settings = TestSettings(folder.newFolder("settings"))
        repository =
            BackupRepository(context, db, settings.archive, "0.13.0", workDir = folder.newFolder("wa"), undoDir = folder.newFolder("ua"))
        otherRepository =
            BackupRepository(context, other, settings.archive, "0.13.0", workDir = folder.newFolder("wb"), undoDir = folder.newFolder("ub"))
        project = (fixture.projects.create(ProjectDefaults().toDraft().copy(name = "Con lineas")) as SaveResult.Saved).id
        val points = RoomPointRepository(db)
        listOf("p1", "p2", "p3").forEach { points.create(point(it)) }
        lines = RoomLineRepository(db, points)
        lineId =
            (
                lines.createFromPoints(
                    NewLine(project, "BORD_1", bord, CodeGeometry.LINE, false, listOf("p3", "p1", "p2")),
                ) as LineSaveResult.Saved
                ).id
        lines.edit(lineId, LineUpdate(pointIds = listOf("p1", "p2", "p3")))
        lines.close(lineId)
    }

    @After
    fun close() {
        settings.close()
        db.close()
        other.close()
        context.deleteDatabase(mine)
        context.deleteDatabase(theirs)
    }

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
    fun `S025 RF-26 el paquete lleva la linea, sus vertices, atributos e historial sin redondear`() {
        import(writePackage()).shouldBeInstanceOf<ImportResult.Imported>()

        count(theirs, "project_lines") shouldBe 1
        count(theirs, "line_vertices") shouldBe 3
        count(theirs, "line_attributes") shouldBe 1
        count(theirs, "line_history") shouldBe 2
        count(theirs, "line_history_vertices") shouldBe 6
        val read = RoomLineRepository(other, RoomPointRepository(other))
        val line = runBlocking { read.line(lineId).first() }.shouldNotBeNull()
        line.name shouldBe "BORD_1"
        line.closed shouldBe true
        line.pointIds shouldBe listOf("p1", "p2", "p3")
        line.code shouldBe bord
        runBlocking { read.history(lineId).first() }.map { it.change.name }.sorted() shouldBe listOf("CLOSED", "VERTICES")
    }

    @Test
    fun `S025 RF-26 importar como nuevo copia las lineas con ids nuevos y sobre los puntos nuevos`() {
        val pkg = writePackage()
        import(pkg).shouldBeInstanceOf<ImportResult.Imported>()

        import(pkg, ImportChoice.AS_NEW).shouldBeInstanceOf<ImportResult.Imported>()

        count(theirs, "project_lines") shouldBe 2
        count(theirs, "line_vertices") shouldBe 6
        val lineCols = columns(file(theirs), "project_lines")
        rows(file(theirs), "project_lines").map { it[lineCols.indexOf("id")] }.toSet().size shouldBe 2
        val pointCols = columns(file(theirs), "points")
        val pointIds = rows(file(theirs), "points").map { it[pointCols.indexOf("id")] }.toSet()
        val vertexCols = columns(file(theirs), "line_vertices")
        (rows(file(theirs), "line_vertices").map { it[vertexCols.indexOf("point_id")] }.toSet() - pointIds).isEmpty() shouldBe true
        val historyCols = columns(file(theirs), "line_history_vertices")
        val historyPoints = rows(file(theirs), "line_history_vertices").map { it[historyCols.indexOf("point_id")] }.toSet()
        (historyPoints - pointIds).isEmpty() shouldBe true
    }

    @Test
    fun `S025 RF-26 la copia completa restaura las lineas borradas`() {
        val backup = ByteArrayOutputStream().also { runBlocking { repository.writeBackup(it) } shouldBe true }.toByteArray()
        runBlocking {
            lines.moveToTrash(lineId)
            lines.deletePermanently(lineId)
        }
        count(mine, "project_lines") shouldBe 0

        val valid = runBlocking { repository.read({ backup.inputStream() }, BackupKind.BACKUP) }.shouldBeInstanceOf<BackupRead.Valid>()
        runBlocking { repository.restore(valid) }.shouldBeInstanceOf<RestoreResult.Restored>()

        runBlocking { lines.line(lineId).first() }.shouldNotBeNull().pointIds shouldBe listOf("p1", "p2", "p3")
    }

    @Test
    fun `S025 RF-26 una linea en la papelera sigue en la papelera al importarla`() {
        runBlocking { lines.moveToTrash(lineId) }

        import(writePackage()).shouldBeInstanceOf<ImportResult.Imported>()

        runBlocking { RoomLineRepository(other, RoomPointRepository(other)).trash(project).first() }.size shouldBe 1
    }
}
