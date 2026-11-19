package es.topophone.app.experimental

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.app.R
import es.topophone.app.ui.text
import es.topophone.core.model.preferences.ThemeState
import es.topophone.receiver.experimental.ReachConfigEntry
import es.topophone.receiver.experimental.ReachConfigView
import es.topophone.receiver.experimental.ReachPart
import es.topophone.receiver.experimental.ReachPartId
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** S044/RF-14, RF-15: «Configuración del receptor» en solo lectura. Interfaz en español (es-ES). */
@RunWith(AndroidJUnit4::class)
class ReceiverConfigScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val view = ReachConfigView(
        positioning = listOf(
            ReachConfigEntry("positioning_settings.positioning_mode", "kinematic", false),
            ReachConfigEntry("positioning_settings.snr_mask", "35", false),
        ),
        output = listOf(
            ReachConfigEntry("position_output.output1.type", "bluetooth", false),
            ReachConfigEntry("position_output.output2.token", "", true),
        ),
    )

    private var reloads = 0
    private var backs = 0

    private fun show(state: ConfigUiState) = compose.setContent {
        ReceiverConfigScreen(state, onReload = { reloads++ }, themeState = ThemeState(), onToggleSun = {}, onBack = { backs++ })
    }

    @Test
    fun `S044 RF-14 muestra los ajustes de posicionamiento y de salida con su valor`() {
        show(ConfigUiState.Loaded(ReachPart.Ok(view)))

        compose.onNodeWithTag(
            CONFIG_LIST_TAG,
        ).performScrollToNode(androidx.compose.ui.test.hasTestTag(configEntryTag("position_output.output1.type")))
        compose.onNodeWithTag(configEntryTag("position_output.output1.type")).assertTextContains("bluetooth", substring = true)
        compose.onNodeWithTag(
            CONFIG_LIST_TAG,
        ).performScrollToNode(androidx.compose.ui.test.hasTestTag(configEntryTag("positioning_settings.snr_mask")))
        compose.onNodeWithTag(configEntryTag("positioning_settings.snr_mask")).assertTextContains("35", substring = true)
    }

    @Test
    fun `S044 RNF-T11 el valor de una clave sensible sale como oculto`() {
        show(ConfigUiState.Loaded(ReachPart.Ok(view)))

        compose.onNodeWithTag(
            CONFIG_LIST_TAG,
        ).performScrollToNode(androidx.compose.ui.test.hasTestTag(configEntryTag("position_output.output2.token")))
        compose.onNodeWithTag(
            configEntryTag("position_output.output2.token"),
        ).assertTextContains(text(R.string.experimental_config_hidden), substring = true)
    }

    @Test
    fun `S044 RF-14 es solo lectura sin ningun campo editable`() {
        show(ConfigUiState.Loaded(ReachPart.Ok(view)))

        compose.onAllNodes(hasSetTextAction()).assertCountEquals(0)
    }

    @Test
    fun `S044 RF-14 sin secciones de salida se dice`() {
        show(ConfigUiState.Loaded(ReachPart.Ok(ReachConfigView(view.positioning, emptyList()))))

        compose.onNodeWithTag(CONFIG_LIST_TAG).performScrollToNode(androidx.compose.ui.test.hasTestTag(CONFIG_NO_OUTPUT_TAG))
        compose.onNodeWithTag(CONFIG_NO_OUTPUT_TAG).assertTextContains(text(R.string.experimental_config_no_output))
    }

    @Test
    fun `S044 RF-15 una parte incompatible dice Receptor no compatible y deja volver a leer`() {
        show(ConfigUiState.Loaded(ReachPart.Incompatible(ReachPartId.SETTINGS, "falta configuration.positioning_settings")))

        compose.onNodeWithTag(CONFIG_INCOMPATIBLE_TAG).assertTextContains(text(R.string.experimental_incompatible))
        compose.onNodeWithTag(CONFIG_RELOAD_TAG).performClick()

        reloads shouldBe 1
    }

    @Test
    fun `S044 RF-14 mientras se lee y sin receptor se dice`() {
        show(ConfigUiState.Loading)
        compose.onNodeWithTag(CONFIG_LOADING_TAG).assertTextContains(text(R.string.experimental_loading))
    }

    @Test
    fun `S044 RF-14 sin receptor al que preguntar se explica`() {
        show(ConfigUiState.Unavailable)

        compose.onNodeWithTag(CONFIG_UNAVAILABLE_TAG).assertTextContains(text(R.string.experimental_config_unavailable))
        compose.onAllNodesWithTag(CONFIG_LIST_TAG).assertCountEquals(0)
    }

    @Test
    fun `S044 RF-14 el recuento de ajustes leidos se enseña`() {
        show(ConfigUiState.Loaded(ReachPart.Ok(view)))

        compose.onNodeWithTag(CONFIG_COUNT_TAG).assertTextContains(text(R.string.experimental_config_count, 4))
    }
}
