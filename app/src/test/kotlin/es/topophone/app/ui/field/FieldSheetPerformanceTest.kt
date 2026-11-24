package es.topophone.app.ui.field

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.app.ui.collector.CollectorActions
import es.topophone.app.ui.collector.CollectorSheet
import es.topophone.app.ui.collector.CollectorUiState
import es.topophone.app.ui.collector.LiveQuality
import es.topophone.app.ui.map.StakeoutCameraPolicy
import es.topophone.core.designsystem.theme.TopoPhoneTheme
import es.topophone.core.geodesy.GeoPoint
import es.topophone.core.gnss.nmea.SolutionStatus
import es.topophone.core.model.preferences.EffectiveTheme
import es.topophone.core.model.project.LinearUnit
import io.kotest.matchers.doubles.shouldBeLessThan as doubleLessThan
import io.kotest.matchers.longs.shouldBeLessThan
import kotlin.time.measureTime
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * El rendimiento de la hoja de campo (S060/RNF-T13): la línea de estado y la placa se refrescan a 5 Hz sin bloquear el hilo principal, y
 * seguir al jalón con el mapa no pasa de las 5 órdenes por segundo. Robolectric no mide los 16 ms de un móvil: los umbrales son los de los
 * demás tests de rendimiento de la app y la medida real se ve en la demo del emulador.
 */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "es-rES")
class FieldSheetPerformanceTest {

    @get:Rule
    val compose = createComposeRule()

    private fun live(index: Int) = LiveQuality(SolutionStatus.FIX, 0.010 + (index % 7) * 0.001, 0.015 + (index % 5) * 0.001)

    @Test
    fun `S060 RNF-T13 la linea de estado del recolector se refresca a 5 Hz con cada actualizacion en menos de 300 ms`() {
        var state by mutableStateOf(CollectorUiState(loaded = true, name = "P1", poleHeightText = "1,800", live = live(0)))
        compose.setContent {
            TopoPhoneTheme(EffectiveTheme.LIGHT) {
                CollectorSheet(state, LinearUnit.METRE, CollectorActions(), onClose = {}, modifier = Modifier)
            }
        }
        compose.waitForIdle()
        repeat(3) {
            // calentamiento
            state = state.copy(live = live(it))
            compose.waitForIdle()
        }

        // Cinco segundos a 5 Hz: 25 actualizaciones; la peor no pasa de 300 ms.
        val worst = (1..25).maxOf { index ->
            measureTime {
                state = state.copy(live = live(index + 3))
                compose.waitForIdle()
            }.inWholeMilliseconds
        }

        worst shouldBeLessThan 300L
    }

    @Test
    fun `S060 RNF-T13 seguir al jalon cuesta menos de 20 microsegundos por orden y da como mucho 5 por segundo`() {
        val policy = StakeoutCameraPolicy()
        val tip = GeoPoint(40.4168, -3.7038)
        val design = GeoPoint(40.4170, -3.7030)
        repeat(1_000) { policy.next(tip, design, Math.toRadians(it.toDouble()), 360.0, it * 20L) } // calentamiento

        val calls = 50_000 // 50 Hz durante 1.000 s
        var orders = 0
        val elapsed = measureTime {
            for (i in 0 until calls) {
                if (policy.next(tip, design, Math.toRadians((i % 360).toDouble()), 360.0, 1_000_000L + i * 20L) != null) orders++
            }
        }

        (elapsed.inWholeMicroseconds / calls) shouldBeLessThan 20L
        (orders / 1_000.0) doubleLessThan 5.01 // ≤ 5 órdenes por segundo
    }
}
