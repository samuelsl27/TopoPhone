package es.topophone.app.ui.receiver

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.app.R
import es.topophone.app.ui.text
import es.topophone.core.designsystem.theme.TopoPhoneTheme
import es.topophone.core.model.point.PoleKind
import es.topophone.core.model.preferences.EffectiveTheme
import es.topophone.core.survey.GearResolver
import es.topophone.core.survey.SavedPole
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** El selector rápido del jalón activo del recolector, del replanteo y de las coordenadas (S046/RF-14, RF-15, RF-22, RF-26, RF-27). */
@RunWith(AndroidJUnit4::class)
class GearSelectorTest {

    @get:Rule
    val compose = createComposeRule()

    private val standard = SavedPole("p1", "Jalón estándar", PoleKind.VERTICAL, 1.8)
    private val short = SavedPole("p2", "Jalón corto", PoleKind.VERTICAL, 1.2)
    private val poles = listOf(standard, short)

    private fun ui(
        locked: Boolean = false,
        compensates: Boolean = false,
        changed: Boolean = false,
        poles: List<SavedPole> = this.poles,
        activeId: String? = null,
        profileRef: String? = null,
    ) = GearSelectorUi(GearResolver.resolve(profileRef, emptyList(), activeId, poles), poles, locked, compensates, changed)

    private fun show(ui: GearSelectorUi, onSelect: (String) -> Unit = {}) {
        compose.setContent { TopoPhoneTheme(EffectiveTheme.LIGHT) { GearSelector(ui, onSelect) } }
    }

    @Test
    fun `S046 RF-15 el selector muestra el jalon activo con su altura vertical y la de antena`() {
        show(ui())

        compose.onNodeWithTag(GEAR_SELECTOR_TAG).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.gear_selector_summary, "Jalón estándar", "1,800 m", "1,934 m")).assertIsDisplayed()
    }

    @Test
    fun `S046 RF-14 cambiar de jalon son dos toques`() {
        val chosen = mutableListOf<String>()
        show(ui(), onSelect = { chosen += it })

        compose.onNodeWithTag(GEAR_SELECTOR_TAG).performClick()
        compose.onNodeWithTag(gearSelectorItemTag("p2")).performClick()

        chosen shouldBe listOf("p2")
    }

    @Test
    fun `S046 RF-27 con una medida en curso el selector no se abre y lo dice`() {
        val chosen = mutableListOf<String>()
        show(ui(locked = true), onSelect = { chosen += it })

        compose.onNodeWithTag(GEAR_SELECTOR_TAG).assertIsNotEnabled()
        compose.onNodeWithTag(GEAR_SELECTOR_LOCKED_TAG).assertIsDisplayed()
        compose.onNodeWithTag(GEAR_SELECTOR_TAG).performClick()
        compose.onNodeWithTag(gearSelectorItemTag("p2")).assertDoesNotExist()
        chosen.shouldBeEmpty()
    }

    @Test
    fun `S046 RF-22 con el receptor compensando el selector avisa de que la altura real es la del receptor`() {
        show(ui(compensates = true))

        compose.onNodeWithTag(GEAR_SELECTOR_COMPENSATED_TAG).assertIsDisplayed()
    }

    @Test
    fun `S046 RF-26 tras un cambio de equipo avisa de que no afecta a los puntos ya guardados`() {
        show(ui(changed = true))

        compose.onNodeWithTag(GEAR_SELECTOR_NOTICE_TAG).assertIsDisplayed()
    }

    @Test
    fun `S046 RF-28 sin jalon valido el selector lo indica y da el problema`() {
        val slant = SavedPole("p3", "Inclinado", PoleKind.SLANT, 1.95)
        show(ui(poles = listOf(slant), activeId = "p3"))

        compose.onNodeWithText(text(R.string.gear_selector_none)).assertIsDisplayed()
        compose.onNodeWithTag(GEAR_SELECTOR_PROBLEM_TAG).assertIsDisplayed()
        compose.onNodeWithTag(GEAR_SELECTOR_COMPENSATED_TAG).assertDoesNotExist()
    }

    @Test
    fun `S046 RNF-T8 el selector mide 56 dp o mas y TalkBack lee el jalon y la altura de antena`() {
        show(ui())

        val node = compose.onNodeWithTag(GEAR_SELECTOR_TAG)
        val bounds = node.getBoundsInRoot()
        ((bounds.bottom - bounds.top) >= 56.dp) shouldBe true
        node.fetchSemanticsNode().config[SemanticsProperties.ContentDescription].single() shouldBe
            text(R.string.gear_selector_description, "Jalón estándar", "1,934 m")
    }
}
