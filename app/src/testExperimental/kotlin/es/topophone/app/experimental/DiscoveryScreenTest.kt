package es.topophone.app.experimental

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertCountEquals
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
import es.topophone.receiver.experimental.DiscoveryPhase
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith

/** S044/RF-7 a RF-10: «Buscar receptores». Interfaz en español (es-ES). */
@RunWith(AndroidJUnit4::class)
class DiscoveryScreenTest {

    @get:Rule
    val compose = createComposeRule()

    @get:Rule
    val folder = TemporaryFolder()

    private val rows =
        listOf(FoundRow("rs3-a", "192.168.1.50", alreadyAdded = false), FoundRow("rs3-b", "192.168.1.51", alreadyAdded = true))

    private class Calls {
        val added = mutableListOf<FoundRow>()
        val drafts = mutableListOf<AddDraft>()
        var confirm = 0
        var cancel = 0
        var retry = 0
        var requestPermission = 0
        var openSettings = 0
    }

    private fun show(state: DiscoveryUiState, calls: Calls = Calls()) = compose.setContent {
        DiscoveryScreen(
            state = state,
            actions = DiscoveryActions(
                onAdd = { calls.added += it },
                onDraftChange = { calls.drafts += it },
                onConfirmAdd = { calls.confirm++ },
                onCancelAdd = { calls.cancel++ },
                onRetry = { calls.retry++ },
                onRequestPermission = { calls.requestPermission++ },
                onOpenSettings = { calls.openSettings++ },
            ),
            themeState = ThemeState(),
            onToggleSun = {},
            onBack = {},
        )
    }

    @Test
    fun `S044 RF-7 mientras busca lo dice y enseña el nombre y la direccion de cada receptor`() {
        show(DiscoveryUiState(phase = DiscoveryPhase.Searching, found = rows))

        compose.onNodeWithTag(DISCOVERY_SEARCHING_TAG).assertTextContains(text(R.string.experimental_discovery_searching))
        compose.onNodeWithTag(discoveryRowTag("192.168.1.50")).assertTextContains("rs3-a", substring = true)
        compose.onNodeWithTag(discoveryRowTag("192.168.1.50")).assertTextContains("192.168.1.50", substring = true)
    }

    @Test
    fun `S044 RF-8 un receptor ya dado de alta dice Ya anadido y no ofrece anadirlo`() {
        show(DiscoveryUiState(phase = DiscoveryPhase.Done, found = rows))

        compose.onNodeWithTag(discoveryAddedTag("192.168.1.51")).assertTextContains(text(R.string.experimental_discovery_already_added))
        compose.onAllNodesWithTag(discoveryAddTag("192.168.1.51")).assertCountEquals(0)
        compose.onNodeWithTag(discoveryAddTag("192.168.1.50")).assertTextContains(text(R.string.experimental_discovery_add))
    }

    @Test
    fun `S044 RF-8 Anadir entrega el receptor elegido`() {
        val calls = Calls()
        show(DiscoveryUiState(phase = DiscoveryPhase.Done, found = rows), calls)

        compose.onNodeWithTag(discoveryAddTag("192.168.1.50")).performClick()

        calls.added shouldBe listOf(rows[0])
    }

    @Test
    fun `S044 RF-9 sin resultados explica las causas, remite a la direccion a mano y deja reintentar`() {
        val calls = Calls()
        show(DiscoveryUiState(phase = DiscoveryPhase.Empty), calls)

        compose.onNodeWithTag(DISCOVERY_EMPTY_TAG).assertTextContains(text(R.string.experimental_discovery_empty), substring = true)
        compose.onNodeWithTag(
            DISCOVERY_CAUSES_TAG,
        ).assertTextContains(text(R.string.experimental_discovery_cause_network), substring = true)
        compose.onNodeWithTag(
            DISCOVERY_CAUSES_TAG,
        ).assertTextContains(text(R.string.experimental_discovery_cause_blocked), substring = true)
        compose.onNodeWithTag(DISCOVERY_CAUSES_TAG).assertTextContains(text(R.string.experimental_discovery_cause_off), substring = true)
        compose.onNodeWithTag(DISCOVERY_MANUAL_TAG).assertTextContains(text(R.string.experimental_discovery_manual))
        compose.onNodeWithTag(DISCOVERY_RETRY_TAG).performClick()

        calls.retry shouldBe 1
    }

    @Test
    fun `S044 RF-7 con el permiso por pedir se explica y se puede pedir`() {
        val calls = Calls()
        show(DiscoveryUiState(permission = DiscoveryPermission.NeedsRequest), calls)

        compose.onNodeWithTag(
            DISCOVERY_PERMISSION_TAG,
        ).assertTextContains(text(R.string.experimental_discovery_permission), substring = true)
        compose.onNodeWithTag(DISCOVERY_PERMISSION_BUTTON_TAG).performClick()

        calls.requestPermission shouldBe 1
    }

