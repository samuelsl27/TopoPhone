package es.topophone.app.ui.receiver

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.core.designsystem.theme.TopoPhoneTheme
import es.topophone.core.gnss.nmea.Constellation
import es.topophone.core.gnss.nmea.Dop
import es.topophone.core.gnss.nmea.GgaFix
import es.topophone.core.gnss.nmea.GsvKey
import es.topophone.core.gnss.nmea.SatelliteView
import es.topophone.core.gnss.nmea.SatellitesInView
import es.topophone.core.gnss.nmea.SkyView
import es.topophone.core.gnss.nmea.SolutionStatus
import es.topophone.core.model.preferences.EffectiveTheme
import es.topophone.core.model.preferences.ThemeState
import es.topophone.core.survey.BaseState
import es.topophone.receiver.session.ConnectionState
import es.topophone.receiver.session.ReceiverSnapshot
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.longs.shouldBeLessThan
import kotlin.time.measureTime
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Con 64 satélites en vista y 3 bandas por satélite, el skyplot y la SNR muestran una época en menos de 1 s (S015, RNF
 * de la spec). Robolectric sirve de cota; la medida en el móvil es de la demo.
 */
@RunWith(AndroidJUnit4::class)
class StatusPerformanceTest {

    @get:Rule
    val compose = createComposeRule()

    /** 64 satélites (GPS, GLONASS, Galileo y BeiDou) con 3 señales cada uno, y la base con las mismas. */
    private fun sky(shift: Int): Pair<SkyView, BaseState> {
        val systems = listOf(
            Triple(Constellation.GPS, 1..16, listOf(1, 5, 7)),
            Triple(Constellation.GLONASS, 65..80, listOf(1, 3, 1)),
            Triple(Constellation.GALILEO, 1..16, listOf(7, 1, 2)),
            Triple(Constellation.BEIDOU, 1..16, listOf(1, 5, 11)),
        )
        val inView = LinkedHashMap<GsvKey, SatellitesInView>()
        for ((constellation, prns, signals) in systems) {
            for (signal in signals.distinct()) {
                inView[GsvKey(constellation, signal)] = SatellitesInView(
                    prns.count(),
                    prns.map { prn ->
                        SatelliteView(
                            prn,
                            Math.toRadians(5.0 + (prn * 7 + shift) % 85),
                            Math.toRadians((prn * 37.0 + shift) % 360),
                            30 + (prn + shift) % 25,
                        )
                    },
                )
            }
        }
        val used = systems.associate { (constellation, prns, _) -> constellation to prns.take(10).toSet() }
        val sky = SkyView(Dop(1.2, 0.6, 1.0), used, used.mapValues { it.value.size }, inView, 0)
        val base = BaseState(null, 1, null, sky.roverSnr.mapValues { (it.value ?: 40).toDouble() + 1.5 }, snrAvailable = true)
        return sky to base
    }

    private val gga = GgaFix("101530.20", 40.41668, -3.70371, SolutionStatus.FIX, 24, 0.6, 650.123, 51.234, 1.0, "")

    @Test
    fun `S015 RNF la vista del cielo y las filas de SNR de 64 satelites con 3 bandas se calculan en menos de 100 ms`() {
        repeat(3) { sky(it).let { (sky, base) -> snrGroups(sky, base) } } // calentamiento
        val (sky, base) = sky(7)

        val elapsed = measureTime {
            sky.counts
            skyplotMarks(sky.satellites, 1_000f)
            snrGroups(sky, base)
        }

        sky.satellites.size shouldBeGreaterThanOrEqual 64
        elapsed.inWholeMilliseconds shouldBeLessThan 100L
    }

    @Test
    fun `S015 RNF la pantalla Estado muestra una epoca nueva de 64 satelites con 3 bandas en menos de 1 s`() {
        var snapshot by mutableStateOf(
            sky(0).let { (sky, base) ->
                ReceiverSnapshot(ConnectionState.Receiving("reach-RS3"), gga = gga, sky = sky, base = base)
            },
        )
        compose.setContent {
            TopoPhoneTheme(EffectiveTheme.LIGHT) {
                ReceiverPageScreen(ReceiverPage.STATUS, ThemeState.DEFAULT, {}, ReceiverUiState(snapshot = snapshot), ReceiverActions(), {})
            }
        }
        compose.waitForIdle()
        repeat(2) {
            // calentamiento
            snapshot = sky(it + 1).let { (sky, base) -> snapshot.copy(sky = sky, base = base) }
            compose.waitForIdle()
        }

        val next = sky(5)
        val elapsed = measureTime {
            snapshot = snapshot.copy(sky = next.first, base = next.second)
            compose.waitForIdle()
        }

        elapsed.inWholeMilliseconds shouldBeLessThan 1_000L
    }
}
