package es.topophone.app.experimental

import androidx.compose.runtime.Composable
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.core.model.guide.EquipmentProfile
import es.topophone.core.model.preferences.ThemeState
import es.topophone.receiver.experimental.AntennaHeightProposal
import es.topophone.receiver.experimental.DiscoveryPhase
import es.topophone.receiver.experimental.ReachConfigEntry
import es.topophone.receiver.experimental.ReachConfigView
import es.topophone.receiver.experimental.ReachDeviceSettings
import es.topophone.receiver.experimental.ReachEndpoint
import es.topophone.receiver.experimental.ReachIdentity
import es.topophone.receiver.experimental.ReachInfoReading
import es.topophone.receiver.experimental.ReachLiveState
import es.topophone.receiver.experimental.ReachPart
import es.topophone.receiver.experimental.ReachPartId
import es.topophone.receiver.experimental.WriteResult
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * S044 (RNF-T7, RNF-T8): todo control de las pantallas del módulo tiene etiqueta y mide 56 dp como mínimo, en cada estado de cada pantalla.
 * Los estados se distinguen por texto (los tests de cada pantalla lo comprueban), no solo por color. Interfaz en español (es-ES).
 */
@RunWith(AndroidJUnit4::class)
class ExperimentalAccessibilityTest {

    @get:Rule
    val compose = createComposeRule()

    private val ready = ReachSource.Ready("wifi:a", ReachEndpoint.parse("192.168.42.1")!!)
    private val reading = ReachInfoReading(
        ReachPart.Ok(ReachIdentity("Modelo", "SERIE1", "v1")),
        ReachPart.Ok(ReachDeviceSettings("rover", 1.8)),
    )
    private val incompatible = ReachInfoReading(
        ReachPart.Incompatible(ReachPartId.IDENTITY, "x"),
        ReachPart.Incompatible(ReachPartId.DEVICE, "x"),
    )
    private val live = ReachLiveState(connected = true, batteryPercent = 50, logsWriting = true)

    /** Todo lo que se puede pulsar, escribir o conmutar. */
    private val controls: SemanticsMatcher = hasClickAction() or isToggleable() or hasSetTextAction()

    private fun label(node: androidx.compose.ui.semantics.SemanticsConfiguration): String {
        val text = node.getOrNull(SemanticsProperties.Text)?.joinToString(" ") { it.text }.orEmpty()
        val description = node.getOrNull(SemanticsProperties.ContentDescription)?.joinToString(" ").orEmpty()
        val editable = node.getOrNull(SemanticsProperties.EditableText)?.text.orEmpty()
        return "$text $description $editable".trim()
    }

    /** Pinta [content] y comprueba cada control: ≥ 56 dp y con texto o descripción. Devuelve cuántos controles había. */
    private fun check(content: @Composable () -> Unit): Int {
        compose.setContent(content)
        compose.waitForIdle()
        val nodes = compose.onAllNodes(controls)
        val count = nodes.fetchSemanticsNodes().size
        repeat(count) { index ->
            nodes[index].assertHeightIsAtLeast(56.dp)
            val description = label(nodes[index].fetchSemanticsNode().config)
            (description.isNotBlank()) shouldBe true
        }
        return count
    }

    private fun panel(state: PanelUiState) = check {
        ExperimentalPanelScreen(state, PanelActions(), ThemeState(), {}, {})
    }

    @Test
    fun `S044 RNF-T8 el panel con la informacion leida y sus acciones`() {
        (panel(PanelUiState(source = ready, info = ReachInfoState.Loaded(reading), live = live)) >= 5) shouldBe true
    }

    @Test
    fun `S044 RNF-T8 el panel con la informacion incompatible y la propuesta de perfil abierta`() {
        val state = PanelUiState(
            source = ready,
            info = ReachInfoState.Loaded(incompatible),
            profileProposal = EquipmentProfile("Modelo", "SERIE1", "v1"),
            profileSaved = true,
        )
        (panel(state) >= 3) shouldBe true
    }

