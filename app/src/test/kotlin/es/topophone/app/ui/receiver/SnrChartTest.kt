package es.topophone.app.ui.receiver

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.app.R
import es.topophone.app.ui.text
import es.topophone.core.designsystem.component.StatusToneKey
import es.topophone.core.designsystem.theme.StatusTone
import es.topophone.core.designsystem.theme.TopoPhoneTheme
import es.topophone.core.gnss.nmea.Constellation
import es.topophone.core.gnss.nmea.GgaFix
import es.topophone.core.gnss.nmea.GsvKey
import es.topophone.core.gnss.nmea.SatelliteView
import es.topophone.core.gnss.nmea.SatellitesInView
import es.topophone.core.gnss.nmea.SkyView
import es.topophone.core.gnss.nmea.SolutionStatus
import es.topophone.core.gnss.signal.Band
import es.topophone.core.gnss.signal.SatelliteId
import es.topophone.core.gnss.signal.SignalKey
import es.topophone.core.model.preferences.EffectiveTheme
import es.topophone.core.model.preferences.ThemeState
import es.topophone.core.survey.BaseState
import es.topophone.receiver.session.ConnectionState
import es.topophone.receiver.session.ReceiverSnapshot
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** SNR del rover y de la base por satélite y banda (S015/RF-7 a RF-10). Interfaz en español (es-ES). */
@RunWith(AndroidJUnit4::class)
class SnrChartTest {

    @get:Rule
    val compose = createComposeRule()

    private val g05 = SatelliteId(Constellation.GPS, 5)
    private val g12 = SatelliteId(Constellation.GPS, 12)
    private val g13 = SatelliteId(Constellation.GPS, 13)
    private val g20 = SatelliteId(Constellation.GPS, 20)
    private val e02 = SatelliteId(Constellation.GALILEO, 2)

    private val sky = SkyView(
        dop = null,
        used = emptyMap(),
        usedCounts = emptyMap(),
        inView = mapOf(
            GsvKey(Constellation.GPS, 1) to SatellitesInView(
                3,
                listOf(SatelliteView(12, null, null, 34), SatelliteView(5, null, null, 42), SatelliteView(13, null, null, null)),
            ),
            GsvKey(Constellation.GPS, 8) to SatellitesInView(1, listOf(SatelliteView(5, null, null, 45))),
            GsvKey(Constellation.GALILEO, null) to SatellitesInView(1, listOf(SatelliteView(2, null, null, 44))),
        ),
        updatedAtMs = 0,
    )
    private val base = BaseState(
        position = null,
        serial = 1,
        change = null,
        snr = mapOf(
            SignalKey(g05, Band.L1) to 44.5,
            SignalKey(g20, Band.L1) to 51.0,
            SignalKey(g05, Band.L5) to 47.0,
        ),
        snrAvailable = true,
    )

    @Test
    fun `S015 RF-7 RF-8 filas por constelacion y banda, con la base junto al rover y las de solo base`() {
        snrGroups(sky, base) shouldBe listOf(
            SnrGroup(
                Constellation.GPS,
                Band.L1,
                listOf(
                    SnrRow(g05, rover = 42, roverInView = true, base = 44.5),
                    SnrRow(g12, rover = 34, roverInView = true, base = null),
                    SnrRow(g13, rover = null, roverInView = true, base = null),
                    SnrRow(g20, rover = null, roverInView = false, base = 51.0),
                ),
            ),
            SnrGroup(Constellation.GPS, Band.L5, listOf(SnrRow(g05, rover = 45, roverInView = true, base = 47.0))),
            SnrGroup(Constellation.GALILEO, null, listOf(SnrRow(e02, rover = 44, roverInView = true, base = null))),
        )
    }

    @Test
    fun `S015 RF-10 sin SNR de la base vigente solo van las barras del rover`() {
        snrGroups(sky, base.copy(snrAvailable = false)).flatMap { it.rows }.mapNotNull { it.base } shouldBe emptyList()
    }

