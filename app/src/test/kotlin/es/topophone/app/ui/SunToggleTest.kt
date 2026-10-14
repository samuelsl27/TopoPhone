package es.topophone.app.ui

import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.app.MainActivity
import es.topophone.core.designsystem.R as DesignR
import es.topophone.core.designsystem.component.SUN_TOGGLE_TAG
import es.topophone.core.designsystem.theme.EffectiveThemeKey
import es.topophone.core.model.preferences.EffectiveTheme
import es.topophone.core.model.preferences.ThemeMode
import es.topophone.core.model.preferences.ThemeState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

private val themeRoot = SemanticsMatcher.keyIsDefined(EffectiveThemeKey)

private fun hasTheme(theme: EffectiveTheme) = SemanticsMatcher.expectValue(EffectiveThemeKey, theme)

/** Acceso rápido al modo sol (S001/RF-11, S001/RF-12). */
@RunWith(AndroidJUnit4::class)
class SunToggleTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `S001 RF-11 el acceso rapido al modo sol esta en las tres secciones`() {
        compose.setContent { StatefulTestApp() }

        TopLevelDestination.entries.forEach { destination ->
            compose.navItem(destination).performClick()
            compose.onNodeWithTag(SUN_TOGGLE_TAG).assertIsDisplayed()
        }
    }

    @Test
    fun `S001 RF-12 el acceso rapido alterna entre Sol y el tema que estaba activo`() {
        compose.setContent { StatefulTestApp(initialTheme = ThemeState.DEFAULT.select(ThemeMode.DARK)) }
        compose.onNode(themeRoot).assert(hasTheme(EffectiveTheme.DARK))

        compose.onNodeWithTag(SUN_TOGGLE_TAG).performClick()
        compose.onNode(themeRoot).assert(hasTheme(EffectiveTheme.SUN))

        compose.onNodeWithTag(SUN_TOGGLE_TAG).performClick()
        compose.onNode(themeRoot).assert(hasTheme(EffectiveTheme.DARK))
    }

    @Test
    fun `S001 RF-12 el acceso rapido funciona igual desde cualquier seccion`() {
        compose.setContent { StatefulTestApp() }

        compose.navItem(TopLevelDestination.RECEIVER).performClick()
        compose.onNodeWithTag(SUN_TOGGLE_TAG).performClick()
        compose.onNode(themeRoot).assert(hasTheme(EffectiveTheme.SUN))

        compose.navItem(TopLevelDestination.SETTINGS).performClick()
        compose.onNodeWithTag(SUN_TOGGLE_TAG).performClick()
        compose.onNode(themeRoot).assert(hasTheme(EffectiveTheme.LIGHT))
    }

    @Test
    fun `S001 RF-11 el acceso rapido dice si activa o desactiva el modo sol`() {
        compose.setContent { StatefulTestApp() }

        compose.onNodeWithTag(SUN_TOGGLE_TAG).assert(hasContentDescription(text(DesignR.string.ds_sun_mode_on)))
        compose.onNodeWithTag(SUN_TOGGLE_TAG).performClick()
        compose.onNodeWithTag(SUN_TOGGLE_TAG).assert(hasContentDescription(text(DesignR.string.ds_sun_mode_off)))
    }
}

/** Modo sol en la app real: pasa por el ViewModel y el almacenamiento (S001/RF-12). */
@RunWith(AndroidJUnit4::class)
class SunToggleActivityTest {

    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun `S001 RF-12 en la app real el acceso rapido activa y desactiva el modo sol`() {
        compose.awaitTheme(EffectiveTheme.LIGHT)

        compose.onNodeWithTag(SUN_TOGGLE_TAG).performClick()
        compose.awaitTheme(EffectiveTheme.SUN)

        compose.onNodeWithTag(SUN_TOGGLE_TAG).performClick()
        compose.awaitTheme(EffectiveTheme.LIGHT)
    }
}
