package es.topophone.app.ui.receiver

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.app.R
import es.topophone.app.ui.text
import es.topophone.core.designsystem.component.StatusToneKey
import es.topophone.core.designsystem.theme.StatusTone
import es.topophone.core.designsystem.theme.TopoPhoneTheme
import es.topophone.core.gnss.nmea.Epoch
import es.topophone.core.gnss.nmea.GgaFix
import es.topophone.core.gnss.nmea.GstAccuracy
import es.topophone.core.gnss.nmea.SolutionStatus
import es.topophone.core.model.preferences.EffectiveTheme
import es.topophone.core.model.preferences.ThemeState
import es.topophone.receiver.session.ConnectionState
import es.topophone.receiver.session.ReceiverSnapshot
import io.kotest.matchers.shouldBe
import java.time.LocalDate
import java.util.TimeZone
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Tarjeta Estado de la pantalla Receptor (S002/RF-12 a RF-17, S003/RF-13 a RF-15). Interfaz en español (es-ES). */
@RunWith(AndroidJUnit4::class)
class StatusCardTest {

    @get:Rule
    val compose = createComposeRule()

    private val receiving = ConnectionState.Receiving("reach-RS3")

    private fun gga(status: SolutionStatus = SolutionStatus.FIX, hdop: Double? = 0.6, age: Double? = 1.0) = GgaFix(
        utcTime = "101530.20",
        latitudeDeg = 40.41668,
        longitudeDeg = -3.70371,
        status = status,
        satellites = 24,
        hdop = hdop,
        altitudeM = 650.123,
        geoidSeparationM = 51.234,
        correctionAgeS = age,
        rawSentence = "",
    )

    private val gst = GstAccuracy("101530.20", sigmaLatitudeM = 0.010, sigmaLongitudeM = 0.010, sigmaAltitudeM = 0.0237)

    private var snapshot by mutableStateOf(ReceiverSnapshot())

    private fun show(initial: ReceiverSnapshot) {
        snapshot = initial
        compose.setContent {
            TopoPhoneTheme(EffectiveTheme.LIGHT) {
                ReceiverPageScreen(ReceiverPage.STATUS, ThemeState.DEFAULT, {}, ReceiverUiState(snapshot = snapshot), ReceiverActions(), {})
            }
        }
    }

    private fun value(tag: String) = compose.onNodeWithTag(tag, useUnmergedTree = false).performScrollTo()

    @Test
    fun `S002 RF-12 S002 RF-16 cada estado de la solucion se muestra con su nombre y su color`() {
        val expected = mapOf(
            SolutionStatus.NONE to (R.string.solution_none to StatusTone.NEUTRAL),
            SolutionStatus.SINGLE to (R.string.solution_single to StatusTone.NEGATIVE),
            SolutionStatus.DGPS to (R.string.solution_dgps to StatusTone.INFO),
            SolutionStatus.FLOAT to (R.string.solution_float to StatusTone.WARNING),
            SolutionStatus.FIX to (R.string.solution_fix to StatusTone.POSITIVE),
            SolutionStatus.ESTIMATED to (R.string.solution_estimated to StatusTone.NEUTRAL),
        )
        show(ReceiverSnapshot(receiving, gga = gga()))

        expected.forEach { (status, nameAndTone) ->
            snapshot = ReceiverSnapshot(receiving, gga = gga(status))
            compose.waitForIdle()

            value(SOLUTION_STATUS_TAG)
                .assert(hasText(text(nameAndTone.first)))
                .assert(SemanticsMatcher.expectValue(StatusToneKey, nameAndTone.second))
        }
    }

    @Test
    fun `S002 RF-12 los nombres de los estados son los de la spec`() {
        listOf(
            R.string.solution_none to "Sin solución",
            R.string.solution_single to "Autónoma",
            R.string.solution_dgps to "Diferencial",
            R.string.solution_float to "Flotante",
            R.string.solution_fix to "Fija",
            R.string.solution_estimated to "Estimada",
        ).forEach { (id, name) -> text(id) shouldBe name }
    }

