package es.topophone.app.experimental

import androidx.compose.runtime.CompositionLocalProvider
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
import es.topophone.core.model.guide.EquipmentProfile
import es.topophone.core.model.preferences.ThemeState
import es.topophone.receiver.experimental.ReachDeviceSettings
import es.topophone.receiver.experimental.ReachEndpoint
import es.topophone.receiver.experimental.ReachIdentity
import es.topophone.receiver.experimental.ReachInfoReading
import es.topophone.receiver.experimental.ReachPart
import es.topophone.receiver.experimental.ReachPartId
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith

/** S044/RF-6, RF-11, RF-12, RF-15: el panel del módulo experimental y su marca. Interfaz en español (es-ES). */
@RunWith(AndroidJUnit4::class)
class ExperimentalPanelScreenTest {

    @get:Rule
    val compose = createComposeRule()

    @get:Rule
    val folder = TemporaryFolder()

    private val ready = ReachSource.Ready("wifi:a", ReachEndpoint.parse("192.168.42.1")!!)
    private val reading = ReachInfoReading(
        ReachPart.Ok(ReachIdentity("Reach RS3", "SINTETICO0001", "v0.0")),
        ReachPart.Ok(ReachDeviceSettings("rover", 1.8)),
    )

    private class Calls {
        val saveAddress = mutableListOf<String>()
        var refresh = 0
        var fill = 0
        var confirm = 0
        var cancel = 0
        var dismissSaved = 0
        var back = 0
    }

    private fun show(state: PanelUiState, calls: Calls = Calls()) = compose.setContent {
        ExperimentalPanelScreen(
            state = state,
            actions = PanelActions(
                onRefresh = { calls.refresh++ },
                onSaveAddress = { calls.saveAddress += it },
                onFillProfile = { calls.fill++ },
                onConfirmProfile = { calls.confirm++ },
                onCancelProfile = { calls.cancel++ },
                onDismissSaved = { calls.dismissSaved++ },
            ),
            themeState = ThemeState(),
            onToggleSun = {},
            onBack = { calls.back++ },
        )
    }

    @Test
    fun `S044 RF-11 con el movil o una reproduccion se explica que no aplica`() {
        show(PanelUiState(source = ReachSource.NotApplicable))

        compose.onNodeWithTag(PANEL_NOT_APPLICABLE_TAG).assertTextContains(text(R.string.experimental_not_applicable))
    }

    @Test
    fun `S044 RF-11 sin IP privada se pide y lo escrito se entrega al guardar`() {
        val calls = Calls()
        show(PanelUiState(source = ReachSource.NeedsAddress("wifi:b")), calls)

        compose.onNodeWithTag(PANEL_ADDRESS_FIELD_TAG).performTextInput("192.168.1.99")
        compose.onNodeWithTag(PANEL_ADDRESS_SAVE_TAG).performClick()

        calls.saveAddress shouldBe listOf("192.168.1.99")
    }

    @Test
    fun `S044 RF-23 una direccion rechazada se explica`() {
        show(PanelUiState(source = ReachSource.NeedsAddress("wifi:b"), addressRejected = true))

        compose.onNodeWithTag(PANEL_ADDRESS_ERROR_TAG).assertTextContains(text(R.string.experimental_address_rejected))
    }

    @Test
    fun `S044 RF-11 mientras se lee se ve que se esta leyendo`() {
        show(PanelUiState(source = ready, info = ReachInfoState.Loading))

        compose.onNodeWithTag(PANEL_LOADING_TAG).assertTextContains(text(R.string.experimental_loading))
    }

    @Test
    fun `S044 RF-11 lo leido muestra modelo, serie, firmware, rol y altura de antena`() {
        show(PanelUiState(source = ready, info = ReachInfoState.Loaded(reading)))

        compose.onNodeWithTag(PANEL_MODEL_TAG).assertTextContains("Reach RS3", substring = true)
        compose.onNodeWithTag(PANEL_SERIAL_TAG).assertTextContains("SINTETICO0001", substring = true)
        compose.onNodeWithTag(PANEL_FIRMWARE_TAG).assertTextContains("v0.0", substring = true)
        compose.onNodeWithTag(PANEL_ROLE_TAG).assertTextContains("rover", substring = true)
        compose.onNodeWithTag(PANEL_ANTENNA_TAG).assertTextContains("1,800 m", substring = true)
    }

    @Test
    fun `S044 RF-15 una identidad incompatible dice Receptor no compatible y no deja rellenar el perfil`() {
        val incompatible = reading.copy(identity = ReachPart.Incompatible(ReachPartId.IDENTITY, "falta device.serial_number"))
        show(PanelUiState(source = ready, info = ReachInfoState.Loaded(incompatible)))

        compose.onNodeWithTag(PANEL_IDENTITY_INCOMPATIBLE_TAG).assertTextContains(text(R.string.experimental_incompatible))
        compose.onNodeWithTag(PANEL_ANTENNA_TAG).assertTextContains("1,800 m", substring = true)
        compose.onNodeWithTag(PANEL_FILL_PROFILE_TAG).assertIsNotEnabled()
    }

