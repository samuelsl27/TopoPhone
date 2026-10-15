package es.topophone.app.ui

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.text.TextLayoutResult
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.app.R
import es.topophone.app.ui.receiver.CONNECTION_STATUS_TAG
import es.topophone.app.ui.settings.ABOUT_DISCLAIMER_TAG
import es.topophone.app.ui.settings.ABOUT_SETTING_TAG
import es.topophone.app.ui.settings.LANGUAGE_SETTING_TAG
import es.topophone.app.ui.settings.LICENSES_SETTING_TAG
import es.topophone.app.ui.settings.THEME_SETTING_TAG
import es.topophone.app.ui.settings.themeOptionTag
import es.topophone.core.designsystem.component.MinTouchTarget
import es.topophone.core.designsystem.component.NAVIGATE_BACK_TAG
import es.topophone.core.designsystem.component.SUN_TOGGLE_TAG
import es.topophone.core.model.preferences.ThemeMode
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Requisitos no funcionales de diseño de la spec 001 en el móvil más pequeño previsto:
 * 360 × 640 dp en vertical y horizontal, fuente del sistema al 150 % sin recortes y al 200 %
 * con todo accesible desplazando; zonas táctiles ≥ 56 dp (RNF-T8).
 */
abstract class LayoutRnfTestBase {

    @get:Rule
    val compose = createComposeRule()

    private fun SemanticsNodeInteraction.assertTextNotClipped(): SemanticsNodeInteraction {
        val results = mutableListOf<TextLayoutResult>()
        fetchSemanticsNode().config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(results)
        results.forEach { it.hasVisualOverflow shouldBe false }
        return this
    }

    private fun start(fontScale: Float) {
        RuntimeEnvironment.setFontScale(fontScale)
        compose.setContent { StatefulTestApp() }
    }

    private fun assertNavigationFitsAndIsLargeEnough() {
        TopLevelDestination.entries.forEach { destination ->
            compose.navItem(destination).assertIsDisplayed().assertHeightIsAtLeast(MinTouchTarget).assertWidthIsAtLeast(MinTouchTarget)
            compose.onNode(hasText(text(destination.label)) and hasAnyAncestor(hasTestTag(destination.testTag)), useUnmergedTree = true)
                .assertTextNotClipped()
        }
        compose.onNodeWithTag(SUN_TOGGLE_TAG).assertIsDisplayed().assertHeightIsAtLeast(MinTouchTarget).assertWidthIsAtLeast(MinTouchTarget)
    }

    private fun assertEverySettingIsReachableAndLargeEnough() {
        compose.navItem(TopLevelDestination.SETTINGS).performClick()
        listOf(THEME_SETTING_TAG, LANGUAGE_SETTING_TAG, ABOUT_SETTING_TAG, LICENSES_SETTING_TAG).forEach { tag ->
            compose.onNodeWithTag(tag).performScrollTo().assertIsDisplayed().assertHeightIsAtLeast(MinTouchTarget)
        }
        compose.onNodeWithTag(THEME_SETTING_TAG).performScrollTo().performClick()
        ThemeMode.entries.forEach { mode ->
            compose.onNodeWithTag(themeOptionTag(mode)).performScrollTo().assertIsDisplayed().assertHeightIsAtLeast(MinTouchTarget)
        }
        compose.onNodeWithTag(themeOptionTag(ThemeMode.SYSTEM)).performClick()
    }

    private fun assertAboutIsReachable() {
        compose.onNodeWithTag(ABOUT_SETTING_TAG).performScrollTo().performClick()
        compose.onNodeWithTag(NAVIGATE_BACK_TAG).assertHeightIsAtLeast(MinTouchTarget).assertWidthIsAtLeast(MinTouchTarget)
        compose.onNodeWithTag(ABOUT_DISCLAIMER_TAG).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag(NAVIGATE_BACK_TAG).performClick()
    }

    private fun assertEmptyStatesAreReachable() {
        compose.navItem(TopLevelDestination.SURVEY).performClick()
        compose.onNodeWithText(text(R.string.survey_empty_description)).performScrollTo().assertIsDisplayed()
        compose.navItem(TopLevelDestination.RECEIVER).performClick()
        compose.onNodeWithTag(CONNECTION_STATUS_TAG).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `RNF fuente al 150 por ciento sin recortes en la navegacion y zonas tactiles de 56 dp`() {
        start(fontScale = 1.5f)

        assertNavigationFitsAndIsLargeEnough()
        assertEmptyStatesAreReachable()
        assertEverySettingIsReachableAndLargeEnough()
        assertAboutIsReachable()
    }

    @Test
    fun `RNF fuente al 200 por ciento con todo el contenido accesible desplazando`() {
        start(fontScale = 2.0f)

        TopLevelDestination.entries.forEach { compose.navItem(it).assertIsDisplayed() }
        assertEmptyStatesAreReachable()
        assertEverySettingIsReachableAndLargeEnough()
        assertAboutIsReachable()
        compose.onAllNodesWithText(text(R.string.nav_settings)).fetchSemanticsNodes().isNotEmpty() shouldBe true
    }
}

// Gráficos nativos: miden el texto de verdad, imprescindible para detectar recortes.
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "es-rES-w360dp-h640dp-port")
class LayoutRnfPortraitTest : LayoutRnfTestBase()

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "es-rES-w640dp-h360dp-land")
class LayoutRnfLandscapeTest : LayoutRnfTestBase()
