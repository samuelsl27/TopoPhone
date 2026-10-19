package es.topophone.app.ui.receiver

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.app.R
import es.topophone.app.ui.text
import es.topophone.core.designsystem.theme.TopoPhoneTheme
import es.topophone.core.gnss.nmea.Constellation
import es.topophone.core.gnss.nmea.Dop
import es.topophone.core.gnss.nmea.GgaFix
import es.topophone.core.gnss.nmea.GsvKey
import es.topophone.core.gnss.nmea.SatelliteView
import es.topophone.core.gnss.nmea.SatellitesInView
import es.topophone.core.gnss.nmea.SkySatellite
import es.topophone.core.gnss.nmea.SkyView
import es.topophone.core.gnss.nmea.SolutionStatus
import es.topophone.core.gnss.signal.SatelliteId
import es.topophone.core.model.preferences.EffectiveTheme
import es.topophone.core.model.preferences.ThemeState
import es.topophone.receiver.session.ConnectionState
import es.topophone.receiver.session.ReceiverSnapshot
import io.kotest.matchers.floats.plusOrMinus
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Skyplot: posición de cada satélite, su constelación, si se usa y lo que lee TalkBack (S015/RF-4 a RF-6). */
@RunWith(AndroidJUnit4::class)
class SkyplotTest {

    @get:Rule
    val compose = createComposeRule()

    private fun rad(deg: Double) = Math.toRadians(deg)

    private fun satellite(constellation: Constellation, number: Int, elevation: Double, azimuth: Double, used: Boolean) =
        SkySatellite(SatelliteId(constellation, number), rad(elevation), rad(azimuth), used)

    /** Lienzo de 200 px: centro en (100, 100) y horizonte a 100 px. */
    private fun mark(elevation: Double, azimuth: Double) =
        skyplotMarks(listOf(satellite(Constellation.GPS, 5, elevation, azimuth, used = true)), sizePx = 200f).single()

    @Test
    fun `S015 RF-4 norte arriba, azimut horario, horizonte en el borde y cenit en el centro`() {
        mark(90.0, 0.0).let { (it.x to it.y) shouldBe (100f to 100f) }
        mark(0.0, 0.0).let {
            it.x shouldBe (100f plusOrMinus 1e-3f)
            it.y shouldBe (0f plusOrMinus 1e-3f)
        }
        mark(0.0, 90.0).let {
            it.x shouldBe (200f plusOrMinus 1e-3f)
            it.y shouldBe (100f plusOrMinus 1e-3f)
        }
        mark(0.0, 180.0).y shouldBe (200f plusOrMinus 1e-3f)
        mark(0.0, 270.0).x shouldBe (0f plusOrMinus 1e-3f)
        mark(30.0, 0.0).y shouldBe ((100f - 100f * 2 / 3) plusOrMinus 1e-3f)
        mark(60.0, 45.0).let {
            it.x shouldBe ((100f + 100f / 3 * 0.70710677f) plusOrMinus 1e-3f)
            it.y shouldBe ((100f - 100f / 3 * 0.70710677f) plusOrMinus 1e-3f)
        }
    }

    @Test
    fun `S015 RF-4 una elevacion negativa queda en el horizonte`() {
        mark(-5.0, 0.0).y shouldBe (0f plusOrMinus 1e-3f)
    }

    @Test
    fun `S015 RF-5 RF-6 cada satelite lleva su letra y su numero, y relleno si se usa`() {
        val marks = skyplotMarks(
            listOf(
                satellite(Constellation.GPS, 5, 40.0, 185.0, used = true),
                satellite(Constellation.GLONASS, 6, 55.0, 300.0, used = false),
                satellite(Constellation.BEIDOU, 39, 20.0, 10.0, used = true),
            ),
            sizePx = 200f,
        )

        marks.map { it.label } shouldBe listOf("G05", "R06", "C39")
        marks.map { it.filled } shouldBe listOf(true, false, true)
        marks.map { it.constellation } shouldBe listOf(Constellation.GPS, Constellation.GLONASS, Constellation.BEIDOU)
        // Un color por constelación; "varias" y "desconocida" comparten el gris.
        Constellation.entries.map(::constellationColor).toSet().size shouldBe Constellation.entries.size - 1
    }

    private val receiving = ConnectionState.Receiving("reach-RS3")
    private val gga = GgaFix("101530.20", 40.41668, -3.70371, SolutionStatus.FIX, 24, 0.6, 650.123, 51.234, 1.0, "")
    private val sky = SkyView(
        dop = Dop(1.2, 0.5, 1.0),
        used = mapOf(Constellation.GPS to setOf(5)),
        usedCounts = mapOf(Constellation.GPS to 1),
        inView = mapOf(
            GsvKey(Constellation.GPS, 1) to
                SatellitesInView(2, listOf(SatelliteView(5, rad(40.0), rad(185.0), 35), SatelliteView(12, rad(15.0), rad(84.0), 42))),
            GsvKey(Constellation.GALILEO, 7) to SatellitesInView(1, listOf(SatelliteView(2, rad(60.0), rad(90.0), 44))),
        ),
        updatedAtMs = 0,
    )

    private fun show(snapshot: ReceiverSnapshot) = compose.setContent {
        TopoPhoneTheme(EffectiveTheme.LIGHT) {
            ReceiverPageScreen(ReceiverPage.STATUS, ThemeState.DEFAULT, {}, ReceiverUiState(snapshot = snapshot), ReceiverActions(), {})
        }
    }

    @Test
    fun `S015 RF-4 TalkBack lee del skyplot un resumen por constelacion`() {
        show(ReceiverSnapshot(receiving, gga = gga, sky = sky))

        val gps = text(R.string.skyplot_constellation, text(R.string.status_constellation, text(R.string.gnss_gps), 'G'), 1, 2)
        val galileo = text(R.string.skyplot_constellation, text(R.string.status_constellation, text(R.string.gnss_galileo), 'E'), 0, 1)
        compose.onNodeWithTag(SKYPLOT_TAG).performScrollTo()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.ContentDescription, listOf("$gps; $galileo")))
    }

    @Test
    fun `S015 RF-4 sin GSV o sin datos no hay skyplot`() {
        show(ReceiverSnapshot(receiving, gga = gga, sky = null))

        compose.onAllNodesWithTag(SKYPLOT_TAG).assertCountEquals(0)
    }
}
