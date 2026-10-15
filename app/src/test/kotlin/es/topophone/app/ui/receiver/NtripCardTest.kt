package es.topophone.app.ui.receiver

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.app.R
import es.topophone.app.ui.text
import es.topophone.core.designsystem.theme.TopoPhoneTheme
import es.topophone.core.model.preferences.EffectiveTheme
import es.topophone.core.model.preferences.ThemeState
import es.topophone.core.ntrip.NtripFailure
import es.topophone.receiver.session.ConnectionState
import es.topophone.receiver.session.NtripState
import es.topophone.receiver.session.ReceiverSnapshot
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Tarjeta NTRIP de la pantalla Receptor (S002/RF-18 a RF-28). Interfaz en español (es-ES). */
@RunWith(AndroidJUnit4::class)
class NtripCardTest {

    @get:Rule
    val compose = createComposeRule()

    private val receiving = ConnectionState.Receiving("reach-RS3")

    private fun show(
        ntrip: NtripState = NtripState.Idle,
        form: NtripForm = NtripForm(),
        formInvalid: Boolean = false,
        internet: Boolean = true,
        nowMs: Long = 0,
        actions: ReceiverActions = ReceiverActions(),
    ) {
        val state = ReceiverUiState(
            snapshot = ReceiverSnapshot(receiving, ntrip = ntrip),
            form = form,
            formInvalid = formInvalid,
            internetAvailable = internet,
            nowMs = nowMs,
        )
        compose.setContent {
            TopoPhoneTheme(EffectiveTheme.LIGHT) { ReceiverScreen(ThemeState.DEFAULT, {}, state, actions) }
        }
    }

    private fun node(tag: String) = compose.onNodeWithTag(tag).performScrollTo()

    private fun assertMessage(id: Int) {
        node(NTRIP_STATUS_TAG).assert(hasText(text(id)))
    }

    @Test
    fun `S002 RF-18 se pueden escribir servidor, puerto, punto de montaje, usuario y contrasena`() {
        var form by mutableStateOf(NtripForm())
        compose.setContent {
            TopoPhoneTheme(EffectiveTheme.LIGHT) {
                ReceiverScreen(
                    ThemeState.DEFAULT,
                    {},
                    ReceiverUiState(snapshot = ReceiverSnapshot(receiving), form = form),
                    ReceiverActions(onFormChange = { form = it }),
                )
            }
        }

        node(NTRIP_PORT_TAG).assert(hasText("2101"))
        node(NTRIP_HOST_TAG).performTextInput("caster.es")
        node(NTRIP_MOUNTPOINT_TAG).performTextInput("VRS3")
        node(NTRIP_USERNAME_TAG).performTextInput("topo")
        node(NTRIP_PASSWORD_TAG).performTextInput("s3cr3t")
        compose.waitForIdle()

        form shouldBe NtripForm("caster.es", "2101", "VRS3", "topo", "s3cr3t")
    }

