package es.topophone.app.ui.guide

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
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
import es.topophone.app.ui.equipment.EquipmentActions
import es.topophone.app.ui.equipment.EquipmentCard
import es.topophone.app.ui.equipment.EquipmentUiState
import es.topophone.app.ui.web.ReceiverWebActions
import es.topophone.app.ui.web.ReceiverWebScreen
import es.topophone.app.ui.web.ReceiverWebUiState
import es.topophone.app.ui.web.WebFailureCause
import es.topophone.app.ui.web.WebPhase
import es.topophone.core.designsystem.theme.TopoPhoneTheme
import es.topophone.core.model.guide.CheckCause
import es.topophone.core.model.guide.CheckId
import es.topophone.core.model.guide.CheckStatus
import es.topophone.core.model.guide.ConfigListId
import es.topophone.core.model.guide.ReceiverCheck
import es.topophone.core.model.preferences.EffectiveTheme
import es.topophone.core.model.preferences.ThemeState
import es.topophone.core.survey.AntennaCatalog
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import java.io.File
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * La Guía, las comprobaciones, la vista web y «Datos del receptor» cumplen RNF-T7, RNF-T8 y RNF-T14: todo control tiene etiqueta y mide
 * al menos 56 dp, cada estado se distingue por texto y símbolo y no solo por el color, en inglés salen textos en inglés, las claves de
 * es y en son las mismas, ningún texto procede del fabricante y ninguna cadena de usuario está en el código (S043).
 */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "es-rES")
class GuideAccessibilityTest {

    @get:Rule
    val compose = createComposeRule()

    private val real = GuideUiState(applies = true, receiverKey = "AA:BB", canCheck = true)

    private val results = listOf(
        ReceiverCheck(CheckId.GGA_RATE, CheckStatus.OK, measured = "5,0 Hz"),
        ReceiverCheck(CheckId.SENTENCES, CheckStatus.WARNING, "GGA, GST", listOf(CheckCause.MISSING_GSV), "ntrip_messages"),
        ReceiverCheck(CheckId.SOLUTION, CheckStatus.FAIL, "FLOAT", listOf(CheckCause.SOLUTION_NOT_FIX), "ntrip_fix"),
        ReceiverCheck(CheckId.UTC_TIME, CheckStatus.NO_DATA, null, listOf(CheckCause.NO_DATA), "ntrip_time"),
    )

