package es.topophone.data.backup

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.core.formats.backup.BackupKind
import es.topophone.core.model.localization.LocalizationPair
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
import es.topophone.data.localization.ApplyResult
import es.topophone.data.localization.LocalizedCrsEntity
import es.topophone.data.localization.RoomLocalizationRepository
import es.topophone.data.point.RoomPointRepository
import es.topophone.data.project.SaveResult
import io.kotest.matchers.nulls.shouldBeNull
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

/** Las localizaciones viajan en las copias y los paquetes con sus pares, la activa y el punto que localizan (S039/RF-19, RF-26; RNF-T6). */
@RunWith(AndroidJUnit4::class)
class LocalizationBackupTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val mine = "loc-a-${System.nanoTime()}.db"
    private val theirs = "loc-b-${System.nanoTime()}.db"
    private lateinit var db: TopoPhoneDatabase
    private lateinit var other: TopoPhoneDatabase
    private lateinit var fixture: BackupFixture
    private lateinit var settings: TestSettings
    private lateinit var repository: BackupRepository
    private lateinit var otherRepository: BackupRepository
    private lateinit var localizations: RoomLocalizationRepository
    private lateinit var project: String

    private fun pair(id: String, pointId: String?) =
        LocalizationPair(id, pointId, "P-$id", 440_290.123_456_789, 4_474_257.000_000_001, 648.3, 440_390.4, 4_474_207.4, 648.55)

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
        localizations = RoomLocalizationRepository(db)
        project = (fixture.projects.create(ProjectDefaults().toDraft().copy(name = "Localizado")) as SaveResult.Saved).id
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
                ),
                createdAtMs = 3,
            ),
        )
        // Una localización archivada, la activa y un borrador, con el punto localizado por la activa.
        localizations.addPair(project, pair("a", "p1"))
        localizations.apply(project, nowMs = 1).shouldBeInstanceOf<ApplyResult.Applied>()
        localizations.startDraft(project)
        localizations.setFixScale(project, true)
        localizations.apply(project, nowMs = 2).shouldBeInstanceOf<ApplyResult.Applied>()
        localizations.startDraft(project)
        localizations.setLimits(project, 0.05, 0.002)
        db.localizedCrs().insert(
            LocalizedCrsEntity("l1", "Plano de obra", "SPAIN|ETRS89_UTM30", "ORTHOMETRIC", "1;2;3;4;0;1;1;0;0;0;0;0;0", 5),
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
    fun `S039 RF-26 el paquete lleva el borrador, la activa y el historial con sus pares`() {
        import(writePackage()).shouldBeInstanceOf<ImportResult.Imported>()

        count(theirs, "localizations") shouldBe 3
        count(theirs, "localization_pairs") shouldBe 3
        val remote = RoomLocalizationRepository(other)
        val active = runBlocking { remote.active(project).first() }.shouldNotBeNull()
        active.fixScale shouldBe true
        active.params.shouldNotBeNull()
        runBlocking { remote.history(project).first() }.size shouldBe 1
        val draft = runBlocking { remote.draft(project).first() }.shouldNotBeNull()
        draft.residualLimitM shouldBe 0.05
        draft.pairs.single().scE.toRawBits() shouldBe 440_290.123_456_789.toRawBits()
        count(theirs, "localized_crs") shouldBe 0 // los SC guardados van en la copia completa, no en el paquete
    }

    @Test
    fun `S039 RF-19 como nuevo remapea la localizacion de cada punto y sus pares`() {
        val pkg = writePackage()
        import(pkg).shouldBeInstanceOf<ImportResult.Imported>()

        val second = import(pkg, ImportChoice.AS_NEW).shouldBeInstanceOf<ImportResult.Imported>()

        count(theirs, "localizations") shouldBe 6
        val locCols = columns(file(theirs), "localizations")
        val newIds = rows(file(theirs), "localizations")
            .filter { it[locCols.indexOf("project_id")] == second.projectId }
            .map { it[locCols.indexOf("id")] as String }
        newIds.size shouldBe 3
        val pointCols = columns(file(theirs), "points")
        val copied = rows(file(theirs), "points").first { it[pointCols.indexOf("project_id")] == second.projectId }
        (copied[pointCols.indexOf("localization_id")] in newIds) shouldBe true
        val pairCols = columns(file(theirs), "localization_pairs")
        rows(file(theirs), "localization_pairs").count { it[pairCols.indexOf("localization_id")] in newIds } shouldBe 3
    }

    @Test
    fun `S039 RF-26 la copia restaura las localizaciones y los SC guardados`() {
        val backup = ByteArrayOutputStream().also { runBlocking { repository.writeBackup(it) } shouldBe true }.toByteArray()
        runBlocking { localizations.remove(project) }
        runBlocking { localizations.discardDraft(project) }
        db.openHelper.writableDatabase.execSQL("DELETE FROM localized_crs")
        runBlocking { localizations.active(project).first() }.shouldBeNull()

        val valid = runBlocking { repository.read({ backup.inputStream() }, BackupKind.BACKUP) }.shouldBeInstanceOf<BackupRead.Valid>()
        runBlocking { repository.restore(valid) }.shouldBeInstanceOf<RestoreResult.Restored>()

        runBlocking { localizations.active(project).first() }.shouldNotBeNull()
        runBlocking { localizations.draft(project).first() }.shouldNotBeNull()
        count(mine, "localized_crs") shouldBe 1
    }
}
