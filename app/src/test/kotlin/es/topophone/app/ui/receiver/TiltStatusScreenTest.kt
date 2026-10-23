package es.topophone.app.ui.receiver

import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.app.R
import es.topophone.app.ui.text
import es.topophone.core.designsystem.theme.TopoPhoneTheme
import es.topophone.core.gnss.nmea.Epoch
import es.topophone.core.gnss.nmea.GgaFix
import es.topophone.core.gnss.nmea.SolutionStatus
import es.topophone.core.gnss.nmea.TiltAccuracyCoefficients
import es.topophone.core.gnss.nmea.TiltData
import es.topophone.core.gnss.nmea.TiltNotification
import es.topophone.core.gnss.nmea.TiltState
import es.topophone.core.model.preferences.EffectiveTheme
import es.topophone.core.model.preferences.ThemeState
import es.topophone.core.model.project.AngularUnit
import es.topophone.receiver.session.ConnectionState
import es.topophone.receiver.session.ReceiverSnapshot
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** La tarjeta "Inclinación" de la pantalla Estado (S016/RF-11). Interfaz en español (es-ES). */
@RunWith(AndroidJUnit4::class)
class TiltStatusScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val gga = GgaFix("101530.20", 40.41668, -3.70371, SolutionStatus.FIX, 24, 0.6, 650.123, 51.234, 1.0, "")

    /** El ejemplo de ETC_rev_3: coeficientes N 1,013, E 0,993 y U 1,021. */
    private val compensating = TiltData(
        utcMillisOfDay = 36_930_200,
        state = TiltState.COMPENSATING,
        notification = TiltNotification.NONE,
        headingRad = Math.toRadians(172.543),
        tiltDirectionRad = Math.toRadians(210.5),
        tiltRad = Math.toRadians(15.25),
        accuracyCoefficients = TiltAccuracyCoefficients(north = 1.013, east = 0.993, up = 1.021),
    )

    private fun epoch(tilt: TiltData?) = Epoch(36_930_200, null, gga, null, null, null, null, null, tilt, emptyMap(), emptyMap(), 0)

    private fun show(tilt: TiltData?, unit: AngularUnit = AngularUnit.DECIMAL_DEGREES) {
        val state = ReceiverUiState(
            snapshot = ReceiverSnapshot(ConnectionState.Receiving("RS3"), gga = gga, epoch = epoch(tilt)),
            angularUnit = unit,
            poleHeightM = 1.8,
        )
        compose.setContent {
            TopoPhoneTheme(EffectiveTheme.LIGHT) {
                ReceiverPageScreen(ReceiverPage.STATUS, ThemeState.DEFAULT, {}, state, ReceiverActions(), {})
            }
        }
    }

    private fun value(tag: String) = compose.onNodeWithTag(tag).performScrollTo()

    @Test
    fun `S016 RF-11 en estado 30 estado, aviso, angulos y precision`() {
        show(compensating)

        value(STATUS_TILT_STATE_TAG).assert(hasText(text(R.string.tilt_compensating)))
        value(STATUS_TILT_NOTIFICATION_TAG).assert(hasText(text(R.string.tilt_notification_none)))
        value(STATUS_TILT_ANGLE_TAG).assert(hasText("15,250°"))
        value(STATUS_TILT_DIRECTION_TAG).assert(hasText("210,500°"))
        value(STATUS_TILT_HEADING_TAG).assert(hasText("172,543°"))
        // σ en planta = √(0,993² + 1,013²)·1,934 = 2,743 mm; vertical = 1,021·1,934 = 1,975 mm.
        value(STATUS_TILT_HORIZONTAL_TAG).assert(hasText("0,003 m"))
        value(STATUS_TILT_VERTICAL_TAG).assert(hasText("0,002 m"))
    }

    @Test
    fun `S016 RF-11 los angulos van en el formato de los proyectos nuevos`() {
        show(compensating, AngularUnit.DMS)

        value(STATUS_TILT_DIRECTION_TAG).assert(hasText("210° 30′ 00″"))
    }

    @Test
    fun `S016 RF-11 fuera del estado 30 solo el estado y el aviso`() {
        show(TiltData(null, TiltState.ALIGNMENT, TiltNotification.BAD_GNSS, null, null, null, null))

        value(STATUS_TILT_STATE_TAG).assert(hasText(text(R.string.tilt_alignment)))
        value(STATUS_TILT_NOTIFICATION_TAG).assert(hasText(text(R.string.tilt_notification_bad_gnss)))
        compose.onNodeWithTag(STATUS_TILT_ANGLE_TAG).assertDoesNotExist()
        compose.onNodeWithTag(STATUS_TILT_HORIZONTAL_TAG).assertDoesNotExist()
    }

    @Test
    fun `S016 RF-11 sin ETC no hay tarjeta de inclinacion`() {
        show(null)

        compose.onNodeWithTag(STATUS_TILT_STATE_TAG).assertDoesNotExist()
    }

    @Test
    fun `S016 RF-11 con la inclinacion apagada no se dice el aviso`() {
        show(TiltData(null, TiltState.OFF, TiltNotification.OFF, null, null, null, null))

        value(STATUS_TILT_STATE_TAG).assert(hasText(text(R.string.tilt_off)))
        compose.onNodeWithTag(STATUS_TILT_NOTIFICATION_TAG).assertDoesNotExist()
    }
}
