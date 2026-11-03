package es.topophone.app.ui.linear

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.core.designsystem.theme.TopoPhoneTheme
import es.topophone.core.model.alignment.AlignmentElement
import es.topophone.core.model.alignment.VerticalProfile
import es.topophone.core.model.alignment.VerticalVertex
import es.topophone.core.model.preferences.EffectiveTheme
import es.topophone.core.model.project.LinearUnit
import es.topophone.core.model.survey.linear.SurveyFlag
import es.topophone.core.survey.AlignmentGeometry
import es.topophone.core.survey.ProfileSampler
import es.topophone.core.survey.VerticalProfileEval
import io.kotest.matchers.longs.shouldBeLessThan
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlin.math.PI
import kotlin.math.sin
import kotlin.time.measureTime
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Los límites de tiempo de los perfiles (S052, RNF-T13): lo que se calcula con cada época de un perfil en un eje de 5.000 tramos no pasa de
 * 16 ms (media y peor época), y un perfil de 2.000 puntos se prepara y se dibuja en menos de 100 ms.
 */
@RunWith(AndroidJUnit4::class)
class ProfilePerformanceTest {

    @get:Rule
    val compose = createComposeRule()

    /** Un eje de 5.000 tramos de 20 m (100 km) que ondula a un lado y a otro, con una rasante que sube y baja. */
    private val elements: List<AlignmentElement> = run {
        val out = ArrayList<AlignmentElement>()
        var east = 440_000.0
        var north = 4_474_000.0
        var chainage = 0.0
        repeat(5_000) { i ->
            val azimuth = PI / 2 + 0.3 * sin(i / 40.0)
            out += AlignmentElement.line(chainage, 20.0, east, north, azimuth)
            east += 20.0 * sin(azimuth)
            north += 20.0 * kotlin.math.cos(azimuth)
            chainage += 20.0
        }
        out
    }
    private val geometry = AlignmentGeometry(elements)
    private val grade = VerticalProfileEval(
        VerticalProfile(
            listOf(
                VerticalVertex(0.0, 100.0),
                VerticalVertex(geometry.endChainage / 2, 180.0),
                VerticalVertex(geometry.endChainage, 150.0),
            ),
        ),
    )

    /** Una época caminando por el eje a 1,2 m/s desde el PK 40.000, a 0,2 m a la derecha. */
    private fun tip(index: Int): Pair<Double, Double> {
        val point = geometry.pointAt(40_000.0 + index * 0.12, 0.2).shouldNotBeNull()
        return point.easting to point.northing
    }

    private fun sample(index: Int) {
        val (e, n) = tip(index)
        ProfileSampler.longitudinal(geometry, grade, e, n, 150.0, 0.5).chainageM.shouldNotBeNull()
    }

    @Test
    fun `S052 RNF-T13 un eje de 5000 tramos tiene mas de 90 km`() {
        (elements.size >= 5_000) shouldBe true
        (geometry.endChainage > 90_000.0) shouldBe true
    }

    @Test
    fun `S052 RNF-T13 el muestreo de un punto en un eje de 5000 tramos tarda menos de 16 ms de media`() {
        repeat(200) { sample(it) } // calentamiento

        val elapsed = measureTime { repeat(2_000) { sample(it) } }

        println("Muestreo de un perfil, media por epoca: ${elapsed.inWholeMicroseconds / 2_000} µs")
        (elapsed.inWholeMicroseconds / 2_000) shouldBeLessThan 16_000L
    }

    @Test
    fun `S052 RNF-T13 la peor epoca del muestreo tampoco pasa de 16 ms`() {
        repeat(200) { sample(it) } // calentamiento
        var worst = 0L

        repeat(2_000) { i ->
            val took = measureTime { sample(i) }.inWholeMicroseconds
            if (took > worst) worst = took
        }

        println("Muestreo de un perfil, peor epoca: $worst µs")
        worst shouldBeLessThan 16_000L
    }

    private fun bigChart(count: Int = 2_000): ProfileChart {
        val points = List(count) { i ->
            val x = i * 5.0
            val ground = 100.0 + 8.0 * sin(i / 30.0)
            ProfileChartPoint("P$i", x, ground, 100.0 + 0.002 * i, ground - 100.0, true, SurveyFlag.NONE)
        }
        return ProfileChart(ProfileAxis.CHAINAGE, points, List(200) { it * 50.0 to 100.0 + 0.02 * it })
    }

    @Test
    fun `S052 RNF-T13 preparar el alzado de 2000 puntos con su zoom y su toque tarda menos de 100 ms`() {
        val chart = bigChart()
        repeat(20) { chart.domain() } // calentamiento

        val elapsed = measureTime {
            val domain = chart.domain().shouldNotBeNull()
            var viewport = ProfileViewport()
            repeat(5) { viewport = viewport.zoomIn() }
            val window = viewport.window(domain.first, domain.second)
            chart.heightRange(window)
            ticks(window.first, window.second, 5)
            repeat(100) { chart.nearest(window.first + it) }
        }

        println("Alzado de 2000 puntos, preparación: ${elapsed.inWholeMicroseconds} µs")
        elapsed.inWholeMilliseconds shouldBeLessThan 100L
    }

    @Test
    fun `S052 RNF-T13 dibujar el alzado de 2000 puntos y volver a dibujarlo con otro zoom tarda menos de 100 ms cada vez`() {
        val chart = bigChart()
        compose.setContent { TopoPhoneTheme(EffectiveTheme.LIGHT) { ProfileView(chart, LinearUnit.METRE) } }
        compose.waitForIdle()
        compose.onNodeWithTag(PROFILE_ZOOM_IN_TAG).performClick() // calentamiento
        compose.waitForIdle()

        val times = List(3) {
            measureTime {
                compose.onNodeWithTag(PROFILE_ZOOM_IN_TAG).performClick()
                compose.waitForIdle()
            }.inWholeMilliseconds
        }

        println("Alzado de 2000 puntos, redibujado con zoom (ms): $times")
        times.max() shouldBeLessThan 100L
    }
}
