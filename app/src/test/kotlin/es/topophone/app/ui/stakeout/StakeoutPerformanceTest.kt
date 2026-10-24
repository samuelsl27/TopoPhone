package es.topophone.app.ui.stakeout

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.core.designsystem.theme.TopoPhoneTheme
import es.topophone.core.geodesy.GeodeticPoint
import es.topophone.core.geodesy.LocalTangentPlane
import es.topophone.core.gnss.nmea.Epoch
import es.topophone.core.gnss.nmea.GgaFix
import es.topophone.core.gnss.nmea.GstAccuracy
import es.topophone.core.gnss.nmea.SolutionStatus
import es.topophone.core.model.point.GeodeticPosition
import es.topophone.core.model.point.PointCoordinates
import es.topophone.core.model.point.StakeoutSettings
import es.topophone.core.model.preferences.EffectiveTheme
import es.topophone.core.model.project.AngularUnit
import es.topophone.core.model.project.CorrectionsDatum
import es.topophone.core.model.project.LinearUnit
import es.topophone.core.model.project.Project
import es.topophone.core.model.project.ProjectCrs
import es.topophone.core.survey.ProjectPositionCalculator
import es.topophone.receiver.platform.Alerts
import io.kotest.matchers.longs.shouldBeLessThan
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.time.measureTime
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * La guía del replanteo a 10 Hz se calcula en menos de 1 ms por época, y de que llega una época a que el panel la muestra pasan
 * menos de 300 ms (S018, RNF de la spec). Robolectric sirve de cota; la medida en el móvil es de la demo.
 */
@RunWith(AndroidJUnit4::class)
class StakeoutPerformanceTest {

    @get:Rule
    val compose = createComposeRule()

    private val project = Project(
        id = "p1",
        name = "Calle Mayor 12",
        author = "",
        description = "",
        crs = ProjectCrs.ETRS89_UTM30,
        linearUnit = LinearUnit.METRE,
        angularUnit = AngularUnit.DECIMAL_DEGREES,
        correctionsDatum = CorrectionsDatum.ETRS89,
        folderId = null,
        createdAtMs = 1_000,
        modifiedAtMs = 1_000,
        openedAtMs = null,
        deletedAtMs = null,
    )
    private val calculator = ProjectPositionCalculator()
    private val origin = GeodeticPoint(40.4168, -3.0, 699.423)
    private val design: PointCoordinates = run {
        val ground = GeodeticPosition(origin.latitudeDeg, origin.longitudeDeg, origin.ellipsoidalHeightM)
        val position = calculator.fromGround(ground, project.crs, project.heightSystem)
        PointCoordinates(ground, position.easting, position.northing, position.height)
    }
    private val target = StakeTarget("d1", "12", "esquina", design)
    private val context = StakeContext(StakeoutSettings(), target, true, null, 1.8, project, calculator)
    private val silent = object : Alerts {
        override fun connectionLost() = Unit

        override fun correctionsLost() = Unit

        override fun pointSaved() = Unit

        override fun solutionLost() = Unit

        override fun tiltLost() = Unit
    }

    /** Una época andando hacia el diseño: la época [index] a 10 Hz, a 1,2 m/s desde 60 m al suroeste. */
    private fun epoch(index: Int): Epoch {
        val remaining = (60.0 - 0.12 * (index % 500)).coerceAtLeast(0.0)
        val tip = LocalTangentPlane.offset(origin, -remaining * 0.7071, -remaining * 0.7071, 0.0)
        val height = tip.ellipsoidalHeightM + 1.934 - 51.234
        val fix = GgaFix("t$index", tip.latitudeDeg, tip.longitudeDeg, SolutionStatus.FIX, 24, 0.6, height, 51.234, 1.0, "")
        val gst = GstAccuracy("t", 0.008, 0.006, 0.015)
        return Epoch(36_000_000 + index * 100, null, fix, gst, null, null, null, null, null, emptyMap(), emptyMap(), 0)
    }

    @Test
    fun `S018 RNF la guia a 10 Hz tarda menos de 1 ms por epoca`() {
        val controller = StakeoutController(silent, publishIntervalMs = 0)
        val epochs = List(2_000, ::epoch)
        epochs.take(200).forEach { controller.onEpoch(it, context) } // calentamiento

        val elapsed = measureTime { epochs.forEach { controller.onEpoch(it, context) } }

        controller.state.value.shouldBeInstanceOf<GuidanceState.On>()
        (elapsed.inWholeMicroseconds / epochs.size) shouldBeLessThan 1_000L
    }

    @Test
    fun `S018 RNF de una epoca nueva a la pantalla pasan menos de 300 ms`() {
        val controller = StakeoutController(silent, publishIntervalMs = 0)
        controller.onEpoch(epoch(0), context)
        var state by mutableStateOf(StakeoutUiState(project, target, guidance = controller.state.value))
        compose.setContent {
            TopoPhoneTheme(EffectiveTheme.LIGHT) { StakeoutPanel(state, StakeoutActions()) }
        }
        compose.waitForIdle()
        // calentamiento
        repeat(3) {
            controller.onEpoch(epoch(it + 1), context)
            state = state.copy(guidance = controller.state.value)
            compose.waitForIdle()
        }

        val elapsed = measureTime {
            controller.onEpoch(epoch(100), context)
            state = state.copy(guidance = controller.state.value)
            compose.waitForIdle()
        }

        elapsed.inWholeMilliseconds shouldBeLessThan 300L
    }
}