    @Test
    fun `S044 RNF-T8 el panel pidiendo la direccion del receptor`() {
        (panel(PanelUiState(source = ReachSource.NeedsAddress("wifi:b"), addressRejected = true)) >= 2) shouldBe true
    }

    @Test
    fun `S044 RNF-T8 la configuracion del receptor`() {
        val view = ReachConfigView(
            listOf(ReachConfigEntry("positioning_settings.snr_mask", "35", false)),
            listOf(ReachConfigEntry("position_output.output1.token", "", true)),
        )
        (check { ReceiverConfigScreen(ConfigUiState.Loaded(ReachPart.Ok(view)), {}, ThemeState(), {}, {}) } >= 1) shouldBe true
    }

    @Test
    fun `S044 RNF-T8 la configuracion incompatible`() {
        val state = ConfigUiState.Loaded(ReachPart.Incompatible(ReachPartId.SETTINGS, "x"))
        (check { ReceiverConfigScreen(state, {}, ThemeState(), {}, {}) } >= 1) shouldBe true
    }

    private val antenna = AntennaUiState(
        source = ready,
        currentM = 1.8,
        canSend = true,
        proposal = AntennaHeightProposal(0.0, warnDoubleCount = true),
        text = "0",
        valueM = 0.0,
    )

    @Test
    fun `S044 RNF-T8 enviar altura de antena con su aviso y su resultado`() {
        val state = antenna.copy(result = WriteResult.NotConfirmed(read = 1.6, requestFailed = false))
        (check { AntennaHeightScreen(state, AntennaActions(), ThemeState(), {}, {}) } >= 2) shouldBe true
    }

    @Test
    fun `S044 RNF-T8 enviar altura de antena con la confirmacion abierta`() {
        (check { AntennaHeightScreen(antenna.copy(confirming = true), AntennaActions(), ThemeState(), {}, {}) } >= 4) shouldBe true
    }

    @Test
    fun `S044 RNF-T8 logs con los logs escribiendo y la confirmacion de parar`() {
        val state = LogsUiState(live = live, canWrite = true, action = LogsAction.STOP, confirming = LogsAction.STOP)
        (check { LogsScreen(state, LogsActions(), ThemeState(), {}, {}) } >= 3) shouldBe true
    }

    @Test
    fun `S044 RNF-T8 logs sin directo`() {
        (check { LogsScreen(LogsUiState(), LogsActions(), ThemeState(), {}, {}) } >= 0) shouldBe true
    }

    @Test
    fun `S044 RNF-T8 buscar receptores con resultados y el formulario de alta`() {
        val state = DiscoveryUiState(
            phase = DiscoveryPhase.Done,
            found = listOf(FoundRow("a", "192.168.1.50", false), FoundRow("b", "192.168.1.51", true)),
            draft = AddDraft("a", "192.168.1.50", "9001", invalid = true),
        )
        (check { DiscoveryScreen(state, DiscoveryActions(), ThemeState(), {}, {}) } >= 6) shouldBe true
    }

    @Test
    fun `S044 RNF-T8 buscar receptores sin resultados`() {
        (check { DiscoveryScreen(DiscoveryUiState(phase = DiscoveryPhase.Empty), DiscoveryActions(), ThemeState(), {}, {}) } >= 1) shouldBe
            true
    }

    @Test
    fun `S044 RNF-T8 buscar receptores pidiendo y con el permiso denegado`() {
        (
            check {
                DiscoveryScreen(DiscoveryUiState(permission = DiscoveryPermission.NeedsRequest), DiscoveryActions(), ThemeState(), {}, {})
            } >= 1
            )
            .shouldBe(true)
    }

    @Test
    fun `S044 RNF-T8 buscar receptores con el permiso denegado`() {
        (
            check {
                DiscoveryScreen(DiscoveryUiState(permission = DiscoveryPermission.Denied), DiscoveryActions(), ThemeState(), {}, {})
            } >= 2
            )
            .shouldBe(true)
    }

    @Test
    fun `S044 RNF-T8 el interruptor de Ajustes y su aviso legal`() {
        (check { ExperimentalSettingsRow(active = false, onAccept = {}, onDeactivate = {}) } >= 1) shouldBe true
    }
}
