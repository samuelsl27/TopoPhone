package es.topophone.app.experimental

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.app.R
import es.topophone.app.ui.text
import es.topophone.core.model.preferences.ThemeState
import es.topophone.receiver.experimental.ReachLiveState
import es.topophone.receiver.experimental.WriteFailure
import es.topophone.receiver.experimental.WriteResult
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** S044/RF-20, RF-21: «Iniciar log» y «Parar log». Interfaz en español (es-ES). */
@RunWith(AndroidJUnit4::class)
class LogsScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val stopped = LogsUiState(
        live = ReachLiveState(connected = true, batteryPercent = 80, logsWriting = false),
        canWrite = true,
        action = LogsAction.START,
    )
    private val writing = LogsUiState(
        live = ReachLiveState(connected = true, batteryPercent = 80, logsWriting = true),
        canWrite = true,
        action = LogsAction.STOP,
    )

    private class Calls {
        var request = 0
        var confirm = 0
        var cancel = 0
        var dismiss = 0
    }

    private fun show(state: LogsUiState, calls: Calls = Calls()) = compose.setContent {
        LogsScreen(
            state = state,
            actions = LogsActions(
                onRequest = { calls.request++ },
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
    fun `S044 RF-20 con los logs parados enseña el estado y solo ofrece Iniciar log`() {
        val calls = Calls()
        show(stopped, calls)

        compose.onNodeWithTag(LOGS_STATE_TAG).assertTextContains(text(R.string.experimental_logs_stopped))
        compose.onAllNodesWithTag(LOGS_STOP_TAG).assertCountEquals(0)
        compose.onNodeWithTag(LOGS_START_TAG).assertTextContains(text(R.string.experimental_logs_start))
        compose.onNodeWithTag(LOGS_START_TAG).performClick()

        calls.request shouldBe 1
    }

    @Test
    fun `S044 RF-20 con los logs escribiendo solo ofrece Parar log`() {
        val calls = Calls()
        show(writing, calls)

        compose.onNodeWithTag(LOGS_STATE_TAG).assertTextContains(text(R.string.experimental_logs_writing))
        compose.onAllNodesWithTag(LOGS_START_TAG).assertCountEquals(0)
        compose.onNodeWithTag(LOGS_STOP_TAG).assertTextContains(text(R.string.experimental_logs_stop))
        compose.onNodeWithTag(LOGS_STOP_TAG).performClick()

        calls.request shouldBe 1
    }

    @Test
    fun `S044 RF-20 la confirmacion de Parar avisa de que el log activo se cerrara y no continuara`() {
        show(writing.copy(confirming = LogsAction.STOP))

        compose.onNodeWithTag(LOGS_CONFIRM_DIALOG_TAG).assertTextContains(text(R.string.experimental_logs_stop_warning), substring = true)
    }

    @Test
    fun `S044 RF-20 la confirmacion de Iniciar no lleva el aviso de parar`() {
        show(stopped.copy(confirming = LogsAction.START))

        compose.onNodeWithTag(LOGS_CONFIRM_DIALOG_TAG).assertTextContains(text(R.string.experimental_logs_start_question), substring = true)
        compose.onAllNodesWithTag(LOGS_STOP_WARNING_TAG).assertCountEquals(0)
    }

    @Test
    fun `S044 RF-20 confirmar y cancelar se entregan`() {
        val calls = Calls()
        show(stopped.copy(confirming = LogsAction.START), calls)

        compose.onNodeWithTag(LOGS_CONFIRM_YES_TAG).performClick()
        calls.confirm shouldBe 1
        calls.cancel shouldBe 0
    }

    @Test
    fun `S044 RF-20 cancelar la confirmacion no confirma`() {
        val calls = Calls()
        show(stopped.copy(confirming = LogsAction.START), calls)

        compose.onNodeWithTag(LOGS_CONFIRM_NO_TAG).performClick()

        calls.cancel shouldBe 1
        calls.confirm shouldBe 0
    }

    @Test
    fun `S044 RF-20 sin directo no se ofrece ninguna accion y se explica`() {
        show(LogsUiState(live = ReachLiveState()))

        compose.onNodeWithTag(LOGS_NO_LIVE_TAG).assertTextContains(text(R.string.experimental_logs_no_live))
        compose.onAllNodesWithTag(LOGS_START_TAG).assertCountEquals(0)
        compose.onAllNodesWithTag(LOGS_STOP_TAG).assertCountEquals(0)
    }

    @Test
    fun `S044 RF-20 con el directo pero sin saber el estado de los logs se dice que se espera`() {
        show(LogsUiState(live = ReachLiveState(connected = true)))

        compose.onNodeWithTag(LOGS_STATE_TAG).assertTextContains(text(R.string.experimental_logs_unknown))
        compose.onAllNodesWithTag(LOGS_START_TAG).assertCountEquals(0)
    }

    @Test
    fun `S044 RF-20 un resultado confirmado dice el estado resultante`() {
        show(stopped.copy(result = WriteResult.Confirmed(true)))
        compose.onNodeWithTag(LOGS_RESULT_TAG).performScrollTo().assertTextContains(text(R.string.experimental_logs_confirmed_writing))
    }

    @Test
    fun `S044 RF-20 parar confirmado dice que estan parados`() {
        show(writing.copy(result = WriteResult.Confirmed(false)))
        compose.onNodeWithTag(LOGS_RESULT_TAG).performScrollTo().assertTextContains(text(R.string.experimental_logs_confirmed_stopped))
    }

    @Test
    fun `S044 RF-21 si no coincide enseña el estado que se ve`() {
        show(stopped.copy(result = WriteResult.NotConfirmed(read = false, requestFailed = false)))
        compose.onNodeWithTag(LOGS_RESULT_TAG).performScrollTo().assertTextContains(text(R.string.experimental_logs_mismatch_stopped))
    }

    @Test
    fun `S044 RF-21 si el envio fallo o no se conoce el estado se dice`() {
        show(stopped.copy(result = WriteResult.NotConfirmed(read = null, requestFailed = true)))
        compose.onNodeWithTag(LOGS_RESULT_TAG).performScrollTo().assertTextContains(text(R.string.experimental_logs_send_failed_unknown))
    }

    @Test
    fun `S044 RF-21 si no se llego a enviar se dice por que`() {
        show(stopped.copy(result = WriteResult.Failed(WriteFailure.NO_LIVE)))
        compose.onNodeWithTag(LOGS_RESULT_TAG).performScrollTo().assertTextContains(text(R.string.experimental_logs_failed_no_live))
    }

    @Test
    fun `S044 RF-20 el resultado se puede cerrar`() {
        val calls = Calls()
        show(stopped.copy(result = WriteResult.Confirmed(true)), calls)

        compose.onNodeWithTag(LOGS_RESULT_TAG).performScrollTo().performClick()

        calls.dismiss shouldBe 1
    }
}
