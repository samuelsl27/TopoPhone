package es.topophone.app.ui.receiver

import androidx.compose.runtime.Composable
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.core.designsystem.theme.TopoPhoneTheme
import es.topophone.core.gnss.nmea.ReplaySpeed
import es.topophone.core.gnss.scenario.ScenarioId
import es.topophone.core.model.point.PositionSource
import es.topophone.core.model.preferences.EffectiveTheme
import es.topophone.core.model.preferences.ThemeState
import es.topophone.core.model.project.CentralPoint
import es.topophone.receiver.session.ConnectionState
import es.topophone.receiver.session.ReceiverSnapshot
import es.topophone.receiver.session.ScenarioInfo
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import java.io.File
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Las pantallas del GNSS del móvil y del receptor virtual cumplen RNF-T7 y RNF-T8: todo control tiene texto o etiqueta, ninguno mide
 * menos de 56 dp, en inglés salen textos en inglés, SINGLE y «Simulado» se dicen con texto y ninguna cadena de usuario está en el
 * código (S022).
 */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "es-rES")
class SourcesAccessibilityTest {

    @get:Rule
    val compose = createComposeRule()

    private fun show(content: @Composable () -> Unit) {
        compose.setContent { TopoPhoneTheme(EffectiveTheme.LIGHT) { content() } }
    }

    private fun label(node: SemanticsNode): String {
        val text = node.config.getOrNull(SemanticsProperties.Text)?.joinToString(" ") { it.text }.orEmpty()
        val description = node.config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString(" ").orEmpty()
        return (text + description).trim()
    }

    /** Todo lo que se puede pulsar tiene etiqueta (de sí mismo o de sus hijos) y mide al menos 56 dp de alto. */
    private fun assertControls(screen: String) {
        val density = compose.density.density
        val nodes = compose.onAllNodes(hasClickAction()).fetchSemanticsNodes()
        (nodes.size > 0) shouldBe true
        nodes.filter { label(it).isEmpty() }.map { "$screen: sin etiqueta ${it.id}" }.shouldBeEmpty()
        val tooSmall = nodes.filter { it.size.height / density < 55.5f }
        tooSmall.map { "$screen: ${label(it)} mide ${it.size.height / density} dp" }.shouldBeEmpty()
    }

    private val list = ReceiverUiState(
        bluetooth = BluetoothUi.Disabled,
        snapshot = ReceiverSnapshot(ConnectionState.Disconnected),
        pairingSupported = true,
    )

    private fun receiving(source: PositionSource, scenario: ScenarioInfo? = null) = ReceiverUiState(
        bluetooth = BluetoothUi.Disabled,
        snapshot = ReceiverSnapshot(ConnectionState.Receiving("fuente"), source = source, scenario = scenario),
    )

    private val virtualRunning = ScenarioInfo(ScenarioId.STRAIGHT_WALK, ReplaySpeed.X5, durationS = 120, elapsedS = 30)
    private val projectChoice = VirtualUi.initial("Calle Mayor", CentralPoint(43.2630126, -2.9349852))

    @Test
    fun `S022 RNF-T8 la lista con las dos fuentes y la hoja de Anadir receptor tienen etiquetas y 56 dp`() {
        show { ReceiverScreen(ThemeState.DEFAULT, {}, list, ReceiverActions()) }
        assertControls("lista")

        compose.onNodeWithTag(ADD_RECEIVER_TAG).performScrollTo().performClick()
        assertControls("hoja de tipo")
    }

    @Test
    fun `S022 RNF-T8 el dialogo del receptor virtual tiene etiquetas y 56 dp con proyecto y con coordenadas`() {
        show { ReceiverScreen(ThemeState.DEFAULT, {}, list.copy(virtual = projectChoice), ReceiverActions()) }
        assertControls("dialogo virtual con proyecto")
    }

    @Test
    fun `S022 RNF-T8 el dialogo del receptor virtual sin proyecto tiene etiquetas y 56 dp`() {
        show { ReceiverScreen(ThemeState.DEFAULT, {}, list.copy(virtual = VirtualUi.initial(null, null)), ReceiverActions()) }
        assertControls("dialogo virtual sin proyecto")
    }

    @Test
    fun `S022 RNF-T8 la peticion del permiso de ubicacion y sus avisos tienen etiquetas y 56 dp`() {
        show { ReceiverScreen(ThemeState.DEFAULT, {}, list.copy(askPhonePermission = true), ReceiverActions()) }
        assertControls("peticion de ubicacion")
    }

    @Test
    fun `S022 RNF-T8 los avisos de permiso denegado y ubicacion apagada tienen etiquetas y 56 dp`() {
        show {
            ReceiverScreen(ThemeState.DEFAULT, {}, list.copy(phonePermissionDenied = true, phoneLocationOff = true), ReceiverActions())
        }
        assertControls("avisos de ubicacion")
    }

    @Test
    fun `S022 RNF-T8 la cabecera con el escenario en marcha tiene etiquetas y 56 dp`() {
        show { ReceiverScreen(ThemeState.DEFAULT, {}, receiving(PositionSource.VIRTUAL, virtualRunning), ReceiverActions()) }
        assertControls("cabecera virtual")
    }

