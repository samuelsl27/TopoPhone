package es.topophone.app.receiver

import es.topophone.core.model.point.TiltMode
import es.topophone.data.receiver.ReceiverTiltSettings
import es.topophone.data.receiver.TiltPreferences
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Test

/** El receptor en uso y su modo de inclinación (S016/RF-1, RF-3, RF-10, RF-28; plan A8, A9). */
class CurrentTiltTest {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val replaying = MutableStateFlow(false)
    private val address = MutableStateFlow<String?>(null)
    private val preferences = MemoryTiltPreferences()
    private val tilt = CurrentTilt(replaying, address, preferences, scope)
    private val rs3 = "00:11:22:33:44:55"

    @After
    fun tearDown() = scope.cancel()

    private fun await(predicate: (CurrentTiltState) -> Boolean) = runBlocking { withTimeout(5_000) { tilt.state.first(predicate) } }

    @Test
    fun `S016 RF-1 sin receptor no hay clave ni modo`() {
        await { true } shouldBe CurrentTiltState()
    }

    @Test
    fun `S016 RF-1 el modo es el del receptor conectado`() {
        preferences.values.value = mapOf(rs3 to ReceiverTiltSettings(TiltMode.APP, verified = true, detected = TiltMode.APP))

        address.value = rs3

        await { it.key == rs3 }.settings shouldBe ReceiverTiltSettings(TiltMode.APP, verified = true, detected = TiltMode.APP)
    }

    @Test
    fun `S016 RF-1 una reproduccion usa el modo comun de las reproducciones`() {
        address.value = rs3
        preferences.values.value = mapOf(TiltPreferences.REPLAY to ReceiverTiltSettings(TiltMode.VERTICAL))

        replaying.value = true

        await { it.key == TiltPreferences.REPLAY }.settings.mode shouldBe TiltMode.VERTICAL
    }

    @Test
    fun `S016 RF-3 elegido a mano queda sin comprobar y sube la revision`() {
        address.value = rs3
        await { it.key == rs3 }
        runBlocking { tilt.confirm(TiltMode.RECEIVER) }
        await { it.settings.mode == TiltMode.RECEIVER }.settings.verified shouldBe true

        runBlocking { tilt.choose(TiltMode.VERTICAL) }

        val state = await { it.settings.mode == TiltMode.VERTICAL }
        state.settings shouldBe ReceiverTiltSettings(TiltMode.VERTICAL, verified = false, detected = TiltMode.RECEIVER)
        state.revision shouldBe 2
        preferences.values.value.getValue(rs3) shouldBe state.settings
    }

    @Test
    fun `S016 RF-28 abrir el asistente sube la revision y cerrarlo no`() {
        val before = await { true }.revision

        tilt.openWizard()
        await { it.wizardOpen }.revision shouldBe before + 1
        tilt.closeWizard()

        val closed = await { !it.wizardOpen }
        closed.revision shouldBe before + 1
    }

    @Test
    fun `S016 RF-1 sin receptor no se guarda ningun modo`() {
        runBlocking { tilt.choose(TiltMode.APP) }

        preferences.values.value shouldBe emptyMap()
    }
}
