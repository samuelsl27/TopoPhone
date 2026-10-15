package es.topophone.app.ui.status

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.app.ui.StatefulTestApp
import es.topophone.app.ui.TopLevelDestination
import es.topophone.app.ui.navItem
import es.topophone.app.ui.receiver.CONNECTION_STATUS_TAG
import es.topophone.app.ui.settings.LICENSES_SETTING_TAG
import es.topophone.core.designsystem.component.SUN_TOGGLE_TAG
import io.kotest.matchers.shouldBe
import kotlin.math.abs
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** El indicador de estado en la barra superior de todas las secciones y su acceso a Receptor (S003/RF-16, S003/RF-23). */
@RunWith(AndroidJUnit4::class)
class StatusIndicatorNavigationTest {

    @get:Rule
    val compose = createComposeRule()

    /** En la barra superior, a la derecha, junto al acceso al modo sol. */
    private fun assertIndicatorInTopBar() {
        val indicator = compose.onNodeWithTag(STATUS_INDICATOR_TAG).assertIsDisplayed().getBoundsInRoot()
        val sun = compose.onNodeWithTag(SUN_TOGGLE_TAG).getBoundsInRoot()
        (abs(((indicator.top + indicator.bottom) - (sun.top + sun.bottom)).value) < 2.dp.value) shouldBe true
        (indicator.right <= sun.left) shouldBe true
    }

    @Test
    fun `S003 RF-16 el indicador de estado esta en la barra superior de Levantamiento, Receptor y Ajustes`() {
        compose.setContent { StatefulTestApp() }

        assertIndicatorInTopBar()
        compose.navItem(TopLevelDestination.RECEIVER).performClick()
        assertIndicatorInTopBar()
        compose.navItem(TopLevelDestination.SETTINGS).performClick()
        assertIndicatorInTopBar()
        compose.onNodeWithTag(LICENSES_SETTING_TAG).performScrollTo().performClick()
        assertIndicatorInTopBar()
    }

    @Test
    fun `S003 RF-23 pulsar el indicador de estado abre la seccion Receptor`() {
        compose.setContent { StatefulTestApp() }
        compose.onNodeWithTag(CONNECTION_STATUS_TAG).assertDoesNotExist()

        compose.onNodeWithTag(STATUS_INDICATOR_TAG).performClick()

        compose.onNodeWithTag(CONNECTION_STATUS_TAG).assertIsDisplayed()
    }
}
