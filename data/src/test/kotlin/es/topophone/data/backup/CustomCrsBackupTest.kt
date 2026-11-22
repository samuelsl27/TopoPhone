package es.topophone.data.backup

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.core.formats.backup.BackupKind
import es.topophone.core.model.project.CUSTOM_EPSG
import es.topophone.core.model.project.CorrectionsDatum
import es.topophone.core.model.project.CrsChangeKind
import es.topophone.core.model.project.CrsDefinition
import es.topophone.core.model.project.CustomHeight
import es.topophone.core.model.project.HeightSystem
import es.topophone.core.model.project.LibraryCrs
import es.topophone.core.model.project.SpainCrs
import es.topophone.data.backup.BackupFixture.Companion.rows
import es.topophone.data.crs.CustomCrsSave
import es.topophone.data.crs.RoomCustomCrsRepository
import es.topophone.data.crs.RoomProjectCrsHistoryRepository
import es.topophone.data.db.TopoPhoneDatabase
import es.topophone.data.project.RoomProjectRepository
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

/** Mis SC, el historial de cambios de SC y la ondulación constante viajan en la copia completa y en el paquete de un proyecto (S035/RF-16, RF-22). */
@RunWith(AndroidJUnit4::class)
class CustomCrsBackupTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val mine = "custom-crs-mine-${System.nanoTime()}.db"
    private val theirs = "custom-crs-theirs-${System.nanoTime()}.db"
    private lateinit var db: TopoPhoneDatabase
    private lateinit var other: TopoPhoneDatabase
    private lateinit var settings: TestSettings
    private lateinit var repository: BackupRepository
    private lateinit var otherRepository: BackupRepository
    private lateinit var fixture: BackupFixture
    private lateinit var customCrs: RoomCustomCrsRepository
    private lateinit var history: RoomProjectCrsHistoryRepository

    private fun definition(name: String) = CrsDefinition(
        epsg = CUSTOM_EPSG,
        name = name,
        projected = true,
        datumCode = 0,
        datumName = "Personalizado",
        ellipsoidSemiMajorM = 6378137.0,
        ellipsoidInverseFlattening = 298.257222101,
        primeMeridianDeg = 0.0,
        method = "tm",
        parameters = mapOf(
            "latitudeOfOriginDeg" to 0.0,
            "longitudeOfOriginDeg" to -3.123456789012345,
            "scaleFactor" to 0.9996,
            "falseEasting" to 500000.0,
            "falseNorthing" to 0.0,
        ),
        unitMetres = 1.0,
        area = null,
        frame = "ETRS89",
        transformationName = "No datum shift",
        transformationAccuracyM = null,
        transformationArea = null,
        transformationSteps = "null",
    )

    @Before
    fun open() = runBlocking {
        db = TopoPhoneDatabase.build(context, mine)
        other = TopoPhoneDatabase.build(context, theirs)
        fixture = BackupFixture(db).fill()
        settings = TestSettings(folder.newFolder("settings"))
        repository =
            BackupRepository(context, db, settings.archive, "0.39.0", workDir = folder.newFolder("wa"), undoDir = folder.newFolder("ua"))
        otherRepository =
            BackupRepository(context, other, settings.archive, "0.39.0", workDir = folder.newFolder("wb"), undoDir = folder.newFolder("ub"))
        customCrs = RoomCustomCrsRepository(db)
        history = RoomProjectCrsHistoryRepository(db)
        // Un SC de Mis SC con ondulación constante, usado por el proyecto de la fixture; luego el proyecto pasa a otro SC del mismo datum.
        val saved = (customCrs.create(definition("Obra norte"), CustomHeight.ConstantGeoid(48.123456789012345)) as CustomCrsSave.Saved).crs
        RoomProjectRepository(db).let { projects ->
            val current = projects.project(fixture.streetId)!!
            projects.update(fixture.streetId, current.toDraft().copy(crs = saved.toProjectCrs(CorrectionsDatum.ETRS89)))
        }
        history.apply(
            fixture.streetId,
            SpainCrs.ETRS89_UTM30,
            HeightSystem.ELLIPSOIDAL,
        ).shouldBeInstanceOf<es.topophone.data.crs.CrsChangeResult.Applied>()
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

    private fun backup(): ByteArray = ByteArrayOutputStream().also {
        runBlocking { repository.writeBackup(it) } shouldBe true
    }.toByteArray()

    @Test
    fun `S035 RF-16 la copia lleva Mis SC`() = runBlocking<Unit> {
        val before = customCrs.all()
        before.size shouldBe 1
        val bytes = backup()
        customCrs.delete(before.single().id)
        customCrs.all() shouldBe emptyList()

        val valid = repository.read({ bytes.inputStream() }, BackupKind.BACKUP).shouldBeInstanceOf<BackupRead.Valid>()
        repository.restore(valid).shouldBeInstanceOf<RestoreResult.Restored>()

        customCrs.all() shouldBe before
        customCrs.all().single().height shouldBe CustomHeight.ConstantGeoid(48.123456789012345)
        customCrs.all().single().definition.parameters["longitudeOfOriginDeg"] shouldBe -3.123456789012345
    }

    @Test
    fun `S035 RF-22 la copia lleva el historial`() = runBlocking<Unit> {
        val before = history.observe(fixture.streetId).first()
        before.size shouldBe 1
        val bytes = backup()
        db.openHelper.writableDatabase.execSQL("DELETE FROM project_crs_history")

        val valid = repository.read({ bytes.inputStream() }, BackupKind.BACKUP).shouldBeInstanceOf<BackupRead.Valid>()
        repository.restore(valid).shouldBeInstanceOf<RestoreResult.Restored>()

        history.observe(fixture.streetId).first() shouldBe before
    }

    @Test
    fun `S035 RF-22 el paquete lleva el historial`() = runBlocking<Unit> {
        val pkg = ByteArrayOutputStream().also { repository.writePackage(fixture.streetId, it) shouldBe true }.toByteArray()
        val valid = otherRepository.read({ pkg.inputStream() }, BackupKind.PACKAGE).shouldBeInstanceOf<BackupRead.Valid>()

        otherRepository.importPackage(valid, null, importedLabel = "importado").shouldBeInstanceOf<ImportResult.Imported>()

        val changes = RoomProjectCrsHistoryRepository(other).observe(fixture.streetId).first()
        changes.size shouldBe 1
        changes.single().kind shouldBe CrsChangeKind.CHANGE
        changes.single().from.shouldBeInstanceOf<LibraryCrs>().definition.name shouldBe "Obra norte"
        changes.single().from.shouldBeInstanceOf<LibraryCrs>().geoidConstantM shouldBe 48.123456789012345
        rows(file(theirs), "custom_crs") shouldBe emptyList()
    }

    @Test
    fun `S035 RF-22 como nuevo trae filas nuevas`() = runBlocking<Unit> {
        val pkg = ByteArrayOutputStream().also { repository.writePackage(fixture.streetId, it) shouldBe true }.toByteArray()
        val valid = repository.read({ pkg.inputStream() }, BackupKind.PACKAGE).shouldBeInstanceOf<BackupRead.Valid>()

        val result = repository.importPackage(
            valid,
            ImportChoice.AS_NEW,
            importedLabel = "importado",
        ).shouldBeInstanceOf<ImportResult.Imported>()

        val copy = history.observe(result.projectId).first()
        copy.size shouldBe 1
        (history.observe(fixture.streetId).first().single().id != copy.single().id) shouldBe true
    }
}