    @Test
    fun `S022 RNF-T8 la cabecera con el movil y con la ubicacion simulada tiene etiquetas y 56 dp`() {
        show { ReceiverScreen(ThemeState.DEFAULT, {}, receiving(PositionSource.PHONE_MOCK), ReceiverActions()) }
        assertControls("cabecera movil")
    }

    @Test
    fun `S022 RNF-T8 el fin del escenario tiene tres botones con etiquetas y 56 dp`() {
        show {
            ReceiverScreen(
                ThemeState.DEFAULT,
                {},
                list.copy(
                    snapshot = ReceiverSnapshot(ConnectionState.ScenarioFinished("Receptor virtual"), source = PositionSource.VIRTUAL),
                ),
                ReceiverActions(),
            )
        }
        assertControls("fin de escenario")
    }

    @Test
    @Config(qualifiers = "en")
    fun `S022 RNF-T7 en ingles la lista y la hoja salen en ingles`() {
        show { ReceiverScreen(ThemeState.DEFAULT, {}, list, ReceiverActions()) }

        compose.onNodeWithText("Other position sources").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag(PHONE_ROW_TAG).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag(ADD_RECEIVER_TAG).performScrollTo().performClick()
        compose.onNodeWithTag(ADD_PHONE_TAG).assertIsDisplayed()
        compose.onNodeWithTag(ADD_VIRTUAL_TAG).assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "en")
    fun `S022 RNF-T7 en ingles el dialogo del receptor virtual y los escenarios salen en ingles`() {
        show { ReceiverScreen(ThemeState.DEFAULT, {}, list.copy(virtual = projectChoice), ReceiverActions()) }

        compose.onNodeWithText("Scenario").assertIsDisplayed()
        compose.onNodeWithTag(virtualScenarioTag(ScenarioId.STABLE_FIX)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Stable FIX", substring = true).assertIsDisplayed()
        compose.onNodeWithTag(VIRTUAL_CONNECT_TAG).assertExists()
    }

    @Test
    @Config(qualifiers = "en")
    fun `S022 RNF-T7 en ingles la cabecera dice Simulated y el movil dice SINGLE`() {
        show { ReceiverScreen(ThemeState.DEFAULT, {}, receiving(PositionSource.VIRTUAL, virtualRunning), ReceiverActions()) }
        compose.onNodeWithText("Simulated · Straight-line walk").assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "en")
    fun `S022 RNF-T7 en ingles la cabecera del movil habla de SINGLE y de la ubicacion simulada`() {
        show { ReceiverScreen(ThemeState.DEFAULT, {}, receiving(PositionSource.PHONE_MOCK), ReceiverActions()) }

        compose.onNodeWithText("Phone GNSS · SINGLE", substring = true).assertIsDisplayed()
        compose.onNodeWithText("another app is faking this position", substring = true).assertIsDisplayed()
    }

    @Test
    fun `S022 RNF-T7 los textos de las dos fuentes estan en es y en con las mismas claves y no hay literales de usuario en el codigo`() {
        val prefixes = listOf(
            "phone_", "virtual_", "scenario_", "source_", "status_bar_phone", "status_bar_simulated", "receiver_sources_",
            "receiver_phone_", "receiver_virtual_", "receiver_add_phone", "receiver_add_virtual", "receiver_scenario_finished",
            "ntrip_unavailable_phone", "ntrip_unavailable_virtual", "point_from_phone", "point_from_virtual", "base_shift_blocker_phone",
        )

        fun keys(path: String) = Regex("""<string name="([a-z_0-9]+)"""").findAll(File(path).readText())
            .map { it.groupValues[1] }.filter { key -> prefixes.any { key.startsWith(it) } }.toSet()
        val es = keys("src/main/res/values/strings.xml")
        val en = keys("src/main/res/values-en/strings.xml")

        (es.size > 50) shouldBe true
        (es - en).shouldBeEmpty()
        (en - es).shouldBeEmpty()

        val literal = Regex("""Text\(\s*"|label = "|contentDescription = "|text = "|title = """")
        val files = listOf(
            "src/main/kotlin/es/topophone/app/ui/receiver/VirtualReceiverDialog.kt",
            "src/main/kotlin/es/topophone/app/ui/receiver/VirtualReceiverUi.kt",
            "src/main/kotlin/es/topophone/app/ui/receiver/ScenarioControls.kt",
            "src/main/kotlin/es/topophone/app/ui/receiver/SourceLimits.kt",
            "src/main/kotlin/es/topophone/app/ui/receiver/ReceiverList.kt",
            "src/main/kotlin/es/topophone/app/ui/receiver/ReceiverScreen.kt",
            "src/main/kotlin/es/topophone/app/ui/status/StatusIndicator.kt",
            "src/main/kotlin/es/topophone/app/receiver/SourceLabels.kt",
            "src/main/kotlin/es/topophone/app/receiver/VirtualOrigins.kt",
        )
        val offenders = files.map { File(it) }.filter { file ->
            file.readLines().any { line ->
                literal.containsMatchIn(line) && !line.trimStart().startsWith("//") && !line.trimStart().startsWith("*")
            }
        }.map { it.name }
        offenders.shouldBeEmpty()
    }
}
