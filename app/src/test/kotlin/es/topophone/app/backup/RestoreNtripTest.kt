package es.topophone.app.backup

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.app.receiver.CorrectionsController
import es.topophone.app.ui.receiver.FakePreferences
import es.topophone.app.ui.receiver.nmea
import es.topophone.core.formats.backup.BackupKind
import es.topophone.core.model.ntrip.NtripProfileDraft
import es.topophone.core.ntrip.NtripConfig
import es.topophone.core.ntrip.NtripConnector
import es.topophone.data.backup.AppLanguageStore
import es.topophone.data.backup.BackupRead
import es.topophone.data.backup.BackupRepository
import es.topophone.data.backup.RestoreResult
import es.topophone.data.backup.SettingsArchive
import es.topophone.data.db.TopoPhoneDatabase
import es.topophone.data.ntrip.NtripSaveResult
import es.topophone.data.ntrip.RoomNtripProfileRepository
import es.topophone.data.secret.AesGcmCipher
import es.topophone.data.settings.SettingsDataStore
import es.topophone.receiver.session.ConnectionState
import es.topophone.receiver.session.NtripState
import es.topophone.receiver.session.ReceiverSession
import es.topophone.receiver.session.ReceiverSnapshot
import es.topophone.receiver.transport.StreamTransport
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.CopyOnWriteArrayList
import javax.crypto.KeyGenerator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith

/**
 * Restaurar con la conexión NTRIP abierta (S013/RF-8 con S007/RF-8, RF-9): si el perfil en uso desaparece se cierra, y si
 * vuelve con otros datos se conecta de nuevo, con su contraseña conservada (S013/RF-9). Base de datos, cifrado y
 * restauración reales.
 */
@RunWith(AndroidJUnit4::class)
class RestoreNtripTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val name = "rntrip-${System.nanoTime()}.db"
    private val gga = nmea("GNGGA,101530.20,4025.0080000,N,00342.2228000,W,4,24,0.6,650.123,M,51.234,M,1.0,0001")
    private val storesScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val sessionScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val controllerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val configs = CopyOnWriteArrayList<NtripConfig>()
    private val connector = NtripConnector { config, _, onConnected, _ ->
        configs += config
        onConnected()
        awaitCancellation()
    }
    private val session = ReceiverSession(sessionScope, connector, ntripBackoffMs = listOf(60_000L))
    private val ign = NtripProfileDraft("ERGNSS (IGN): red", "ergnss-tr.ign.es", 2101, false, "sgarcia", "VRS3M", mountpointNeedsGga = true)
    private lateinit var db: TopoPhoneDatabase
    private lateinit var profiles: RoomNtripProfileRepository
    private lateinit var repository: BackupRepository
    private lateinit var controller: CorrectionsController

    @Before
    fun open() {
        db = TopoPhoneDatabase.build(context, name)
        val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        profiles = RoomNtripProfileRepository(db, AesGcmCipher { key })
        val stores = SettingsArchive.PORTABLE.keys.associateWith { store ->
            SettingsDataStore.create(storesScope) { File(folder.root, "$store.preferences_pb") }
        }
        val language = object : AppLanguageStore {
            override fun current() = ""

            override suspend fun apply(tag: String) = Unit
        }
        repository = BackupRepository(
            context,
            db,
            SettingsArchive(stores, language),
            "0.13.0",
            workDir = folder.newFolder("work"),
            undoDir = folder.newFolder("undo"),
        )
        controller = CorrectionsController(session, profiles, FakePreferences(), controllerScope)
    }

    @After
    fun close() {
        controllerScope.cancel()
        sessionScope.cancel()
        storesScope.cancel()
        db.close()
        context.deleteDatabase(name)
    }

    private fun connectReceiver() {
        val toApp = PipedOutputStream()
        session.connect(StreamTransport("reach-RS3", PipedInputStream(toApp, 64 * 1024), ByteArrayOutputStream()))
        toApp.write(gga.toByteArray())
        toApp.flush()
        await { it.connection is ConnectionState.Receiving }
    }

    private fun await(predicate: (ReceiverSnapshot) -> Boolean): ReceiverSnapshot =
        runBlocking { withTimeout(5_000) { session.state.first(predicate) } }

    private fun backup(): ByteArray = ByteArrayOutputStream().also {
        runBlocking { repository.writeBackup(it) } shouldBe true
    }.toByteArray()

    private fun restore(bytes: ByteArray) = runBlocking {
        val valid = repository.read({ bytes.inputStream() }, BackupKind.BACKUP).shouldBeInstanceOf<BackupRead.Valid>()
        repository.restore(valid).shouldBeInstanceOf<RestoreResult.Restored>()
    }

    @Test
    fun `S013 RF-8 restaurar sin el perfil en uso cierra la conexion`() = runBlocking<Unit> {
        (profiles.create(ign, "s3cr3t") as NtripSaveResult.Saved).id
        val copy = backup()
        val other = (profiles.create(ign.copy(name = "Nueva red", mountpoint = "MAC3M"), "otra") as NtripSaveResult.Saved).id
        connectReceiver()
        controller.connect(other)
        await { it.ntrip is NtripState.Streaming }

        restore(copy)

        await { it.ntrip == NtripState.Idle }
        controller.status.value.activeProfileId shouldBe null
    }

    @Test
    fun `S013 RF-9 restaurar el perfil en uso con otros datos reconecta`() = runBlocking<Unit> {
        val id = (profiles.create(ign, "s3cr3t") as NtripSaveResult.Saved).id
        val copy = backup()
        delay(5) // Que la fecha de modificación cambie.
        profiles.update(id, ign.copy(mountpoint = "CERCANA3M"), password = null)
        connectReceiver()
        controller.connect(id)
        await { it.ntrip is NtripState.Streaming }
        configs.single().mountpoint shouldBe "CERCANA3M"

        restore(copy)

        withTimeout(5_000) { while (configs.size < 2) delay(20) }
        configs.last().mountpoint shouldBe "VRS3M"
        configs.last().password shouldBe "s3cr3t"
        controller.status.value.activeProfileId shouldBe id
    }
}
