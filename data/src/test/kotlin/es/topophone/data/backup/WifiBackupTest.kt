package es.topophone.data.backup

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.core.formats.backup.BackupArchive
import es.topophone.core.formats.backup.BackupKind
import es.topophone.core.formats.backup.BackupManifest
import es.topophone.data.backup.BackupFixture.Companion.rows
import es.topophone.data.db.ExportedSchemas
import es.topophone.data.db.TopoPhoneDatabase
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith

/** Los receptores Wi-Fi van en la copia completa y no en el paquete de un proyecto (S021/RF-4, plan D2). */
@RunWith(AndroidJUnit4::class)
class WifiBackupTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val name = "wifi-backup-${System.nanoTime()}.db"
    private lateinit var db: TopoPhoneDatabase
    private lateinit var settings: TestSettings
    private lateinit var repository: BackupRepository
    private lateinit var fixture: BackupFixture

    @Before
    fun open() = runBlocking {
        db = TopoPhoneDatabase.build(context, name)
        fixture = BackupFixture(db).fill()
        settings = TestSettings(folder.newFolder("settings"))
        repository =
            BackupRepository(context, db, settings.archive, "0.38.0", workDir = folder.newFolder("w"), undoDir = folder.newFolder("u"))
        val sql = db.openHelper.writableDatabase
        sql.execSQL("INSERT INTO wifi_receivers VALUES ('w1', 'RS3 hotspot', '192.168.42.1', 9001, NULL, 1000)")
        sql.execSQL("INSERT INTO wifi_receivers VALUES ('w2', 'RS3 casa', 'reach.local', 9001, 9002, 2000)")
    }

    @After
    fun close() {
        settings.close()
        db.close()
        context.deleteDatabase(name)
    }

    private fun source() = context.getDatabasePath(name)

    private fun snapshot(scope: SnapshotScope): File {
        val target = File(folder.root, "snapshot-${System.nanoTime()}.db")
        DatabaseSnapshot(context, source()).copy(target, scope)
        return target
    }

    private fun backup(): ByteArray = ByteArrayOutputStream().also {
        runBlocking { repository.writeBackup(it) } shouldBe true
    }.toByteArray()

    private fun restore(bytes: ByteArray) {
        val valid = runBlocking { repository.read({ bytes.inputStream() }, BackupKind.BACKUP) }.shouldBeInstanceOf<BackupRead.Valid>()
        runBlocking { repository.restore(valid) }.shouldBeInstanceOf<RestoreResult.Restored>()
    }

    private fun receivers() = rows(source(), "wifi_receivers")

    @Test
    fun `S021 RNF-T6 la copia completa lleva los receptores Wi-Fi tal cual`() {
        val target = snapshot(SnapshotScope.All)

        rows(target, "wifi_receivers").size shouldBe 2
        rows(target, "wifi_receivers") shouldBe receivers()
    }

    @Test
    fun `S021 RNF-T6 el paquete de un proyecto no lleva receptores Wi-Fi`() {
        rows(snapshot(SnapshotScope.Project(fixture.streetId)), "wifi_receivers") shouldBe emptyList()
    }

    @Test
    fun `S021 RNF-T6 restaurar una copia devuelve los receptores con su identidad y sus puertos`() {
        val before = receivers()
        val bytes = backup()
        db.openHelper.writableDatabase.execSQL("DELETE FROM wifi_receivers")
        receivers() shouldBe emptyList()

        restore(bytes)

        receivers() shouldBe before
    }

    @Test
    fun `S021 RNF-T6 la copia de una version anterior sin receptores Wi-Fi se restaura sin ellos`() {
        val old = File(folder.root, "v33.db")
        ExportedSchemas.create(old, 33)
        val manifest = BackupManifest(BackupKind.BACKUP, 33, "0.38.0", Instant.EPOCH, 0, 0)
        val settingsFile = File(folder.root, "settings-33.json").apply { writeText("""{"stores":{},"appLanguage":""}""") }
        val files = mapOf(BackupArchive.DATABASE to old, BackupArchive.SETTINGS to settingsFile)
        val bytes = ByteArrayOutputStream().also { BackupArchive.write(manifest, files, it) }.toByteArray()

        restore(bytes)

        receivers() shouldBe emptyList()
    }
}
