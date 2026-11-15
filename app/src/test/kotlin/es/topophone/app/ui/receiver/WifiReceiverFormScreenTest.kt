package es.topophone.app.ui.receiver

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
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
import es.topophone.core.model.receiver.WifiReceiverDraft
import es.topophone.core.model.receiver.WifiReceiverProblem
import es.topophone.core.model.receiver.WifiReceiverWarning
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** El formulario del receptor Wi-Fi: campos, motivos del rechazo, avisos y borrado confirmado (S021/RF-2 a RF-4). Interfaz en español. */
@RunWith(AndroidJUnit4::class)
class WifiReceiverFormScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private fun show(state: WifiReceiverFormUiState, actions: WifiReceiverFormActions = WifiReceiverFormActions()) {
        compose.setContent {
            TopoPhoneTheme(EffectiveTheme.LIGHT) { WifiReceiverFormScreen(ThemeState.DEFAULT, {}, state, actions) }
        }
    }

    private val new = WifiReceiverFormUiState(loaded = true)
    private val editing = WifiReceiverFormUiState(
        draft = WifiReceiverDraft("RS3 campo", "192.168.0.20", "9001", "9002"),
        loaded = true,
        editing = true,
    )

    @Test
    fun `S021 RF-2 un receptor nuevo muestra los cuatro campos con los valores iniciales`() {
        show(new)

        compose.onNodeWithText(text(R.string.wifi_form_new)).assertIsDisplayed()
        compose.onNodeWithTag(WIFI_NAME_TAG).assertIsDisplayed()
        compose.onNodeWithTag(WIFI_HOST_TAG).assert(hasText("192.168.42.1"))
        compose.onNodeWithTag(WIFI_POSITION_PORT_TAG).assert(hasText("9001"))
        compose.onNodeWithTag(WIFI_CORRECTIONS_PORT_TAG).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(text(R.string.wifi_form_corrections_help)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag(WIFI_DELETE_TAG).assertDoesNotExist() // no hay nada que borrar en uno nuevo
    }

    @Test
    fun `S021 RF-2 lo que se escribe llega a cada accion`() {
        var shown by mutableStateOf(new.copy(draft = WifiReceiverDraft("", "", "", "")))
        val actions = WifiReceiverFormActions(
            onName = { shown = shown.copy(draft = shown.draft.copy(name = it)) },
            onHost = { shown = shown.copy(draft = shown.draft.copy(host = it)) },
            onPositionPort = { shown = shown.copy(draft = shown.draft.copy(positionPort = it)) },
            onCorrectionsPort = { shown = shown.copy(draft = shown.draft.copy(correctionsPort = it)) },
        )
        compose.setContent {
            TopoPhoneTheme(EffectiveTheme.LIGHT) { WifiReceiverFormScreen(ThemeState.DEFAULT, {}, shown, actions) }
        }

        compose.onNodeWithTag(WIFI_NAME_TAG).performTextInput("RS3")
        compose.onNodeWithTag(WIFI_HOST_TAG).performTextInput("reach.local")
        compose.onNodeWithTag(WIFI_POSITION_PORT_TAG).performTextInput("9100")
        compose.onNodeWithTag(WIFI_CORRECTIONS_PORT_TAG).performScrollTo().performTextInput("9101")

        shown.draft shouldBe WifiReceiverDraft("RS3", "reach.local", "9100", "9101")
    }

    @Test
    fun `S021 RF-3 cada motivo de rechazo sale bajo su campo`() {
        show(
            new.copy(
                problems = setOf(
                    WifiReceiverProblem.HOST_INVALID,
                    WifiReceiverProblem.POSITION_PORT_INVALID,
                    WifiReceiverProblem.CORRECTIONS_PORT_INVALID,
                ),
            ),
        )

        compose.onNodeWithText(text(R.string.wifi_form_host_invalid)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.wifi_form_position_invalid)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.wifi_form_corrections_invalid)).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `S021 RF-3 una direccion vacia tiene su propio motivo`() {
        show(new.copy(problems = setOf(WifiReceiverProblem.HOST_EMPTY)))

        compose.onNodeWithText(text(R.string.wifi_form_host_empty)).assertIsDisplayed()
    }

    @Test
    fun `S021 RF-3 un puerto bajo avisa de que suele estar reservado`() {
        show(new.copy(warnings = setOf(WifiReceiverWarning.RESERVED_POSITION_PORT)))

        compose.onNodeWithText(text(R.string.wifi_form_port_reserved)).assertIsDisplayed()
    }

    @Test
    fun `S021 RF-2 Guardar llama a la accion de guardar`() {
        var saved = 0
        show(new, WifiReceiverFormActions(onSave = { saved++ }))

        compose.onNodeWithTag(WIFI_SAVE_TAG).performScrollTo().performClick()

        saved shouldBe 1
    }

    @Test
    fun `S021 RF-4 editar un receptor muestra su titulo, sus valores y el boton de borrar`() {
        show(editing)

        compose.onNodeWithText(text(R.string.wifi_form_edit)).assertIsDisplayed()
        compose.onNodeWithTag(WIFI_NAME_TAG).assert(hasText("RS3 campo"))
        compose.onNodeWithTag(WIFI_HOST_TAG).assert(hasText("192.168.0.20"))
        compose.onNodeWithTag(WIFI_DELETE_TAG).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `S021 RF-4 Borrar receptor pide confirmacion con su nombre y Borrar lo confirma`() {
        var asked = 0
        var confirmed = 0
        var cancelled = 0
        val actions = WifiReceiverFormActions(
            onAskDelete = { asked++ },
            onConfirmDelete = { confirmed++ },
            onCancelDelete = { cancelled++ },
        )
        show(editing.copy(confirmingDelete = true), actions)

        compose.onNodeWithText(text(R.string.wifi_form_delete_title)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.wifi_form_delete_text, "RS3 campo")).assertIsDisplayed()
        compose.onNodeWithTag(WIFI_DELETE_CONFIRM_TAG).performClick()
        compose.onNodeWithText(text(R.string.receiver_add_cancel)).performClick()

        confirmed shouldBe 1
        cancelled shouldBe 1
        asked shouldBe 0
    }

    @Test
    fun `S021 RF-4 el boton Borrar receptor solo pide, no borra`() {
        var asked = 0
        var confirmed = 0
        show(editing, WifiReceiverFormActions(onAskDelete = { asked++ }, onConfirmDelete = { confirmed++ }))

        compose.onNodeWithTag(WIFI_DELETE_TAG).performScrollTo().performClick()

        asked shouldBe 1
        confirmed shouldBe 0
    }

    @Test
    fun `S021 RF-2 al terminar el formulario vuelve atras`() {
        var back = 0
        show(new.copy(done = true), WifiReceiverFormActions(onBack = { back++ }))

        compose.waitForIdle()

        back shouldBe 1
    }
}