    @Test
    fun `S015 RF-9 los tramos son menos de 35, de 35 a menos de 45, y 45 o mas`() {
        listOf(20.0, 34.9375, 35.0, 44.9375, 45.0, 56.0).map(::snrTone) shouldBe listOf(
            StatusTone.NEGATIVE,
            StatusTone.NEGATIVE,
            StatusTone.WARNING,
            StatusTone.WARNING,
            StatusTone.POSITIVE,
            StatusTone.POSITIVE,
        )
    }

    private val gga = GgaFix("101530.20", 40.41668, -3.70371, SolutionStatus.FIX, 24, 0.6, 650.123, 51.234, 1.0, "")

    private fun show(base: BaseState) = compose.setContent {
        TopoPhoneTheme(EffectiveTheme.LIGHT) {
            val snapshot = ReceiverSnapshot(ConnectionState.Receiving("reach-RS3"), gga = gga, sky = sky, base = base)
            ReceiverPageScreen(ReceiverPage.STATUS, ThemeState.DEFAULT, {}, ReceiverUiState(snapshot = snapshot), ReceiverActions(), {})
        }
    }

    @Test
    fun `S015 RF-9 cada barra escribe su valor y tiene el color de su tramo`() {
        show(base)

        compose.onNodeWithTag(snrBarTag(g05, Band.L1, base = false), useUnmergedTree = true).performScrollTo()
            .assert(hasAnyDescendant(hasText("42")))
            .assert(SemanticsMatcher.expectValue(StatusToneKey, StatusTone.WARNING))
        compose.onNodeWithTag(snrBarTag(g05, Band.L1, base = true), useUnmergedTree = true).performScrollTo()
            .assert(hasAnyDescendant(hasText("44,5")))
            .assert(SemanticsMatcher.expectValue(StatusToneKey, StatusTone.WARNING))
        compose.onNodeWithTag(snrBarTag(g12, Band.L1, base = false), useUnmergedTree = true).performScrollTo()
            .assert(SemanticsMatcher.expectValue(StatusToneKey, StatusTone.NEGATIVE))
        compose.onNodeWithTag(snrBarTag(g20, Band.L1, base = true), useUnmergedTree = true).performScrollTo()
            .assert(hasAnyDescendant(hasText("51")))
            .assert(SemanticsMatcher.expectValue(StatusToneKey, StatusTone.POSITIVE))
    }

    @Test
    fun `S015 RF-7 los grupos llevan su banda o Sin banda, y el satelite sin SNR va sin barra`() {
        show(base)

        compose.onNodeWithTag(snrGroupTag(Constellation.GPS, Band.L5)).performScrollTo().assert(hasText("GPS (G) · L5"))
        compose.onNodeWithTag(snrGroupTag(Constellation.GALILEO, null)).performScrollTo()
            .assert(hasText("Galileo (E) · ${text(R.string.snr_no_band)}"))
        compose.onNodeWithTag(snrRowTag(g13, Band.L1)).performScrollTo().assert(hasText("G13"))
        compose.onAllNodesWithTag(snrBarTag(g13, Band.L1, base = false), useUnmergedTree = true).assertCountEquals(0)
    }

    @Test
    fun `S015 RF-8 TalkBack lee de cada fila el satelite, la banda y las dos SNR`() {
        show(base)

        compose.onNodeWithTag(snrRowTag(g05, Band.L1)).performScrollTo().assert(
            SemanticsMatcher.expectValue(
                SemanticsProperties.ContentDescription,
                listOf(
                    text(
                        R.string.snr_row_description,
                        "G05",
                        "L1",
                        text(R.string.snr_row_rover, "42"),
                        text(R.string.snr_row_base, "44,5"),
                    ),
                ),
            ),
        )
    }

    @Test
    fun `S015 RF-10 sin SNR de la base se dice que no esta disponible`() {
        show(base.copy(snrAvailable = false, snr = emptyMap()))

        compose.onNodeWithTag(SNR_BASE_UNAVAILABLE_TAG).performScrollTo().assert(hasText(text(R.string.snr_base_unavailable)))
        compose.onAllNodesWithTag(snrBarTag(g05, Band.L1, base = true), useUnmergedTree = true).assertCountEquals(0)
    }
}
