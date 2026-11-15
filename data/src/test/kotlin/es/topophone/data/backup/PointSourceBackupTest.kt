package es.topophone.data.backup

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.core.formats.backup.BackupKind
import es.topophone.core.model.point.GeodeticPosition
import es.topophone.core.model.point.Measurement
import es.topophone.core.model.point.MeasurementMode
import es.topophone.core.model.point.Point
import es.topophone.core.model.point.PointCoordinates
import es.topophone.core.model.point.PointQuality
import es.topophone.core.model.point.PositionSource
import es.topophone.core.model.project.ProjectDefaults
import es.topophone.data.db.TopoPhoneDatabase
import es.topophone.data.point.PointSaveResult
import es.topophone.data.point.RoomPointRepository
import es.topophone.data.project.RoomProjectRepository
import es.topophone.data.project.SaveResult
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith

/** La fuente de la posición de un punto viaja en las copias de seguridad y en los paquetes de proyecto (S022/RF-11, RF-19). */
@RunWith(AndroidJUnit4::class)
class PointSourceBackupTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val mine = "psrc-a-${System.nanoTime()}.db"
    private val theirs = "psrc-b-${System.nanoTime()}.db"
    private lateinit var db: TopoPhoneDatabase
    private lateinit var other: TopoPhoneDatabase
    private lateinit var settings: TestSettings
    private lateinit var repository: BackupRepository
    private lateinit var otherRepository: BackupRepository
    private lateinit var project: String

    @Before
    fun open() = runBlocking {
        db = TopoPhoneDatabase.build(context, mine)
        other = TopoPhoneDatabase.build(context, theirs)
        settings = TestSettings(folder.newFolder("settings"))
        repository =
            BackupRepository(context, db, settings.archive, "0.13.0", workDir = folder.newFolder("wa"), undoDir = folder.newFolder("ua"))
        otherRepository =
            BackupRepository(context, other, settings.archive, "0.13.0", workDir = folder.newFolder("wb"), undoDir = folder.newFolder("ub"))
        project = (RoomProjectRepository(db).create(ProjectDefaults().toDraft().copy(name = "Con fuentes")) as SaveResult.Saved).id
        val points = RoomPointRepository(db, clock = { 50_000 })
        for (source in listOf(PositionSource.PHONE, PositionSource.VIRTUAL, PositionSource.PHONE_MOCK)) {
            points.create(point(source)) shouldBe PointSaveResult.Saved
        }
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

    private fun point(source: PositionSource) = Point.measured(
        id = "id-${source.name}",
        projectId = project,
        name = source.name,
        description = "",
        measurement = Measurement(
            antenna = GeodeticPosition(40.41668, -3.70371, 701.357),
            poleHeightM = 0.0,
            quality = PointQuality(
                "SINGLE",
                9,
                null,
                null,
                2.5,
                null,
                MeasurementMode.INSTANT,
                1,
                0,
                fromReplay = source.simulated,
                source = source,
            ),
            rawNmea = "",
            outOfTolerance = emptySet(),
            measuredFromMs = 1_000,
            measuredToMs = 1_000,
            coordinates = PointCoordinates(GeodeticPosition(40.41668, -3.70371, 701.357), 440_297.988, 4_474_244.001, 648.311),
        ),
        createdAtMs = 1_000,
    )

    private fun sourcesIn(database: TopoPhoneDatabase, projectId: String) =
        runBlocking { RoomPointRepository(database).points(projectId).first() }
            .associate { it.name to it.measurement.shouldNotBeNull().quality.source }

    private val expected = mapOf(
        "PHONE" to PositionSource.PHONE,
        "VIRTUAL" to PositionSource.VIRTUAL,
        "PHONE_MOCK" to PositionSource.PHONE_MOCK,
    )

    @Test
    fun `S022 RF-11 el paquete lleva la fuente de cada punto`() {
        val pkg = ByteArrayOutputStream().also { runBlocking { repository.writePackage(project, it) } shouldBe true }.toByteArray()
        val valid = runBlocking { otherRepository.read({ pkg.inputStream() }, BackupKind.PACKAGE) }.shouldBeInstanceOf<BackupRead.Valid>()

        val imported = runBlocking { otherRepository.importPackage(valid, null, importedLabel = "importado") }
            .shouldBeInstanceOf<ImportResult.Imported>()

        sourcesIn(other, imported.projectId) shouldBe expected
    }

    @Test
    fun `S022 RF-11 la copia restaura la fuente de cada punto`() {
        val backup = ByteArrayOutputStream().also { runBlocking { repository.writeBackup(it) } shouldBe true }.toByteArray()
        runBlocking { db.openHelper.writableDatabase.execSQL("UPDATE points SET position_source = NULL") }
        sourcesIn(db, project).values.toSet() shouldBe setOf(PositionSource.RECEIVER, PositionSource.REPLAY)

        val valid = runBlocking { repository.read({ backup.inputStream() }, BackupKind.BACKUP) }.shouldBeInstanceOf<BackupRead.Valid>()
        runBlocking { repository.restore(valid) }.shouldBeInstanceOf<RestoreResult.Restored>()

        sourcesIn(db, project) shouldBe expected
    }
}
