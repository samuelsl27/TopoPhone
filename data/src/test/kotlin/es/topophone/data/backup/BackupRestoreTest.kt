package es.topophone.data.backup

import android.content.Context
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.core.formats.backup.BackupKind
import es.topophone.core.model.ntrip.NtripProfileDraft
import es.topophone.core.model.preferences.ThemeMode
import es.topophone.core.model.project.ProjectDefaults
import es.topophone.data.backup.BackupFixture.Companion.rows
import es.topophone.data.db.TopoPhoneDatabase
import es.topophone.data.ntrip.NtripSaveResult
import es.topophone.data.ntrip.PasswordResult
import es.topophone.data.ntrip.RoomNtripProfileRepository
import es.topophone.data.receiver.DataStoreReceiverPreferences
import es.topophone.data.receiver.SavedReceiver
import es.topophone.data.secret.AesGcmCipher
import es.topophone.data.settings.DataStoreSettingsRepository
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.io.File
import javax.crypto.KeyGenerator
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith

/** Restaurar una copia: copia previa, sustitución, contraseñas y vuelta atrás si algo falla (S013/RF-7 a RF-10, RF-15). */
@RunWith(AndroidJUnit4::class)
class BackupRestoreTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val name = "rest-${System.nanoTime()}.db"
    private val tables = listOf("project_folders", "projects", "map_viewports", "points", "point_history")
    private lateinit var db: TopoPhoneDatabase
    private lateinit var fixture: BackupFixture
    private lateinit var settings: TestSettings
    private lateinit var profiles: RoomNtripProfileRepository
    private lateinit var undoDir: File
    private lateinit var repository: BackupRepository
    private lateinit var redA: String
    private lateinit var redB: String

    /** Filas de cada tabla y tema en el momento de la copia. */
    private lateinit var atBackup: Map<String, List<List<Any?>>>
    private lateinit var backup: ByteArray

    @Before
    fun open() = runBlocking {
        db = TopoPhoneDatabase.build(context, name)
        fixture = BackupFixture(db).fill()
        settings = TestSettings(folder.newFolder("settings"), TestSettings.FakeLanguage("es"))
        val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        profiles = RoomNtripProfileRepository(db, AesGcmCipher { key })
        redA = (profiles.create(NtripProfileDraft("Red A", "a.example"), "secreto-a") as NtripSaveResult.Saved).id
        redB = (profiles.create(NtripProfileDraft("Red B", "b.example"), "secreto-b") as NtripSaveResult.Saved).id
        DataStoreSettingsRepository(settings.stores.getValue("settings")).selectTheme(ThemeMode.DARK)
        undoDir = File(folder.root, "undo")
        repository =
            BackupRepository(context, db, settings.archive, "0.13.0", clock = { 0 }, workDir = folder.newFolder("work"), undoDir = undoDir)

        backup = java.io.ByteArrayOutputStream().also { repository.writeBackup(it) shouldBe true }.toByteArray()
        atBackup = tables.associateWith { rows(source(), it) }

        // Lo que pasa después de la copia: un proyecto nuevo, otra contraseña, un perfil borrado y otro nuevo, otro tema.
        fixture.projects.create(ProjectDefaults().toDraft().copy(name = "Nuevo"))
        profiles.update(redA, NtripProfileDraft("Red A", "a.example"), "nueva-a")
        profiles.delete(redB)
        profiles.create(NtripProfileDraft("Red C", "c.example"), "secreto-c")
        DataStoreSettingsRepository(settings.stores.getValue("settings")).selectTheme(ThemeMode.SUN)
        DataStoreReceiverPreferences(settings.stores.getValue("receiver")).saveLastReceiver(SavedReceiver("00:11:22:33:44:55", "RS3"))
    }

    @After
    fun close() {
        settings.close()
        db.close()
        context.deleteDatabase(name)
    }

    private fun source() = context.getDatabasePath(name)

    private fun valid(bytes: ByteArray = backup) =
        runBlocking { repository.read({ bytes.inputStream() }, BackupKind.BACKUP) }.shouldBeInstanceOf<BackupRead.Valid>()

    private fun theme() = runBlocking { settings.stores.getValue("settings").data.first()[stringPreferencesKey("theme_mode")] }

    private fun profileNames() = runBlocking { profiles.profiles().first().map { it.draft.name }.toSet() }

    @Test
    fun `S013 RF-8 los datos y ajustes pasan a ser los de la copia`() {
        val result = runBlocking { repository.restore(valid()) }

        result shouldBe RestoreResult.Restored(projects = 2, points = 4)
        for (table in tables) rows(source(), table) shouldBe atBackup.getValue(table)
        profileNames() shouldBe setOf("IGN", "Libre", "Red A", "Red B")
        theme() shouldBe "DARK"
        settings.language.tag shouldBe "es"
    }

    @Test
    fun `S013 RF-8 los ajustes propios del movil no cambian`() {
        runBlocking { repository.restore(valid()) }

        runBlocking { settings.stores.getValue("receiver").data.first()[stringPreferencesKey("last_receiver_address")] } shouldBe
            "00:11:22:33:44:55"
    }

    @Test
    fun `S013 RF-9 un perfil que sigue en el movil conserva su contrasena`() {
        runBlocking { repository.restore(valid()) }

        runBlocking { profiles.password(redA) } shouldBe PasswordResult.Ok("nueva-a")
        runBlocking { profiles.password("ntrip-libre") } shouldBe PasswordResult.None
    }

    @Test
    fun `S013 RF-15 un perfil que solo estaba en la copia pide su contrasena`() {
        runBlocking { repository.restore(valid()) }

        runBlocking { profiles.password(redB) } shouldBe PasswordResult.Undecryptable
        runBlocking { profiles.profile(redB) }!!.hasPassword shouldBe true
    }

    @Test
    fun `S013 RF-7 antes de restaurar se guarda el estado actual`() {
        val kept = mutableListOf<ByteArray>()

        runBlocking { repository.restore(valid()) { kept += it.readBytes() } }

        val before = File(undoDir, BackupRepository.BEFORE_RESTORE)
        before.exists() shouldBe true
        kept.single() shouldBe before.readBytes()
        val previous = valid(before.readBytes())
        previous.manifest.projectCount shouldBe 3 // Calle Mayor 12, Puerto y Nuevo.
    }

    @Test
    fun `S013 RF-7 si falla la copia en la carpeta, se restaura igual`() {
        val result = runBlocking { repository.restore(valid()) { error("tarjeta retirada") } }

        result.shouldBeInstanceOf<RestoreResult.Restored>()
    }

    @Test
    fun `S013 RF-10 un fallo en la base de datos no cambia nada`() {
        val before = tables.associateWith { rows(source(), it) }
        repository.hooks = RestoreHooks(beforeCommit = { error("sin espacio") })

        runBlocking { repository.restore(valid()) } shouldBe RestoreResult.Failed

        for (table in tables) rows(source(), table) shouldBe before.getValue(table)
        profileNames() shouldBe setOf("IGN", "Libre", "Red A", "Red C")
        runBlocking { profiles.password(redA) } shouldBe PasswordResult.Ok("nueva-a")
        theme() shouldBe "SUN"
    }

    @Test
    fun `S013 RF-10 un fallo en los ajustes vuelve todo atras`() {
        val before = tables.associateWith { rows(source(), it) }
        repository.hooks = RestoreHooks(afterDatabase = { error("ajustes ilegibles") })

        runBlocking { repository.restore(valid()) } shouldBe RestoreResult.Failed

        for (table in tables) rows(source(), table) shouldBe before.getValue(table)
        profileNames() shouldBe setOf("IGN", "Libre", "Red A", "Red C")
        runBlocking { profiles.password(redA) } shouldBe PasswordResult.Ok("nueva-a")
        theme() shouldBe "SUN"
    }

    @Test
    fun `S013 RF-10 sin copia previa no se restaura`() {
        undoDir.writeText("no es una carpeta")
        val before = tables.associateWith { rows(source(), it) }

        runBlocking { repository.restore(valid()) } shouldBe RestoreResult.Failed

        for (table in tables) rows(source(), table) shouldBe before.getValue(table)
    }

    @Test
    fun `S013 RF-8 un paquete no se restaura como copia`() {
        val read = valid()
        val pkg = BackupRead.Valid(read.manifest.copy(kind = BackupKind.PACKAGE, projectId = "p"), read.database, settings = null)

        runBlocking { repository.restore(pkg) } shouldBe RestoreResult.Failed
        profileNames() shouldBe setOf("IGN", "Libre", "Red A", "Red C")
    }

    @Test
    fun `S013 RF-11 deshacer devuelve datos y contrasenas de antes`() {
        val before = tables.associateWith { rows(source(), it) }
        runBlocking { repository.restore(valid()) }

        runBlocking { repository.undoRestore() } shouldBe RestoreResult.Restored(projects = 3, points = 4)

        for (table in tables) rows(source(), table) shouldBe before.getValue(table)
        profileNames() shouldBe setOf("IGN", "Libre", "Red A", "Red C")
        val redC = runBlocking { profiles.profiles().first() }.single { it.draft.name == "Red C" }.id
        runBlocking { profiles.password(redC) } shouldBe PasswordResult.Ok("secreto-c")
        runBlocking { profiles.password(redA) } shouldBe PasswordResult.Ok("nueva-a")
        theme() shouldBe "SUN"
    }

    @Test
    fun `S013 RF-11 deshacer aparece al restaurar y se va al usarlo`() {
        runBlocking { repository.undoAvailable.first() } shouldBe false

        runBlocking { repository.restore(valid()) }
        runBlocking { repository.undoAvailable.first() } shouldBe true

        runBlocking { repository.undoRestore() }.shouldBeInstanceOf<RestoreResult.Restored>()
        runBlocking { repository.undoAvailable.first() } shouldBe false
        runBlocking { repository.undoRestore() } shouldBe RestoreResult.Failed
    }

    @Test
    fun `S013 RF-11 deshacer sigue disponible al reabrir la app`() {
        runBlocking { repository.restore(valid()) }

        val reopened = BackupRepository(context, db, settings.archive, "0.13.0", workDir = folder.newFolder("w2"), undoDir = undoDir)

        runBlocking { reopened.undoAvailable.first() } shouldBe true
    }

    @Test
    fun `S013 RF-11 una restauracion fallida no pierde el deshacer anterior`() {
        runBlocking { repository.restore(valid()) }
        val first = File(undoDir, BackupRepository.BEFORE_RESTORE).readBytes()
        repository.hooks = RestoreHooks(beforeCommit = { error("sin espacio") })

        runBlocking { repository.restore(valid()) } shouldBe RestoreResult.Failed

        File(undoDir, BackupRepository.BEFORE_RESTORE).readBytes() shouldBe first
        runBlocking { repository.undoAvailable.first() } shouldBe true
    }

    @Test
    fun `S013 RF-11 un fallo al deshacer no cambia nada`() {
        runBlocking { repository.restore(valid()) }
        val restored = tables.associateWith { rows(source(), it) }
        repository.hooks = RestoreHooks(afterDatabase = { error("ajustes ilegibles") })

        runBlocking { repository.undoRestore() } shouldBe RestoreResult.Failed

        for (table in tables) rows(source(), table) shouldBe restored.getValue(table)
        theme() shouldBe "DARK"
        runBlocking { profiles.password(redA) } shouldBe PasswordResult.Ok("nueva-a")
        runBlocking { repository.undoAvailable.first() } shouldBe true
    }
}
