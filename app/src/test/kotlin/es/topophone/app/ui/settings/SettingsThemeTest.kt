package es.topophone.app.ui.settings

import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.app.MainActivity
import es.topophone.app.R
import es.topophone.app.ui.StatefulTestApp
import es.topophone.app.ui.TopLevelDestination
import es.topophone.app.ui.awaitAppReady
import es.topophone.app.ui.awaitTheme
import es.topophone.app.ui.navItem
import es.topophone.app.ui.text
import es.topophone.core.designsystem.theme.EffectiveThemeKey
import es.topophone.core.model.preferences.EffectiveTheme
import es.topophone.core.model.preferences.ThemeMode
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment

private fun hasEffectiveTheme(theme: EffectiveTheme) = SemanticsMatcher.expectValue(EffectiveThemeKey, theme)

private val themeRoot = SemanticsMatcher.keyIsDefined(EffectiveThemeKey)

/** Selector de tema en Ajustes (S001/RF-9, S001/RF-10, S001/RF-15, S001/RF-7). */
@RunWith(AndroidJUnit4::class)
class SettingsThemeTest {

    @get:Rule
    val compose = createComposeRule()

    private fun openThemeDialog() {
        compose.navItem(TopLevelDestination.SETTINGS).performClick()
        compose.onNodeWithTag(THEME_SETTING_TAG).performClick()
    }

    @Test
    fun `S001 RF-9 Ajustes ofrece cuatro temas con Sistema seleccionado por defecto`() {
        compose.setContent { StatefulTestApp() }

        compose.navItem(TopLevelDestination.SETTINGS).performClick()
        compose.onNodeWithTag(THEME_SETTING_TAG).assert(hasText(text(R.string.theme_system)))

        compose.onNodeWithTag(THEME_SETTING_TAG).performClick()
        ThemeMode.entries.forEach { mode -> compose.onNodeWithTag(themeOptionTag(mode)).assertIsDisplayed() }
        compose.onNodeWithTag(themeOptionTag(ThemeMode.SYSTEM)).assertIsSelected()
    }

    @Test
    fun `S001 RF-9 elegir Oscuro lo aplica y lo muestra como tema actual`() {
        compose.setContent { StatefulTestApp() }
        openThemeDialog()

        compose.onNodeWithTag(themeOptionTag(ThemeMode.DARK)).performClick()

        compose.onNode(themeRoot).assert(hasEffectiveTheme(EffectiveTheme.DARK))
        compose.onNodeWithTag(THEME_SETTING_TAG).assert(hasText(text(R.string.theme_dark)))
    }

    @Test
    fun `S001 RF-9 elegir Sol desde Ajustes aplica el tema Sol`() {
        compose.setContent { StatefulTestApp() }
        openThemeDialog()

        compose.onNodeWithTag(themeOptionTag(ThemeMode.SUN)).performClick()

        compose.onNode(themeRoot).assert(hasEffectiveTheme(EffectiveTheme.SUN))
    }

    @Test
    fun `S001 RF-10 con el tema Sistema se sigue el modo oscuro del dispositivo`() {
        compose.setContent { StatefulTestApp(systemInDarkTheme = true) }

        compose.onNode(themeRoot).assert(hasEffectiveTheme(EffectiveTheme.DARK))
    }
}

/** Integración con la actividad real: modo oscuro del sistema, persistencia y diálogos al girar. */
@RunWith(AndroidJUnit4::class)
class SettingsThemeActivityTest {

    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun `S001 RF-10 si el dispositivo cambia a modo oscuro con la app abierta, el tema Sistema cambia`() {
        compose.awaitTheme(EffectiveTheme.LIGHT)

        RuntimeEnvironment.setQualifiers("+night")
        compose.activityRule.scenario.recreate()

        compose.awaitTheme(EffectiveTheme.DARK)

        RuntimeEnvironment.setQualifiers("+notnight")
        compose.activityRule.scenario.recreate()

        compose.awaitTheme(EffectiveTheme.LIGHT)
    }

    @Test
    fun `S001 RF-15 el tema elegido se aplica de nuevo al volver a abrir la app`() {
        compose.awaitAppReady()
        compose.navItem(TopLevelDestination.SETTINGS).performClick()
        compose.onNodeWithTag(THEME_SETTING_TAG).performClick()
        compose.onNodeWithTag(themeOptionTag(ThemeMode.DARK)).performClick()
        compose.awaitTheme(EffectiveTheme.DARK) // la escritura en DataStore es asíncrona

        // Se cierra la actividad y se abre otra nueva: el estado sale del almacenamiento, no de memoria.
        compose.activityRule.scenario.close()
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.awaitTheme(EffectiveTheme.DARK)
            compose.onNode(themeRoot).assert(hasEffectiveTheme(EffectiveTheme.DARK))
        }
    }

    @Test
    fun `S001 RF-7 al girar el dispositivo el dialogo de tema sigue abierto`() {
        compose.awaitAppReady()
        compose.navItem(TopLevelDestination.SETTINGS).performClick()
        compose.onNodeWithTag(THEME_SETTING_TAG).performClick()
        compose.onNodeWithTag(themeOptionTag(ThemeMode.LIGHT)).assertIsDisplayed()

        RuntimeEnvironment.setQualifiers("+land")
        compose.activityRule.scenario.recreate()
        compose.awaitAppReady()

        compose.onNodeWithTag(themeOptionTag(ThemeMode.LIGHT)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.settings_theme_dialog_title)).assertIsDisplayed()
    }
}
