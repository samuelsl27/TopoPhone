package es.topophone.app.receiver

import android.content.Context
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.app.R
import es.topophone.app.ui.status.STATUS_INDICATOR_CORRECTIONS_TAG
import es.topophone.app.ui.status.STATUS_INDICATOR_TAG
import es.topophone.app.ui.status.StatusIndicator
import es.topophone.app.ui.status.StatusIndicatorState
import es.topophone.app.ui.status.StatusSource
import es.topophone.app.ui.status.toStatusIndicatorState
import es.topophone.app.ui.text
import es.topophone.core.designsystem.theme.TopoPhoneTheme
import es.topophone.core.gnss.nmea.SolutionStatus
import es.topophone.core.model.preferences.EffectiveTheme
import es.topophone.receiver.session.ConnectionState
import es.topophone.receiver.session.NtripState
import es.topophone.receiver.session.ReceiverSnapshot
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.count
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Aviso de la alarma por edad de las correcciones en el indicador, la notificación, el sonido y la vibración (S007/RF-29). */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "es-rES")
class CorrectionsAlarmTest {

    @get:Rule
    val compose = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val receiving = ReceiverSnapshot(connection = ConnectionState.Receiving("reach-RS3"), ntrip = NtripState.Streaming(10, 0))

    @Test
    fun `S007 RF-29 con la alarma el indicador de estado dice Sin correcciones con su icono`() {
        toStatusIndicatorState(receiving.copy(correctionsAlarm = true)).correctionsAlarm shouldBe true
        toStatusIndicatorState(receiving).correctionsAlarm shouldBe false

        compose.setContent {
            TopoPhoneTheme(EffectiveTheme.LIGHT) {
                StatusIndicator(
                    StatusIndicatorState(StatusSource.RECEIVER, solution = SolutionStatus.FIX, satellites = 24, correctionsAlarm = true),
                    onClick = {},
                )
            }
        }

        compose.onNodeWithTag(STATUS_INDICATOR_CORRECTIONS_TAG, useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithTag(STATUS_INDICATOR_TAG).assert(hasText(text(R.string.status_bar_no_corrections), substring = true))
    }

    @Test
    fun `S007 RF-29 con la alarma la notificacion dice que no llegan correcciones`() {
        ReceiverNotifications.text(context, receiving.copy(correctionsAlarm = true)) shouldContain
            text(R.string.notification_no_corrections)
        ReceiverNotifications.text(context, receiving) shouldNotContain text(R.string.notification_no_corrections)
    }

    @Test
    fun `S007 RF-29 el sonido y la vibracion suenan una vez cada vez que salta la alarma`() {
        val states = listOf(false, true, true, true, false, false, true, true).map { receiving.copy(correctionsAlarm = it) }

        val rises = runBlocking { correctionsAlarmRises(states.asFlow()).count() }

        rises shouldBe 2
    }
}