    @Test
    fun `S002 RF-13 se muestran satelites, HDOP y edad de las correcciones`() {
        show(ReceiverSnapshot(receiving, gga = gga(hdop = 0.6, age = 1.0)))

        value(STATUS_SATELLITES_TAG).assert(hasText("24"))
        value(STATUS_HDOP_TAG).assert(hasText("0,6"))
        value(STATUS_CORRECTION_AGE_TAG).assert(hasText("1,0 s"))
    }

    @Test
    fun `S002 RF-13 sin HDOP ni correcciones se indican como no disponibles`() {
        show(ReceiverSnapshot(receiving, gga = gga(status = SolutionStatus.SINGLE, hdop = null, age = null)))

        value(STATUS_HDOP_TAG).assert(hasText(text(R.string.receiver_value_unavailable)))
        value(STATUS_CORRECTION_AGE_TAG).assert(hasText(text(R.string.receiver_value_unavailable)))
    }

    @Test
    fun `S002 RF-14 S002 RF-15 se muestran las precisiones horizontal y vertical con 3 decimales`() {
        show(ReceiverSnapshot(receiving, gga = gga(), gst = gst))

        value(STATUS_SIGMA_H_TAG).assert(hasText("0,014 m"))
        value(STATUS_SIGMA_V_TAG).assert(hasText("0,024 m"))
    }

    @Test
    fun `S002 RF-17 sin GST reciente las precisiones se muestran como no disponibles`() {
        show(ReceiverSnapshot(receiving, gga = gga(), gst = null))

        value(STATUS_SIGMA_H_TAG).assert(hasText(text(R.string.receiver_value_unavailable)))
        value(STATUS_SIGMA_V_TAG).assert(hasText(text(R.string.receiver_value_unavailable)))
    }

    @Test
    fun `S002 RF-12 sin GGA o sin recibir datos no se muestra una solucion antigua`() {
        show(ReceiverSnapshot(receiving, gga = null))
        compose.onNodeWithText(text(R.string.receiver_status_waiting)).performScrollTo().assertIsDisplayed()

        snapshot = ReceiverSnapshot(ConnectionState.Lost("reach-RS3"), gga = gga(), gst = gst)
        compose.waitForIdle()

        compose.onNodeWithText(text(R.string.receiver_status_waiting)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag(SOLUTION_STATUS_TAG).assertDoesNotExist()
    }
    private fun epoch(date: LocalDate?) = Epoch(
        utcMillisOfDay = 37_530_000, // 10:25:30 UTC
        date = date,
        gga = gga(),
        gst = null,
        rmc = null,
        vtg = null,
        zda = null,
        basePosition = null,
        tilt = null,
        gsa = emptyMap(),
        gsv = emptyMap(),
        completedAtMs = 0,
    )

    @Test
    fun `S003 RF-14 la fecha y la hora de la epoca se muestran en la hora local del movil`() {
        val zone = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("Europe/Madrid"))
        try {
            show(ReceiverSnapshot(receiving, gga = gga(), epoch = epoch(LocalDate.of(2026, 10, 15))))

            value(STATUS_TIME_TAG).assert(hasText("12:25:30", substring = true)).assert(hasText("2026", substring = true))
        } finally {
            TimeZone.setDefault(zone)
        }
    }

    @Test
    fun `S003 RF-14 una epoca sin fecha muestra la hora UTC y fecha no disponible`() {
        show(ReceiverSnapshot(receiving, gga = gga(), epoch = epoch(null)))

        value(STATUS_TIME_TAG).assert(hasText(text(R.string.receiver_epoch_time_no_date, "10:25:30")))
        text(R.string.receiver_epoch_time_no_date, "10:25:30").contains("fecha no disponible") shouldBe true
    }

    @Test
    fun `S003 RF-15 se muestran los satelites en vista junto a los usados`() {
        show(ReceiverSnapshot(receiving, gga = gga(), epoch = epoch(null), satellitesInView = 31))

        value(STATUS_SATELLITES_TAG).assert(hasText(text(R.string.receiver_satellites_in_view, 24, 31)))
    }

    @Test
    fun `S003 RF-13 con Sin datos no se muestra la solucion de la ultima epoca`() {
        show(ReceiverSnapshot(ConnectionState.NoData("reach-RS3"), gga = gga(), gst = gst, epoch = epoch(null)))

        compose.onNodeWithText(text(R.string.receiver_status_waiting)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag(SOLUTION_STATUS_TAG).assertDoesNotExist()
    }
}
