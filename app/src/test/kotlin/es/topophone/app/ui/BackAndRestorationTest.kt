package es.topophone.app.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.app.MainActivity
import es.topophone.app.R
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment

/** Botón Atrás, giro y restauración del estado (S001/RF-5, S001/RF-6, S001/RF-7, S001/RF-8). */
@RunWith(AndroidJUnit4::class)
class BackAndRestorationTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    /** Pulsa Atrás cuando la interfaz ya se ha recompuesto, como haría un usuario real. */
    private fun pressBack() {
        compose.waitForIdle()
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
    }

    @Test
    fun `S001 RF-5 Atras desde Receptor vuelve a Levantamiento`() {
        compose.setContent { StatefulTestApp() }
        compose.navItem(TopLevelDestination.RECEIVER).performClick()

        pressBack()

        compose.navItem(TopLevelDestination.SURVEY).assertIsSelected()
        compose.onNodeWithText(text(R.string.survey_empty_description)).assertIsDisplayed()
        compose.activity.isFinishing shouldBe false
    }

    @Test
    fun `S001 RF-5 Atras desde Ajustes vuelve a Levantamiento aunque se haya pasado por Receptor`() {
        compose.setContent { StatefulTestApp() }
        compose.navItem(TopLevelDestination.RECEIVER).performClick()
        compose.navItem(TopLevelDestination.SETTINGS).performClick()

        pressBack()

        compose.navItem(TopLevelDestination.SURVEY).assertIsSelected()
        compose.activity.isFinishing shouldBe false
    }

    @Test
    fun `S001 RF-6 Atras en Levantamiento sale de la app`() {
        compose.setContent { StatefulTestApp() }

        pressBack()

        compose.activity.isFinishing shouldBe true
    }

    @Test
    fun `S001 RF-8 tras la muerte del proceso se restaura la seccion activa`() {
        val restoration = StateRestorationTester(compose)
        restoration.setContent { StatefulTestApp() }
        compose.navItem(TopLevelDestination.SETTINGS).performClick()

        restoration.emulateSavedInstanceStateRestore()

        compose.navItem(TopLevelDestination.SETTINGS).assertIsSelected()
        compose.onNodeWithText(text(R.string.settings_empty_description)).assertIsDisplayed()
    }
}

/** Giro real de la actividad principal (S001/RF-7). */
@RunWith(AndroidJUnit4::class)
class RotationTest {

    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun `S001 RF-7 al girar el dispositivo se conserva la seccion activa`() {
        compose.navItem(TopLevelDestination.RECEIVER).performClick()

        RuntimeEnvironment.setQualifiers("+land")
        compose.activityRule.scenario.recreate()

        compose.navItem(TopLevelDestination.RECEIVER).assertIsSelected()
        compose.onNodeWithText(text(R.string.receiver_empty_description)).assertIsDisplayed()

        RuntimeEnvironment.setQualifiers("+port")
        compose.activityRule.scenario.recreate()

        compose.navItem(TopLevelDestination.RECEIVER).assertIsSelected()
    }
}
