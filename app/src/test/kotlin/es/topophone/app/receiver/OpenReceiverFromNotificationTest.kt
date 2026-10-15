package es.topophone.app.receiver

import android.content.Intent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.app.MainActivity
import es.topophone.app.ui.awaitAppReady
import es.topophone.app.ui.receiver.CONNECTION_STATUS_TAG
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric

/** La notificación del receptor abre la sección Receptor (S004/RF-9). */
@RunWith(AndroidJUnit4::class)
class OpenReceiverFromNotificationTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    private fun openReceiver() = Intent(ApplicationProvider.getApplicationContext(), MainActivity::class.java)
        .putExtra(MainActivity.EXTRA_OPEN_RECEIVER, true)

    @Test
    fun `S004 RF-9 al abrir la app desde la notificacion se muestra Receptor`() {
        ActivityScenario.launch<MainActivity>(openReceiver()).use {
            compose.awaitAppReady()

            compose.onNodeWithTag(CONNECTION_STATUS_TAG).assertIsDisplayed()
        }
    }

    @Test
    fun `S004 RF-9 con la app ya abierta en otra seccion la notificacion lleva a Receptor`() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            compose.awaitAppReady()
            compose.onNodeWithTag(CONNECTION_STATUS_TAG).assertDoesNotExist()

            activity.newIntent(openReceiver())
            compose.waitForIdle()

            compose.onNodeWithTag(CONNECTION_STATUS_TAG).assertIsDisplayed()
        } finally {
            activity.pause().stop().destroy()
        }
    }
}