    @Test
    fun `S044 RF-7 con el permiso denegado se explica y se ofrecen los ajustes y reintentar`() {
        val calls = Calls()
        show(DiscoveryUiState(permission = DiscoveryPermission.Denied), calls)

        compose.onNodeWithTag(DISCOVERY_DENIED_TAG).assertTextContains(text(R.string.experimental_discovery_denied), substring = true)
        compose.onNodeWithTag(DISCOVERY_SETTINGS_TAG).performClick()
        compose.onNodeWithTag(DISCOVERY_RETRY_TAG).performClick()

        calls.openSettings shouldBe 1
        calls.retry shouldBe 1
    }

    @Test
    fun `S044 RF-8 el formulario de alta propone nombre, direccion y puerto y se pueden editar`() {
        val calls = Calls()
        show(DiscoveryUiState(phase = DiscoveryPhase.Done, found = rows, draft = AddDraft("rs3-a", "192.168.1.50", "9001")), calls)

        compose.onNodeWithTag(ADD_NAME_TAG).assertTextContains("rs3-a", substring = true)
        compose.onNodeWithTag(ADD_HOST_TAG).assertTextContains("192.168.1.50", substring = true)
        compose.onNodeWithTag(ADD_PORT_TAG).assertTextContains("9001", substring = true)

        compose.onNodeWithTag(ADD_PORT_TAG).performTextInput("2")

        calls.drafts.last() shouldBe AddDraft("rs3-a", "192.168.1.50", "29001")
    }

    @Test
    fun `S044 RF-8 confirmar y cancelar el alta se entregan`() {
        val calls = Calls()
        show(DiscoveryUiState(phase = DiscoveryPhase.Done, found = rows, draft = AddDraft("rs3-a", "192.168.1.50", "9001")), calls)

        compose.onNodeWithTag(ADD_CONFIRM_TAG).performClick()
        compose.onNodeWithTag(ADD_CANCEL_TAG).performClick()

        calls.confirm shouldBe 1
        calls.cancel shouldBe 1
    }

    @Test
    fun `S044 RF-8 unos datos invalidos se avisan en el formulario`() {
        show(DiscoveryUiState(phase = DiscoveryPhase.Done, found = rows, draft = AddDraft("x", "192.168.1.50", "abc", invalid = true)))

        compose.onNodeWithTag(ADD_INVALID_TAG).assertTextContains(text(R.string.experimental_discovery_add_invalid))
    }

    @Test
    fun `S044 RF-8 tras dar de alta se avisa con el nombre`() {
        show(DiscoveryUiState(phase = DiscoveryPhase.Done, found = rows, addedName = "rs3-a"))

        compose.onNodeWithTag(
            DISCOVERY_ADDED_MESSAGE_TAG,
        ).performScrollTo().assertTextContains(text(R.string.experimental_discovery_added, "rs3-a"))
    }

    @Test
    fun `S044 RF-7 la lista de receptores lleva Buscar receptores solo con el modulo activo y abre la busqueda`() {
        var opened: Any? = null
        TestPreferences(folder.root, active = true).use { on ->
            val feature = ExperimentalFeatureImpl(on.preferences, idleSession(kotlinx.coroutines.test.TestScope().backgroundScope)) { 1L }
            compose.setContent {
                CompositionLocalProvider(LocalExperimentalNavigation provides { opened = it }) { feature.ReceiverListSlot() }
            }

            compose.waitUntil(5_000) { compose.onAllNodesWithTag(EXPERIMENTAL_SEARCH_TAG).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag(EXPERIMENTAL_SEARCH_TAG).assertTextContains(text(R.string.experimental_discovery_title), substring = true)
            compose.onNodeWithTag(EXPERIMENTAL_SEARCH_TAG).performClick()

            opened shouldBe ExperimentalDiscoveryDestination
        }
    }

    @Test
    fun `S044 RF-7 con el modulo apagado la lista no lleva Buscar receptores`() {
        TestPreferences(folder.root, active = false).use { off ->
            val feature = ExperimentalFeatureImpl(off.preferences, idleSession(kotlinx.coroutines.test.TestScope().backgroundScope)) { 1L }
            compose.setContent { feature.ReceiverListSlot() }
            compose.waitForIdle()

            compose.onAllNodesWithTag(EXPERIMENTAL_SEARCH_TAG).assertCountEquals(0)
        }
    }
}
