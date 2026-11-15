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
import es.topophone.core.model.preferences.EffectiveTheme
import es.topophone.core.model.preferences.ThemeState
import es.topophone.core.model.receiver.WifiReceiver
import es.topophone.core.model.receiver.WifiReceiverDraft
import es.topophone.core.model.receiver.WifiReceiverProblem
import es.topophone.receiver.session.ConnectionState
import es.topophone.receiver.session.ReceiverSnapshot
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import java.io.File
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Las pantallas del receptor Wi-Fi cumplen RNF-T7 y RNF-T8: todo control tiene texto o etiqueta, ninguno mide menos de 56 dp, «Editar»
 * dice de qué receptor es, en inglés salen textos en inglés y ninguna cadena de usuario está en el código (S021).
 */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "es-rES")
class WifiAccessibilityTest {

    @get:Rule
    val compose = createComposeRule()

    private val hotspot = WifiReceiver("w1", "RS3 hotspot", "192.168.42.1", 9001, null)
    private val casa = WifiReceiver("w2", "RS3 casa", "reach.local", 9001, 9002)

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
        nodes.filter {
            it.size.height / density < 55.5f
        }.map { "$screen: ${label(it)} mide ${it.size.height / density} dp" }.shouldBeEmpty()
    }

    private val list = ReceiverUiState(
        bluetooth = BluetoothUi.Disabled,
        snapshot = ReceiverSnapshot(ConnectionState.Disconnected),
        wifiReceivers = listOf(hotspot, casa),
        lastReceiverAddress = "wifi:w2",
        pairingSupported = true,
    )

    private val editing = WifiReceiverFormUiState(
        draft = WifiReceiverDraft("RS3 campo", "192.168.0.20", "9001", "9002"),
        loaded = true,
        editing = true,
        problems = setOf(WifiReceiverProblem.HOST_INVALID),
    )

    @Test
    fun `S021 RNF-T8 la lista con receptores Wi-Fi tiene etiquetas y 56 dp`() {
        show { ReceiverScreen(ThemeState.DEFAULT, {}, list, ReceiverActions()) }

        assertControls("lista")
    }

    @Test
    fun `S021 RNF-T8 Editar dice de que receptor es`() {
        show { ReceiverScreen(ThemeState.DEFAULT, {}, list, ReceiverActions()) }

        val names = compose.onAllNodes(hasClickAction()).fetchSemanticsNodes().map { label(it) }
        (names.any { it.contains("Editar RS3 hotspot") }) shouldBe true
        (names.any { it.contains("Editar RS3 casa") }) shouldBe true
    }

    @Test
    fun `S021 RNF-T8 la hoja de tipo de receptor tiene etiquetas y 56 dp`() {
        show { ReceiverScreen(ThemeState.DEFAULT, {}, list, ReceiverActions()) }
        compose.onNodeWithTag(ADD_RECEIVER_TAG).performScrollTo().performClick()

        assertControls("hoja de tipo")
    }

    @Test
    fun `S021 RNF-T8 el formulario nuevo y el de edicion tienen etiquetas y 56 dp`() {
        show { WifiReceiverFormScreen(ThemeState.DEFAULT, {}, WifiReceiverFormUiState(loaded = true), WifiReceiverFormActions()) }
        assertControls("formulario nuevo")
    }

    @Test
    fun `S021 RNF-T8 el formulario de edicion con un motivo de rechazo tiene etiquetas y 56 dp`() {
        show { WifiReceiverFormScreen(ThemeState.DEFAULT, {}, editing, WifiReceiverFormActions()) }

        assertControls("formulario de edicion")
    }

    @Test
    fun `S021 RNF-T8 la confirmacion de borrado tiene etiquetas y 56 dp`() {
        show { WifiReceiverFormScreen(ThemeState.DEFAULT, {}, editing.copy(confirmingDelete = true), WifiReceiverFormActions()) }

        assertControls("borrado")
    }

    @Test
    fun `S021 RNF-T8 la peticion y el aviso del permiso de red local tienen etiquetas y 56 dp`() {
        show { ReceiverScreen(ThemeState.DEFAULT, {}, list.copy(askLocalNetwork = true), ReceiverActions()) }
        assertControls("peticion de red local")
    }

    @Test
    fun `S021 RNF-T8 el aviso de permiso denegado tiene etiquetas y 56 dp`() {
        show { ReceiverScreen(ThemeState.DEFAULT, {}, list.copy(localNetworkDenied = true), ReceiverActions()) }

        assertControls("red local denegada")
    }

    @Test
    @Config(qualifiers = "en")
    fun `S021 RNF-T7 en ingles la lista y la hoja salen en ingles`() {
        show { ReceiverScreen(ThemeState.DEFAULT, {}, list, ReceiverActions()) }

        compose.onNodeWithText("Wi-Fi (TCP) receivers").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag(ADD_RECEIVER_TAG).performScrollTo().performClick()
        compose.onNodeWithTag(ADD_WIFI_TAG).assertIsDisplayed()
        compose.onNodeWithText("Cancel").assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "en")
    fun `S021 RNF-T7 en ingles el formulario sale en ingles`() {
        show { WifiReceiverFormScreen(ThemeState.DEFAULT, {}, editing, WifiReceiverFormActions()) }

        compose.onNodeWithText("Edit Wi-Fi receiver").assertIsDisplayed()
        compose.onNodeWithText("Position port").assertExists()
        compose.onNodeWithText("Corrections port (optional)").assertExists()
        compose.onNodeWithText("Delete receiver").performScrollTo().assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "en")
    fun `S021 RNF-T7 en ingles el permiso de red local sale en ingles`() {
        show { ReceiverScreen(ThemeState.DEFAULT, {}, list.copy(askLocalNetwork = true), ReceiverActions()) }

        compose.onNodeWithText("Local network access").assertIsDisplayed()
    }

    @Test
    fun `S021 RNF-T7 los textos del Wi-Fi estan en es y en con las mismas claves y no hay literales de usuario en el codigo`() {
        val prefixes = listOf(
            "wifi_",
            "local_network_",
            "receiver_wifi_",
            "receiver_add_",
            "ntrip_needs_internet",
            "ntrip_no_corrections_port",
            "corrections_link_",
        )

        fun keys(path: String) = Regex("""<string name="([a-z_0-9]+)"""").findAll(File(path).readText())
            .map { it.groupValues[1] }.filter { key -> prefixes.any { key.startsWith(it) } }.toSet()
        val es = keys("src/main/res/values/strings.xml")
        val en = keys("src/main/res/values-en/strings.xml")

        (es.size > 30) shouldBe true
        (es - en).shouldBeEmpty()
        (en - es).shouldBeEmpty()

        val literal = Regex("""Text\(\s*"|label = "|contentDescription = "|text = "|title = """")
        val files = listOf(
            "src/main/kotlin/es/topophone/app/ui/receiver/WifiReceiverFormScreen.kt",
            "src/main/kotlin/es/topophone/app/ui/receiver/ReceiverList.kt",
            "src/main/kotlin/es/topophone/app/ui/receiver/ReceiverScreen.kt",
            "src/main/kotlin/es/topophone/app/receiver/LocalNetworkPermission.kt",
            "src/main/kotlin/es/topophone/app/receiver/NetworkAwareSourcetable.kt",
        )
        val offenders = files.map { File(it) }.filter { file ->
            file.readLines().any { line ->
                literal.containsMatchIn(line) && !line.trimStart().startsWith("//") && !line.trimStart().startsWith("*")
            }
        }.map { it.name }
        offenders.shouldBeEmpty()
    }
}
