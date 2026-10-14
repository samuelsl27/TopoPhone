package es.topophone.app.ui.settings

import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.app.BuildConfig
import es.topophone.app.MainActivity
import es.topophone.app.R
import es.topophone.app.ui.AppInfo
import es.topophone.app.ui.StatefulTestApp
import es.topophone.app.ui.TopLevelDestination
import es.topophone.app.ui.awaitAppReady
import es.topophone.app.ui.navItem
import es.topophone.app.ui.text
import es.topophone.core.designsystem.component.NAVIGATE_BACK_TAG
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Pantalla "Acerca de" (S001/RF-20, S001/RF-21, S001/RF-22). */
@RunWith(AndroidJUnit4::class)
class AboutScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private fun openAbout(
        appInfo: AppInfo = AppInfo("0.0.0-dev", "0123456789ab", "https://github.com/samuelsl27/TopoPhone"),
        onOpenUrl: (String) -> Unit = {
        },
    ) {
        compose.setContent { StatefulTestApp(appInfo = appInfo, onOpenUrl = onOpenUrl) }
        compose.navItem(TopLevelDestination.SETTINGS).performClick()
        compose.onNodeWithTag(ABOUT_SETTING_TAG).performScrollTo().performClick()
    }

    @Test
    fun `S001 RF-20 muestra nombre, version, commit, licencia GPL-3_0 y repositorio`() {
        openAbout()

        compose.onNodeWithTag(ABOUT_NAME_TAG).assert(hasText("TopoPhone"))
        compose.onNodeWithTag(ABOUT_VERSION_TAG).assert(hasText("0.0.0-dev"))
        compose.onNodeWithTag(ABOUT_COMMIT_TAG).assert(hasText("0123456789ab"))
        compose.onNodeWithTag(ABOUT_LICENSE_TAG).assert(hasText("GPL-3.0", substring = true))
        compose.onNodeWithTag(ABOUT_REPOSITORY_TAG).assert(hasText("https://github.com/samuelsl27/TopoPhone"))
    }

    @Test
    fun `S001 RF-20 pulsar el repositorio abre su enlace`() {
        var opened: String? = null
        openAbout(onOpenUrl = { opened = it })

        compose.onNodeWithTag(ABOUT_REPOSITORY_TAG).performScrollTo().performClick()

        opened shouldBe "https://github.com/samuelsl27/TopoPhone"
    }

    @Test
    fun `S001 RF-21 una compilacion sin etiqueta muestra la version con el sufijo -dev`() {
        openAbout(appInfo = AppInfo("0.0.2-dev", "abcdef123456", "https://github.com/samuelsl27/TopoPhone"))

        compose.onNodeWithTag(ABOUT_VERSION_TAG).assert(hasText("0.0.2-dev"))
    }

    @Test
    fun `S001 RF-22 indica que TopoPhone es un proyecto independiente no afiliado a Emlid`() {
        openAbout()

        compose.onNodeWithTag(ABOUT_DISCLAIMER_TAG).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(text(R.string.about_disclaimer)).assertIsDisplayed()
        text(R.string.about_disclaimer).contains("Emlid") shouldBe true
    }

    @Test
    fun `S001 RF-20 la flecha atras vuelve a Ajustes`() {
        openAbout()

        compose.onNodeWithTag(NAVIGATE_BACK_TAG).performClick()

        compose.onNodeWithTag(ABOUT_SETTING_TAG).assertIsDisplayed()
    }
}

/** La app real muestra la versión y el commit con que se compiló (S001/RF-20, S001/RF-21). */
@RunWith(AndroidJUnit4::class)
class AboutBuildInfoTest {

    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun `S001 RF-20 S001 RF-21 Acerca de muestra la version y el commit de la compilacion`() {
        compose.awaitAppReady()
        compose.navItem(TopLevelDestination.SETTINGS).performClick()
        compose.onNodeWithTag(ABOUT_SETTING_TAG).performScrollTo().performClick()

        compose.onNodeWithTag(ABOUT_VERSION_TAG).assert(hasText(BuildConfig.VERSION_NAME))
        compose.onNodeWithTag(ABOUT_COMMIT_TAG).assert(hasText(BuildConfig.GIT_COMMIT))
        compose.onNodeWithTag(ABOUT_REPOSITORY_TAG).assert(hasText(BuildConfig.REPO_URL))
    }
}
