package es.topophone.app.ui.settings

import android.net.Uri
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.app.R
import es.topophone.app.ui.StatefulTestApp
import es.topophone.app.ui.TopLevelDestination
import es.topophone.app.ui.navItem
import es.topophone.app.ui.text
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Ayuda "Funcionamiento en segundo plano" en Ajustes (S004/RF-12). Interfaz en español (es-ES). */
@RunWith(AndroidJUnit4::class)
class BackgroundHelpTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private var batterySettings = 0

    private fun openHelp() {
        compose.setContent { StatefulTestApp(onOpenAppBatterySettings = { batterySettings++ }) }
        compose.navItem(TopLevelDestination.SETTINGS).performClick()
        compose.onNodeWithTag(BACKGROUND_HELP_SETTING_TAG).performScrollTo().performClick()
    }

    @Test
    fun `S004 RF-12 Ajustes ofrece la ayuda con los pasos para que no se cierre la app`() {
        openHelp()

        compose.onNodeWithText(text(R.string.background_help_intro)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.background_help_general_steps)).performScrollTo().assertIsDisplayed()
        PhoneMaker.entries.forEach { maker ->
            compose.onNodeWithTag(phoneMakerTag(maker)).performScrollTo().assertIsDisplayed()
        }
    }

    @Test
    fun `S004 RF-12 la ayuda tiene acceso directo a los ajustes de bateria de la app`() {
        openHelp()

        compose.onNodeWithTag(BACKGROUND_HELP_BATTERY_TAG).performScrollTo().performClick()

        batterySettings shouldBe 1
    }

    @Test
    fun `S004 RF-12 el acceso directo abre los ajustes de TopoPhone, donde esta su bateria`() {
        val intent = appSettingsIntent("es.topophone.app")

        intent.action shouldBe Settings.ACTION_APPLICATION_DETAILS_SETTINGS
        intent.data shouldBe Uri.fromParts("package", "es.topophone.app", null)
    }

    @Test
    fun `S004 RF-12 los pasos del fabricante del movil salen los primeros`() {
        phoneMakersFor("Xiaomi").first() shouldBe PhoneMaker.XIAOMI
        phoneMakersFor("POCO").first() shouldBe PhoneMaker.XIAOMI
        phoneMakersFor("samsung").first() shouldBe PhoneMaker.SAMSUNG
        phoneMakersFor("HONOR").first() shouldBe PhoneMaker.HUAWEI
        phoneMakersFor("OnePlus").first() shouldBe PhoneMaker.OPPO
        phoneMakersFor("Google") shouldContainExactly PhoneMaker.entries
    }

    @Test
    fun `S004 RF-12 el fabricante del movil se senala en la ayuda`() {
        compose.setContent { BackgroundHelpContent(manufacturer = "samsung", onOpenAppBatterySettings = {}) }

        compose.onAllNodesWithTag(phoneMakerTag(PhoneMaker.SAMSUNG))[0]
            .assertIsDisplayed()
        compose.onNodeWithText(text(R.string.background_help_your_phone, text(R.string.background_help_samsung_title)))
            .assertIsDisplayed()
    }

    @Test
    fun `S004 RF-12 Atras vuelve a Ajustes`() {
        openHelp()

        compose.waitForIdle()
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }

        compose.onNodeWithTag(BACKGROUND_HELP_SETTING_TAG).assertIsDisplayed()
    }
}
