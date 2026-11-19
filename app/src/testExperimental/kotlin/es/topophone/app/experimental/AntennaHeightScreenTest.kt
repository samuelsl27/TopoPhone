package es.topophone.app.experimental

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.app.R
import es.topophone.app.ui.text
import es.topophone.core.model.preferences.ThemeState
import es.topophone.receiver.experimental.AntennaHeightProposal
import es.topophone.receiver.experimental.ReachEndpoint
import es.topophone.receiver.experimental.WriteFailure
import es.topophone.receiver.experimental.WriteResult
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** S044/RF-18, RF-19, RF-21: «Enviar altura de antena». Interfaz en español (es-ES). */
@RunWith(AndroidJUnit4::class)
class AntennaHeightScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val ready = ReachSource.Ready("wifi:a", ReachEndpoint.parse("192.168.42.1")!!)
    private val base = AntennaUiState(
        source = ready,
        currentM = 1.8,
        canSend = true,
        proposal = AntennaHeightProposal(1.95, warnDoubleCount = false),
        text = "1.95",
        valueM = 1.95,
    )

    private class Calls {
        val texts = mutableListOf<String>()
        var send = 0
        var confirm = 0
        var cancel = 0
        var dismiss = 0
    }

    private fun show(state: AntennaUiState, calls: Calls = Calls()) = compose.setContent {
        AntennaHeightScreen(
            state = state,
            actions = AntennaActions(
                onTextChange = { calls.texts += it },
                onSend = { calls.send++ },
                onConfirm = { calls.confirm++ },
                onCancel = { calls.cancel++ },
                onDismissResult = { calls.dismiss++ },
            ),
            themeState = ThemeState(),
            onToggleSun = {},
            onBack = {},
        )
    }

    @Test
    fun `S044 RF-18 enseña el valor actual del receptor y la propuesta`() {
        show(base)

        compose.onNodeWithTag(ANTENNA_CURRENT_TAG).assertTextContains("1,800 m", substring = true)
        compose.onNodeWithTag(ANTENNA_PROPOSAL_TAG).assertTextContains("1,950 m", substring = true)
        compose.onAllNodesWithTag(ANTENNA_DOUBLE_COUNT_TAG).assertCountEquals(0)
    }

    @Test
    fun `S044 RF-18 con otro modo se avisa de que enviar otro valor contaria la altura dos veces`() {
        show(base.copy(proposal = AntennaHeightProposal(0.0, warnDoubleCount = true), text = "0", valueM = 0.0))

        compose.onNodeWithTag(ANTENNA_DOUBLE_COUNT_TAG).assertTextContains(text(R.string.experimental_antenna_double_count))
    }

    @Test
    fun `S044 RF-18 lo escrito en el campo se entrega y Enviar pide confirmar`() {
        val calls = Calls()
        show(base, calls)

        compose.onNodeWithTag(ANTENNA_FIELD_TAG).performTextInput("7")
        compose.onNodeWithTag(ANTENNA_SEND_TAG).performScrollTo().performClick()

        calls.texts.size shouldBe 1
        calls.send shouldBe 1
    }

    @Test
    fun `S044 RF-18 sin valor valido o sin poder enviar el boton esta desactivado`() {
        show(base.copy(valueM = null, text = "abc"))
        compose.onNodeWithTag(ANTENNA_SEND_TAG).assertIsNotEnabled()
        compose.onNodeWithTag(ANTENNA_INVALID_TAG).assertTextContains(text(R.string.experimental_antenna_invalid))
    }

    @Test
    fun `S044 RF-15 con la informacion basica invalida se explica por que no se puede enviar`() {
        show(base.copy(canSend = false))

        compose.onNodeWithTag(ANTENNA_SEND_TAG).assertIsNotEnabled()
        compose.onNodeWithTag(ANTENNA_BLOCKED_TAG).assertTextContains(text(R.string.experimental_antenna_blocked))
    }

    @Test
    fun `S044 RF-18 la confirmacion enseña el valor actual y el nuevo y confirmar o cancelar se entregan`() {
        val calls = Calls()
        show(base.copy(confirming = true), calls)

        compose.onNodeWithTag(ANTENNA_CONFIRM_DIALOG_TAG).assertTextContains("1,800 m", substring = true)
        compose.onNodeWithTag(ANTENNA_CONFIRM_DIALOG_TAG).assertTextContains("1,950 m", substring = true)
        compose.onNodeWithTag(ANTENNA_CONFIRM_YES_TAG).performClick()
        calls.confirm shouldBe 1
        calls.cancel shouldBe 0
    }

    @Test
    fun `S044 RF-18 cancelar en la confirmacion no confirma`() {
        val calls = Calls()
        show(base.copy(confirming = true), calls)

        compose.onNodeWithTag(ANTENNA_CONFIRM_NO_TAG).performClick()

        calls.cancel shouldBe 1
        calls.confirm shouldBe 0
    }

    @Test
    fun `S044 RF-19 un resultado confirmado dice que coincide con el valor leido`() {
        show(base.copy(result = WriteResult.Confirmed(1.95)))

        compose.onNodeWithTag(
            ANTENNA_RESULT_TAG,
        ).performScrollTo().assertTextContains(text(R.string.experimental_antenna_confirmed, "1,950 m"))
    }

    @Test
    fun `S044 RF-21 si no coincide enseña el valor que quedo en el receptor`() {
        show(base.copy(result = WriteResult.NotConfirmed(read = 1.6, requestFailed = false)))

        compose.onNodeWithTag(
            ANTENNA_RESULT_TAG,
        ).performScrollTo().assertTextContains(text(R.string.experimental_antenna_mismatch, "1,600 m"))
    }

    @Test
    fun `S044 RF-21 si el envio fallo se dice y se enseña el valor que quedo`() {
        show(base.copy(result = WriteResult.NotConfirmed(read = 1.8, requestFailed = true)))

        compose.onNodeWithTag(
            ANTENNA_RESULT_TAG,
        ).performScrollTo().assertTextContains(text(R.string.experimental_antenna_send_failed, "1,800 m"))
    }

    @Test
    fun `S044 RF-21 si no se pudo releer se dice que no se conoce el valor`() {
        show(base.copy(result = WriteResult.NotConfirmed(read = null, requestFailed = false)))

        compose.onNodeWithTag(ANTENNA_RESULT_TAG).performScrollTo().assertTextContains(text(R.string.experimental_antenna_unknown))
    }

    @Test
    fun `S044 RF-21 cuando no se llego a escribir se dice por que`() {
        show(base.copy(result = WriteResult.Failed(WriteFailure.NO_RESPONSE)))

        compose.onNodeWithTag(
            ANTENNA_RESULT_TAG,
        ).performScrollTo().assertTextContains(text(R.string.experimental_antenna_failed_no_response))
    }

    @Test
    fun `S044 RF-19 el resultado se puede cerrar`() {
        val calls = Calls()
        show(base.copy(result = WriteResult.Confirmed(1.95)), calls)

        compose.onNodeWithTag(ANTENNA_RESULT_TAG).performScrollTo().performClick()

        calls.dismiss shouldBe 1
    }

    @Test
    fun `S044 RF-18 mientras se envia el boton esta desactivado`() {
        show(base.copy(sending = true))

        compose.onNodeWithTag(ANTENNA_SEND_TAG).assertIsNotEnabled()
    }

    @Test
    fun `S044 RF-18 con valor valido y receptor listo el boton esta activo`() {
        show(base)

        compose.onNodeWithTag(ANTENNA_SEND_TAG).assertIsEnabled()
    }
}