    @Test
    fun `S044 RF-15 los ajustes incompatibles marcan solo esa parte`() {
        val incompatible = reading.copy(device = ReachPart.Incompatible(ReachPartId.DEVICE, "x"))
        show(PanelUiState(source = ready, info = ReachInfoState.Loaded(incompatible)))

        compose.onNodeWithTag(PANEL_DEVICE_INCOMPATIBLE_TAG).assertTextContains(text(R.string.experimental_incompatible))
        compose.onNodeWithTag(PANEL_MODEL_TAG).assertTextContains("Reach RS3", substring = true)
        compose.onNodeWithTag(PANEL_FILL_PROFILE_TAG).assertIsEnabled()
    }

    @Test
    fun `S044 RF-15 Reintentar vuelve a leer`() {
        val calls = Calls()
        val incompatible = reading.copy(identity = ReachPart.Incompatible(ReachPartId.IDENTITY, "x"))
        show(PanelUiState(source = ready, info = ReachInfoState.Loaded(incompatible)), calls)

        compose.onNodeWithTag(PANEL_RETRY_TAG).performScrollTo().performClick()

        calls.refresh shouldBe 1
    }

    @Test
    fun `S044 RF-12 Rellenar el perfil lo pide al ViewModel`() {
        val calls = Calls()
        show(PanelUiState(source = ready, info = ReachInfoState.Loaded(reading)), calls)

        compose.onNodeWithTag(PANEL_FILL_PROFILE_TAG).performScrollTo().performClick()

        calls.fill shouldBe 1
    }

    @Test
    fun `S044 RF-12 la propuesta enseña lo que se guardara y confirmar lo entrega`() {
        val calls = Calls()
        val proposal = EquipmentProfile("Reach RS3", "SINTETICO0001", "v0.0")
        show(PanelUiState(source = ready, info = ReachInfoState.Loaded(reading), profileProposal = proposal), calls)

        compose.onNodeWithTag(PANEL_PROFILE_DIALOG_TAG).assertTextContains("SINTETICO0001", substring = true)
        compose.onNodeWithTag(PANEL_PROFILE_CONFIRM_TAG).performClick()

        calls.confirm shouldBe 1
        calls.cancel shouldBe 0
    }

    @Test
    fun `S044 RF-12 cancelar la propuesta no confirma nada`() {
        val calls = Calls()
        show(
            PanelUiState(
                source = ready,
                info = ReachInfoState.Loaded(reading),
                profileProposal = EquipmentProfile("Reach RS3", "SINTETICO0001", "v0.0"),
            ),
            calls,
        )

        compose.onNodeWithTag(PANEL_PROFILE_CANCEL_TAG).performClick()

        calls.cancel shouldBe 1
        calls.confirm shouldBe 0
    }

    @Test
    fun `S044 RF-12 tras guardar se avisa y el aviso se cierra`() {
        val calls = Calls()
        show(PanelUiState(source = ready, info = ReachInfoState.Loaded(reading), profileSaved = true), calls)

        compose.onNodeWithTag(PANEL_PROFILE_SAVED_TAG).assertTextContains(text(R.string.experimental_profile_saved))
        compose.onNodeWithTag(PANEL_PROFILE_SAVED_TAG).performScrollTo().performClick()

        calls.dismissSaved shouldBe 1
    }

    @Test
    fun `S044 RF-6 la marca Experimental sale en la cabecera con el modulo activo y no sale apagado`() {
        TestPreferences(folder.root, active = true).use { on ->
            val feature = ExperimentalFeatureImpl(on.preferences) { 1L }
            compose.setContent { feature.ReceiverHeaderSlot() }

            compose.waitUntil(5_000) { compose.onAllNodesWithTag(EXPERIMENTAL_MARK_TAG).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag(EXPERIMENTAL_MARK_TAG).assertTextContains(text(R.string.experimental_mark))
        }
    }

    @Test
    fun `S044 RF-6 apagado no hay marca ni entrada de menu`() {
        TestPreferences(folder.root, active = false).use { off ->
            val feature = ExperimentalFeatureImpl(off.preferences) { 1L }
            compose.setContent {
                feature.ReceiverHeaderSlot()
                feature.ReceiverMenuEntry()
            }
            compose.waitForIdle()

            compose.onAllNodesWithTag(EXPERIMENTAL_MARK_TAG).assertCountEquals(0)
            compose.onAllNodesWithTag(EXPERIMENTAL_MENU_TAG).assertCountEquals(0)
        }
    }

    @Test
    fun `S044 RF-6 con el modulo activo la entrada de menu abre el panel`() {
        var opened: Any? = null
        TestPreferences(folder.root, active = true).use { on ->
            val feature = ExperimentalFeatureImpl(on.preferences) { 1L }
            compose.setContent {
                CompositionLocalProvider(LocalExperimentalNavigation provides { opened = it }) { feature.ReceiverMenuEntry() }
            }

            compose.waitUntil(5_000) { compose.onAllNodesWithTag(EXPERIMENTAL_MENU_TAG).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag(EXPERIMENTAL_MENU_TAG).assertTextContains(text(R.string.experimental_settings_title), substring = true)
            compose.onNodeWithTag(EXPERIMENTAL_MENU_TAG).performClick()

            opened shouldBe ExperimentalPanelDestination
        }
    }
}
