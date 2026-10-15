package es.topophone.app.ui.status

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.app.ui.StatefulTestApp
import es.topophone.app.ui.TopLevelDestination
import es.topophone.app.ui.navItem
import es.topophone.app.ui.receiver.CONNECTION_STATUS_TAG
import es.topophone.app.ui.settings.LICENSES_SETTING_TAG
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** La barra de estado en todas las secciones y su acceso a Receptor (S003/RF-16, S003/RF-23). */
@RunWith(AndroidJUnit4::class)
class StatusBarNavigationTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `S003 RF-16 la barra de estado aparece en Levantamiento, Receptor y Ajustes`() {
        compose.setContent { StatefulTestApp() }

        compose.onNodeWithTag(STATUS_BAR_TAG).assertIsDisplayed()
        compose.navItem(TopLevelDestination.RECEIVER).performClick()
        compose.onNodeWithTag(STATUS_BAR_TAG).assertIsDisplayed()
        compose.navItem(TopLevelDestination.SETTINGS).performClick()
        compose.onNodeWithTag(STATUS_BAR_TAG).assertIsDisplayed()
        compose.onNodeWithTag(LICENSES_SETTING_TAG).performScrollTo().performClick()
        compose.onNodeWithTag(STATUS_BAR_TAG).assertIsDisplayed()
    }

    @Test
    fun `S003 RF-23 pulsar la barra de estado abre la seccion Receptor`() {
        compose.setContent { StatefulTestApp() }
        compose.onNodeWithTag(CONNECTION_STATUS_TAG).assertDoesNotExist()

        compose.onNodeWithTag(STATUS_BAR_TAG).performClick()

        compose.onNodeWithTag(CONNECTION_STATUS_TAG).assertIsDisplayed()
    }
}
