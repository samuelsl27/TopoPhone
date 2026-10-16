package es.topophone.data.ntrip

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.core.model.ntrip.NtripProfileDraft
import es.topophone.data.db.TopoPhoneDatabase
import es.topophone.data.receiver.DataStoreReceiverPreferences
import es.topophone.data.receiver.NtripPreferences
import es.topophone.data.secret.AesGcmCipher
import es.topophone.data.settings.SettingsDataStore
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import java.io.File
import javax.crypto.KeyGenerator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith

/** Al actualizar desde S002, los datos de caster guardados pasan a ser un perfil (S007/RF-10). */
@RunWith(AndroidJUnit4::class)
class NtripLegacyImportTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val db = Room.inMemoryDatabaseBuilder(context, TopoPhoneDatabase::class.java).build()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val preferences by lazy {
        DataStoreReceiverPreferences(SettingsDataStore.create(scope) { File(folder.root, "receiver.preferences_pb") })
    }
    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private val repository = RoomNtripProfileRepository(db, AesGcmCipher { key })
    private val import by lazy { NtripLegacyImport(preferences, repository) }

    @After
    fun close() {
        scope.cancel()
        db.close()
    }

    @Test
    fun `S007 RF-10 los datos de caster de S002 se convierten en un perfil con el servidor como nombre`() = runBlocking<Unit> {
        preferences.saveNtrip(NtripPreferences(host = "ergnss-tr.ign.es", port = 2102, mountpoint = "VRS3M", username = "sgarcia"))

        import.run()

        val profile = repository.profiles().first().single()
        profile.draft shouldBe NtripProfileDraft(
            name = "ergnss-tr.ign.es",
            host = "ergnss-tr.ign.es",
            port = 2102,
            username = "sgarcia",
            mountpoint = "VRS3M",
            mountpointNeedsGga = null, // escrito a mano: no se sabe
        )
        profile.hasPassword shouldBe false // S002 nunca la guardó
    }

    @Test
    fun `S007 RF-10 la importacion se hace una sola vez y despues los datos antiguos ya no estan`() = runBlocking<Unit> {
        preferences.saveNtrip(NtripPreferences(host = "ergnss-tr.ign.es", port = 2101, mountpoint = "VRS3M", username = "u"))

        import.run()
        import.run()

        repository.profiles().first().size shouldBe 1
        preferences.ntrip.first() shouldBe NtripPreferences()
    }

    @Test
    fun `S007 RF-10 sin datos de caster de S002 no se crea ningun perfil`() = runBlocking<Unit> {
        import.run()

        repository.profiles().first().shouldBeEmpty()
    }
}