    @Test
    fun `S002 RF-18 S002 RF-27 la contrasena se oculta y se avisa de que no se guarda`() {
        show(form = NtripForm(password = "s3cr3t"))

        node(NTRIP_PASSWORD_TAG).assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Password))
        node(NTRIP_PASSWORD_TAG).assert(
            SemanticsMatcher("el texto visible no contiene la contraseña") {
                SemanticsProperties.EditableText in it.config && !it.config[SemanticsProperties.EditableText].text.contains("s3cr3t")
            },
        )
        compose.onNodeWithText(text(R.string.ntrip_password_not_saved)).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `S002 RF-18 un formulario incompleto se indica`() {
        show(formInvalid = true)

        compose.onNodeWithText(text(R.string.ntrip_form_invalid)).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `S002 RF-19 pulsar Conectar NTRIP conecta con el caster`() {
        var connect = false
        show(form = NtripForm(host = "caster.es", mountpoint = "VRS3"), actions = ReceiverActions(onConnectNtrip = { connect = true }))

        node(NTRIP_CONNECT_TAG).assert(hasText(text(R.string.ntrip_connect))).performClick()

        connect shouldBe true
    }

    @Test
    fun `S002 RF-19 conectado se puede desconectar y el formulario no se puede editar`() {
        var disconnect = false
        show(ntrip = NtripState.Streaming(0, null), actions = ReceiverActions(onDisconnectNtrip = { disconnect = true }))

        node(NTRIP_HOST_TAG).assertIsNotEnabled()
        node(NTRIP_DISCONNECT_TAG).performClick()

        disconnect shouldBe true
    }

    @Test
    fun `S002 RF-21 credenciales rechazadas`() {
        show(ntrip = NtripState.Stopped(NtripFailure.UNAUTHORIZED))

        assertMessage(R.string.ntrip_error_unauthorized)
        text(R.string.ntrip_error_unauthorized) shouldBe "Usuario o contraseña incorrectos"
        node(NTRIP_HOST_TAG).assertIsEnabled()
    }

    @Test
    fun `S002 RF-22 punto de montaje inexistente`() {
        show(ntrip = NtripState.Stopped(NtripFailure.MOUNTPOINT_NOT_FOUND))

        assertMessage(R.string.ntrip_error_mountpoint)
        text(R.string.ntrip_error_mountpoint) shouldBe "El punto de montaje no existe"
    }

    @Test
    fun `S002 RF-23 caster inaccesible y sin internet`() {
        show(ntrip = NtripState.Stopped(NtripFailure.UNREACHABLE), internet = false)

        assertMessage(R.string.ntrip_error_unreachable)
        text(R.string.ntrip_error_unreachable) shouldBe "No se puede conectar con el caster"
        compose.onNodeWithText(text(R.string.ntrip_no_internet)).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `S002 RF-23 caster inaccesible con internet no se culpa a la red`() {
        show(ntrip = NtripState.Stopped(NtripFailure.UNREACHABLE), internet = true)

        assertMessage(R.string.ntrip_error_unreachable)
        compose.onNodeWithText(text(R.string.ntrip_no_internet)).assertDoesNotExist()
    }

    @Test
    fun `S002 RF-24 sin correcciones durante 10 s se avisa y se ofrece reconectar`() {
        var connect = false
        show(ntrip = NtripState.Stopped(NtripFailure.NO_DATA), actions = ReceiverActions(onConnectNtrip = { connect = true }))

        assertMessage(R.string.ntrip_error_no_data)
        node(NTRIP_CONNECT_TAG).assert(hasText(text(R.string.ntrip_reconnect))).performClick()

        connect shouldBe true
    }

    @Test
    fun `S002 RF-24 si el caster corta se avisa y se ofrece reconectar`() {
        show(ntrip = NtripState.Stopped(NtripFailure.CONNECTION_LOST))

        assertMessage(R.string.ntrip_error_connection_lost)
        node(NTRIP_CONNECT_TAG).assert(hasText(text(R.string.ntrip_reconnect)))
    }

    @Test
    fun `S002 RF-25 se muestran los bytes recibidos y los segundos desde la ultima correccion`() {
        show(ntrip = NtripState.Streaming(bytesReceived = 123_456, lastCorrectionAtMs = 10_000), nowMs = 13_400)

        assertMessage(R.string.ntrip_state_streaming)
        node(NTRIP_BYTES_TAG).assert(hasText(text(R.string.ntrip_bytes, "123.456")))
        node(NTRIP_LAST_CORRECTION_TAG).assert(hasText(text(R.string.ntrip_last_correction, 3)))
    }

    @Test
    fun `S002 RF-25 antes de la primera correccion se indica que se espera`() {
        show(ntrip = NtripState.Streaming(bytesReceived = 0, lastCorrectionAtMs = null), nowMs = 13_400)

        node(NTRIP_LAST_CORRECTION_TAG).assert(hasText(text(R.string.ntrip_waiting_first)))
    }

    @Test
    fun `S002 RF-26 el formulario muestra los datos recordados`() {
        show(form = NtripForm("caster.es", "2102", "VRS3", "topo", ""))

        node(NTRIP_HOST_TAG).assert(hasText("caster.es"))
        node(NTRIP_PORT_TAG).assert(hasText("2102"))
        node(NTRIP_MOUNTPOINT_TAG).assert(hasText("VRS3"))
        node(NTRIP_USERNAME_TAG).assert(hasText("topo"))
    }

    @Test
    fun `S002 RF-28 sin receptor se indica que antes hay que conectarlo`() {
        show(ntrip = NtripState.ReceiverNotConnected)

        assertMessage(R.string.ntrip_receiver_not_connected)
    }
}
