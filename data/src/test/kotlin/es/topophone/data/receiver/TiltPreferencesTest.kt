package es.topophone.data.receiver

import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import es.topophone.core.model.point.TiltMode
import es.topophone.data.settings.SettingsDataStore
import io.kotest.matchers.shouldBe
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** El modo de inclinación de cada receptor (S016/RF-1, RF-3, RF-4, RF-10). */
class TiltPreferencesTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val file: File by lazy { File(folder.root, "receiver.preferences_pb") }
    private val rs3 = "00:11:22:33:44:55"
    private val other = "66:77:88:99:AA:BB"

    private fun <T> withPreferences(block: suspend (DataStoreTiltPreferences) -> T): T = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            block(DataStoreTiltPreferences(SettingsDataStore.create(scope) { file }))
        } finally {
            scope.coroutineContext.job.cancelAndJoin()
        }
    }

    @Test
    fun `S016 RF-2 un receptor nuevo no tiene modo`() {
        withPreferences { it.settings(rs3).first() } shouldBe ReceiverTiltSettings(mode = null, verified = false, detected = null)
    }

    @Test
    fun `S016 RF-1 cada receptor recuerda su modo al volver a abrir la app`() {
        withPreferences {
            it.save(rs3, ReceiverTiltSettings(TiltMode.APP, verified = true, detected = TiltMode.APP))
            it.save(other, ReceiverTiltSettings(TiltMode.RECEIVER, verified = false))
        }

        withPreferences { it.settings(rs3).first() } shouldBe ReceiverTiltSettings(TiltMode.APP, verified = true, detected = TiltMode.APP)
        withPreferences { it.settings(other).first() } shouldBe ReceiverTiltSettings(TiltMode.RECEIVER, verified = false)
    }

    @Test
    fun `S016 RF-1 las reproducciones tienen su propio modo`() {
        withPreferences { it.save(TiltPreferences.REPLAY, ReceiverTiltSettings(TiltMode.VERTICAL)) }

        withPreferences { it.settings(TiltPreferences.REPLAY).first() }.mode shouldBe TiltMode.VERTICAL
        withPreferences { it.settings(rs3).first() }.mode shouldBe null
    }

    @Test
    fun `S016 RF-3 y RF-4 elegido a mano queda sin comprobar y recuerda lo que encontro el asistente`() {
        withPreferences { it.save(rs3, ReceiverTiltSettings(TiltMode.VERTICAL, verified = false, detected = TiltMode.RECEIVER)) }

        val settings = withPreferences { it.settings(rs3).first() }

        settings.verified shouldBe false
        settings.detected shouldBe TiltMode.RECEIVER
    }

    @Test
    fun `S016 RF-2 un valor que no se entiende es un modo sin elegir`() {
        runBlocking {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            SettingsDataStore.create(scope) { file }.edit { it[stringPreferencesKey("tilt_$rs3")] = "OTRO;1;" }
            scope.coroutineContext.job.cancelAndJoin()
        }

        withPreferences { it.settings(rs3).first() } shouldBe ReceiverTiltSettings.NONE
    }
}