    private fun show(content: @Composable () -> Unit) {
        compose.setContent {
            TopoPhoneTheme(EffectiveTheme.LIGHT) { Column(modifier = Modifier.verticalScroll(rememberScrollState())) { content() } }
        }
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

    private fun openNtrip() {
        compose.onNodeWithTag(guideListTag(ConfigListId.NTRIP_ROVER)).performScrollTo().performClick()
    }

    // ------------------------------------------------------------------------------------------ RNF-T8
    @Test
    fun `S043 RNF-T8 la lista de listas tiene etiquetas y 56 dp`() {
        show { GuideCard(real, GuideActions()) }

        assertControls("listas")
    }

    @Test
    fun `S043 RNF-T8 una lista abierta con resultados tiene etiquetas y 56 dp`() {
        show { GuideCard(real.copy(results = results, checkedList = ConfigListId.NTRIP_ROVER), GuideActions()) }
        openNtrip()

        assertControls("lista abierta")
    }

    @Test
    fun `S043 RNF-T8 la confirmacion de reiniciar tiene etiquetas y 56 dp`() {
        show { GuideCard(real, GuideActions()) }
        openNtrip()
        compose.onNodeWithTag(GUIDE_RESET_TAG).performScrollTo().performClick()

        assertControls("reiniciar")
    }

    @Test
    fun `S043 RNF-T8 Comprobar con el receptor virtual tiene etiquetas y 56 dp`() {
        show { GuideCard(GuideUiState(applies = false, receiverKey = "virtual", canCheck = true, results = results), GuideActions()) }

        assertControls("virtual")
    }

    @Test
    fun `S043 RNF-T8 Datos del receptor tiene etiquetas y 56 dp`() {
        val state = EquipmentUiState(applies = true, receiverKey = "AA:BB", picked = AntennaCatalog.byRef("catalog:RS4"))
        show { EquipmentCard(state, EquipmentActions()) }

        assertControls("datos del receptor")
    }

    @Test
    fun `S043 RNF-T8 pedir la direccion de la vista web tiene etiquetas y 56 dp`() {
        val state = ReceiverWebUiState(phase = WebPhase.ASK_ADDRESS, receiverKey = "AA:BB", addressRejected = true)
        compose.setContent {
            TopoPhoneTheme(EffectiveTheme.LIGHT) { ReceiverWebScreen(ThemeState.DEFAULT, {}, {}, state, ReceiverWebActions()) }
        }

        assertControls("pedir la direccion")
    }

    @Test
    fun `S043 RNF-T8 el fallo de la vista web tiene etiquetas y 56 dp`() {
        val state = ReceiverWebUiState(phase = WebPhase.FAILED, url = "http://192.168.42.1:80/", causes = WebFailureCause.entries)
        compose.setContent {
            TopoPhoneTheme(EffectiveTheme.LIGHT) { ReceiverWebScreen(ThemeState.DEFAULT, {}, {}, state, ReceiverWebActions()) }
        }

        assertControls("fallo web")
    }

    // ------------------------------------------------------------------------------------------ no solo color
    @Test
    fun `S043 RNF-T8 cada estado de una comprobacion se distingue por su texto y su simbolo`() {
        val statuses = CheckStatus.entries

        statuses.map { GuideCheckTexts.icon(it) }.toSet().size shouldBe statuses.size
        statuses.map { GuideCheckTexts.status(it) }.toSet().size shouldBe statuses.size
    }

    @Test
    fun `S043 RNF-T8 en pantalla los cuatro estados salen con simbolo y texto`() {
        show { GuideCard(real.copy(results = results, checkedList = ConfigListId.NTRIP_ROVER), GuideActions()) }
        openNtrip()

        for ((id, shown) in listOf(
            CheckId.GGA_RATE to "✔ Correcto",
            CheckId.SENTENCES to "⚠ Aviso",
            CheckId.SOLUTION to "✖ Fallo",
            CheckId.UTC_TIME to "… Sin datos",
        )) {
            compose.onNodeWithTag(guideCheckStatusTag(id)).performScrollTo().assertIsDisplayed()
            compose.onNodeWithText(shown).performScrollTo().assertIsDisplayed()
        }
    }

    // ------------------------------------------------------------------------------------------ RNF-T7
    @Test
    @Config(qualifiers = "en")
    fun `S043 RNF-T7 en ingles la Guia y las comprobaciones salen en ingles`() {
        show { GuideCard(real.copy(results = results, checkedList = ConfigListId.NTRIP_ROVER), GuideActions()) }
        openNtrip()

        compose.onNodeWithText("Setup guide").assertExists()
        compose.onNodeWithText("Check").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("✔ OK").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("⚠ Warning").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("✖ Failed").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("… No data").performScrollTo().assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "en")
    fun `S043 RNF-T7 en ingles Datos del receptor y la vista web salen en ingles`() {
        show { EquipmentCard(EquipmentUiState(applies = true, receiverKey = "AA:BB"), EquipmentActions()) }

        compose.onNodeWithText("Receiver details").assertExists()
        compose.onNodeWithText("Open web interface").performScrollTo().assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "en")
    fun `S043 RNF-T7 en ingles el fallo de la vista web sale en ingles`() {
        val state = ReceiverWebUiState(phase = WebPhase.FAILED, url = "http://192.168.42.1:80/", causes = WebFailureCause.entries)
        compose.setContent {
            TopoPhoneTheme(EffectiveTheme.LIGHT) { ReceiverWebScreen(ThemeState.DEFAULT, {}, {}, state, ReceiverWebActions()) }
        }

        compose.onNodeWithText("The page did not load").assertIsDisplayed()
        compose.onNodeWithText("Retry").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Open in the browser").performScrollTo().assertIsDisplayed()
    }

    private val prefixes = listOf(
        "guide_",
        "equipment_",
        "web_",
        "point_equipment_",
        "receiver_menu_guide",
        "receiver_menu_equipment",
        "receiver_menu_source_not_applicable",
        "export_column_equipment_",
    )

    private fun strings(path: String): Map<String, String> =
        Regex("""<string name="([a-z_0-9]+)">(.*?)</string>""").findAll(File(path).readText())
            .map { it.groupValues[1] to it.groupValues[2] }.filter { (key, _) -> prefixes.any { key.startsWith(it) } }.toMap()

    @Test
    fun `S043 RNF-T7 los textos de la guia, los datos, la vista web y la ficha estan en es y en con las mismas claves`() {
        val es = strings("src/main/res/values/strings.xml")
        val en = strings("src/main/res/values-en/strings.xml")

        (es.size > 150) shouldBe true
        (es.keys - en.keys).shouldBeEmpty()
        (en.keys - es.keys).shouldBeEmpty()
        es.filterValues { it.isBlank() }.keys.shouldBeEmpty()
        en.filterValues { it.isBlank() }.keys.shouldBeEmpty()
    }

    @Test
    fun `S043 RNF-T14 ningun texto de la guia, los datos ni la vista web procede del fabricante`() {
        val all = strings("src/main/res/values/strings.xml") + strings("src/main/res/values-en/strings.xml")
        val forbidden = listOf("Emlid", "Reach Panel", "Emlid Flow")

        all.filter { (_, text) -> forbidden.any { text.contains(it, ignoreCase = true) } }.keys.shouldBeEmpty()
    }

    @Test
    fun `S043 RNF-T7 ninguna cadena de usuario esta en el codigo de la guia, los datos ni la vista web`() {
        val literal = Regex("""Text\(\s*"|label = "|contentDescription = "|text = "|title = """")
        val files = listOf(
            "src/main/kotlin/es/topophone/app/ui/guide/GuideScreen.kt",
            "src/main/kotlin/es/topophone/app/ui/guide/GuideViewModel.kt",
            "src/main/kotlin/es/topophone/app/ui/equipment/EquipmentScreen.kt",
            "src/main/kotlin/es/topophone/app/ui/equipment/EquipmentViewModel.kt",
            "src/main/kotlin/es/topophone/app/ui/web/ReceiverWebScreen.kt",
            "src/main/kotlin/es/topophone/app/ui/web/ReceiverWebViewModel.kt",
        )

        val offenders = files.map { File(it) }.filter { file ->
            file.readLines().any { line ->
                literal.containsMatchIn(line) && !line.trimStart().startsWith("//") && !line.trimStart().startsWith("*")
            }
        }.map { it.name }

        offenders.shouldBeEmpty()
    }
}
