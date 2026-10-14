package es.topophone.app.ui.settings

import android.os.Build
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.core.os.LocaleListCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.app.MainActivity
import es.topophone.app.ui.TopLevelDestination
import es.topophone.app.ui.awaitAppReady
import es.topophone.app.ui.navItem
import es.topophone.core.model.preferences.AppLanguage
import io.kotest.matchers.shouldBe
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Idioma de la interfaz (S001/RF-16, S001/RF-17, S001/RF-18).
 * Se prueba en Android 9 (AppCompat aplica el idioma) y en Android 16 (lo aplica el sistema).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [28, 36])
class LanguageTest {

    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    @After
    fun resetLanguage() {
        compose.runOnUiThread { AppCompatDelegate.setApplicationLocales(LocaleListCompat.getEmptyLocaleList()) }
    }

    /**
     * Elige un idioma y comprueba que la app lo pide a AppCompat. En Android 13+ es el sistema quien
     * aplica el cambio recreando la actividad; Robolectric no lo emula, así que se simula aquí.
     */
    private fun chooseLanguage(language: AppLanguage) {
        compose.onNodeWithTag(languageOptionTag(language)).performClick()
        compose.waitForIdle()
        AppCompatDelegate.getApplicationLocales().toLanguageTags() shouldBe language.languageTags
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            RuntimeEnvironment.setQualifiers("+${language.languageTags}")
            compose.activityRule.scenario.recreate()
        }
        compose.awaitAppReady()
    }

    private fun openLanguageDialog() {
        compose.awaitAppReady()
        compose.navItem(TopLevelDestination.SETTINGS).performClick()
        compose.onNodeWithTag(LANGUAGE_SETTING_TAG).performClick()
    }

    @Test
    fun `S001 RF-16 Ajustes ofrece Sistema, Espanol e English con Sistema por defecto`() {
        openLanguageDialog()

        AppLanguage.entries.forEach { compose.onNodeWithTag(languageOptionTag(it)).assertIsDisplayed() }
        compose.onNodeWithTag(languageOptionTag(AppLanguage.SYSTEM)).assertIsSelected()
        compose.onNodeWithTag(languageOptionTag(AppLanguage.SPANISH)).assert(hasText("Español"))
        compose.onNodeWithTag(languageOptionTag(AppLanguage.ENGLISH)).assert(hasText("English"))
    }

    @Test
    fun `S001 RF-18 al elegir English toda la interfaz cambia a ingles sin salir de Ajustes`() {
        openLanguageDialog()

        chooseLanguage(AppLanguage.ENGLISH)

        compose.navItem(TopLevelDestination.SETTINGS).assertIsSelected().assert(hasText("Settings"))
        compose.navItem(TopLevelDestination.SURVEY).assert(hasText("Survey"))
        compose.onNodeWithTag(LANGUAGE_SETTING_TAG).assert(hasText("English"))
    }

    @Test
    fun `S001 RF-18 se puede volver al espanol desde el ingles`() {
        openLanguageDialog()
        chooseLanguage(AppLanguage.ENGLISH)

        compose.onNodeWithTag(LANGUAGE_SETTING_TAG).performClick()
        chooseLanguage(AppLanguage.SPANISH)

        compose.navItem(TopLevelDestination.SETTINGS).assertIsSelected().assert(hasText("Ajustes"))
    }
}

/** Idioma del dispositivo no soportado: la interfaz sale en español (S001/RF-17). */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "fr-rFR")
class UnsupportedDeviceLanguageTest {

    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun `S001 RF-17 con el dispositivo en frances la interfaz sale en espanol`() {
        compose.awaitAppReady()

        compose.navItem(TopLevelDestination.SURVEY).assert(hasText("Levantamiento"))
        compose.navItem(TopLevelDestination.SETTINGS).assert(hasText("Ajustes"))
    }
}
