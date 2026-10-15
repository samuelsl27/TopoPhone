package es.topophone.app.ui.receiver

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.app.ui.StatefulTestApp
import es.topophone.app.ui.TopLevelDestination
import es.topophone.app.ui.navItem
import es.topophone.app.ui.sessions.SESSIONS_LIST_TAG
import es.topophone.core.designsystem.component.NAVIGATE_BACK_TAG
import es.topophone.receiver.session.ConnectionState
import es.topophone.receiver.session.ReceiverSnapshot
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Pantallas del menú del receptor: abrir, volver, giro y muerte del proceso (S005/RF-14, RF-15, RF-17). */
@RunWith(AndroidJUnit4::class)
class ReceiverNavigationTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val connected = ReceiverUiState(
        bluetooth = BluetoothUi.Ready(listOf(RS3_DEVICE)),
        snapshot = ReceiverSnapshot(ConnectionState.Receiving("reach-RS3")),
        poleHeightText = "1,800",
    )

    private val screens = listOf(
        MENU_STATUS_TAG to RECEIVER_STATUS_SCREEN_TAG,
        MENU_COORDINATES_TAG to RECEIVER_COORDINATES_SCREEN_TAG,
        MENU_CORRECTIONS_TAG to RECEIVER_CORRECTIONS_SCREEN_TAG,
        MENU_SESSIONS_TAG to SESSIONS_LIST_TAG,
    )

    private fun openReceiver() {
        compose.setContent { StatefulTestApp(receiverState = connected) }
        compose.navItem(TopLevelDestination.RECEIVER).performClick()
    }

    private fun pressBack() {
        compose.waitForIdle()
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
    }

    @Test
    fun `S005 RF-14 S005 RF-15 cada entrada abre su pantalla y la flecha vuelve a la pantalla del receptor`() {
        openReceiver()

        screens.forEach { (entry, screen) ->
            compose.onNodeWithTag(entry).performScrollTo().performClick()
            compose.onNodeWithTag(screen).assertIsDisplayed()

            compose.onNodeWithTag(NAVIGATE_BACK_TAG).performClick()
            // Al volver se conserva el desplazamiento de la pantalla del receptor.
            compose.onNodeWithTag(RECEIVER_HEADER_TAG).performScrollTo().assertIsDisplayed()
        }
    }

    @Test
    fun `S005 RF-15 Atras en una pantalla del menu vuelve a la pantalla del receptor, no a Levantamiento`() {
        openReceiver()
        compose.onNodeWithTag(MENU_CORRECTIONS_TAG).performScrollTo().performClick()

        pressBack()

        compose.onNodeWithTag(RECEIVER_HEADER_TAG).performScrollTo().assertIsDisplayed()
        compose.navItem(TopLevelDestination.RECEIVER).assertIsSelected()
    }

    @Test
    fun `S005 RF-17 tras la muerte del proceso se conserva la pantalla abierta dentro de Receptor`() {
        val restoration = StateRestorationTester(compose)
        restoration.setContent { StatefulTestApp(receiverState = connected) }
        compose.navItem(TopLevelDestination.RECEIVER).performClick()
        compose.onNodeWithTag(MENU_COORDINATES_TAG).performScrollTo().performClick()

        restoration.emulateSavedInstanceStateRestore()

        compose.onNodeWithTag(RECEIVER_COORDINATES_SCREEN_TAG).assertIsDisplayed()
        compose.navItem(TopLevelDestination.RECEIVER).assertIsSelected()
    }
}
