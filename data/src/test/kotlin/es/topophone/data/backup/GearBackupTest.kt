package es.topophone.data.backup

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.core.formats.backup.BackupKind
import es.topophone.core.model.point.PoleKind
import es.topophone.data.backup.BackupFixture.Companion.rows
import es.topophone.data.db.TopoPhoneDatabase
import es.topophone.data.gear.GearResult
import es.topophone.data.gear.RoomGearRepository
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

/** Los perfiles, los jalones y el equipo de cada receptor viajan en la copia completa y no en los paquetes (S046/RF-30). */
@RunWith(AndroidJUnit4::class)
class GearBackupTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val mine = "gear-a-${System.nanoTime()}.db"
    private val theirs = "gear-b-${System.nanoTime()}.db"
    private lateinit var db: TopoPhoneDatabase
    private lateinit var other: TopoPhoneDatabase
    private lateinit var fixture: BackupFixture
    private lateinit var settings: TestSettings
    private lateinit var repository: BackupRepository
    private lateinit var otherRepository: BackupRepository
    private lateinit var gear: RoomGearRepository
    private lateinit var profileId: String
    private lateinit var poleId: String

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
        gear = RoomGearRepository(db)
        profileId = (gear.saveProfile(null, "Mi antena", "0.12", "0.06") as GearResult.Saved).id
        poleId = (gear.savePole(null, "Jalón estándar", PoleKind.VERTICAL, "1.8") as GearResult.Saved).id
        gear.savePole(null, "Inclinado", PoleKind.SLANT, "1.9500000000001")
        gear.setProfile("AA:BB:CC", "own:$profileId")
        gear.setActivePole("AA:BB:CC", poleId)
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

    @Test
    fun `S046 RF-30 la copia completa restaura perfiles, jalones y activos sin redondear`() {
        val backup = ByteArrayOutputStream().also { runBlocking { repository.writeBackup(it) } shouldBe true }.toByteArray()
        runBlocking {
            gear.setProfile("AA:BB:CC", "catalog:RX")
            gear.deleteProfile(profileId)
            gear.savePole(poleId, "Cambiado", PoleKind.VERTICAL, "2")
        }

        val valid = runBlocking { repository.read({ backup.inputStream() }, BackupKind.BACKUP) }.shouldBeInstanceOf<BackupRead.Valid>()
        runBlocking { repository.restore(valid) }.shouldBeInstanceOf<RestoreResult.Restored>()

        val restored = runBlocking { gear.gear("AA:BB:CC").first() }
        restored.profile.name shouldBe "Mi antena"
        restored.profile.radiusM shouldBe 0.06
        restored.pole!!.name shouldBe "Jalón estándar"
        runBlocking { gear.poles().first() }.map { it.name to it.enteredM } shouldBe
            listOf("Jalón estándar" to 1.8, "Inclinado" to 1.9500000000001)
    }

    @Test
    fun `S046 RF-30 el paquete de un proyecto no lleva el equipo`() {
        val project = fixture.streetId
        val pkg = ByteArrayOutputStream().also { runBlocking { repository.writePackage(project, it) } shouldBe true }.toByteArray()

        val valid = runBlocking { otherRepository.read({ pkg.inputStream() }, BackupKind.PACKAGE) }.shouldBeInstanceOf<BackupRead.Valid>()
        runBlocking { otherRepository.importPackage(valid, null, importedLabel = "importado") }.shouldBeInstanceOf<ImportResult.Imported>()

        count(theirs, "antenna_profiles") shouldBe 0
        count(theirs, "saved_poles") shouldBe 0
        count(theirs, "receiver_gear") shouldBe 0
        count(mine, "antenna_profiles") shouldBe 1
        count(mine, "saved_poles") shouldBe 2
    }
}
