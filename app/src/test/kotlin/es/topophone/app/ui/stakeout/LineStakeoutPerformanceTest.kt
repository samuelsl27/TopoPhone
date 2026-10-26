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
import es.topophone.core.model.cad.CadEntity
import es.topophone.core.model.cad.CadEntityKind
import es.topophone.core.model.point.GeodeticPosition
import es.topophone.core.model.point.LineStakeoutMode
import es.topophone.core.model.point.PointCoordinates
import es.topophone.core.model.point.StakeoutSettings
import es.topophone.core.model.preferences.EffectiveTheme
import es.topophone.core.model.project.AngularUnit
import es.topophone.core.model.project.CorrectionsDatum
import es.topophone.core.model.project.LinearUnit
import es.topophone.core.model.project.Project
import es.topophone.core.model.project.ProjectCrs
import es.topophone.core.survey.LineLocator
import es.topophone.core.survey.ProjectPositionCalculator
import es.topophone.core.survey.StakeLine
import es.topophone.receiver.platform.Alerts
import io.kotest.matchers.longs.shouldBeLessThan
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.math.sin
import kotlin.time.measureTime
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Una línea de 100.000 vértices empieza a guiar en menos de 1 s, el punto más cercano a 10 Hz no pasa de 16 ms por época y de
 * que llega una época a que el panel la muestra pasan menos de 300 ms (S041, RNF-T13 y RNF de la spec). Robolectric sirve de
 * cota; la medida en el móvil es de la demo.
 */
@RunWith(AndroidJUnit4::class)
class LineStakeoutPerformanceTest {

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
    private val ground = GeodeticPosition(origin.latitudeDeg, origin.longitudeDeg, origin.ellipsoidalHeightM)
    private val originPosition = calculator.fromGround(ground, project.crs, project.heightSystem)
    private val e0 = originPosition.easting!!
    private val n0 = originPosition.northing!!
    private val target = StakeTarget("cad:l1:1:0", "Eje", "", PointCoordinates(ground, e0, n0, null))
    private val silent = object : Alerts {
        override fun connectionLost() = Unit

        override fun correctionsLost() = Unit

        override fun pointSaved() = Unit

        override fun solutionLost() = Unit

        override fun tiltLost() = Unit
    }

    /** Una polilínea de 100.000 vértices a 0,5 m uno de otro, con una ondulación de 3 m, de 50 km hacia el este. */
    private val entity: CadEntity = run {
        val n = 100_000
        CadEntity(
            CadEntityKind.POLYLINE,
            DoubleArray(n) { e0 + it * 0.5 },
            DoubleArray(n) { n0 + 3.0 * sin(it / 200.0) },
            null,
            null,
        )
    }

    /** Una época andando por la línea a 10 Hz, 1 m a un lado, a 1,2 m/s desde el origen. */
    private fun epoch(index: Int): Epoch {
        val tip = LocalTangentPlane.offset(origin, index * 0.12, 3.0 * sin(index * 0.12 / 0.5 / 200.0) - 1.0, 0.0)
        val height = tip.ellipsoidalHeightM + 1.934 - 51.234
        val fix = GgaFix("t$index", tip.latitudeDeg, tip.longitudeDeg, SolutionStatus.FIX, 24, 0.6, height, 51.234, 1.0, "")
        val gst = GstAccuracy("t", 0.008, 0.006, 0.015)
        return Epoch(36_000_000 + index * 100, null, fix, gst, null, null, null, null, null, emptyMap(), emptyMap(), 0)
    }

    private fun context(guide: LineGuide) = StakeContext(StakeoutSettings(), target, true, null, 1.8, project, calculator, null, guide)

    @Test
    fun `S041 RNF elegir una linea de 100000 vertices y empezar a guiar tarda menos de 1 s`() {
        val controller = StakeoutController(silent, publishIntervalMs = 0)
        controller.onEpoch(epoch(0), context(guideOf(StakeLine.of(entity).shouldNotBeNull()))) // calienta el código

        val elapsed = measureTime {
            val line = StakeLine.of(entity).shouldNotBeNull()
            controller.onEpoch(epoch(1), context(guideOf(line)))
        }

        controller.state.value.shouldBeInstanceOf<GuidanceState.On>().ui.line.shouldNotBeNull()
        elapsed.inWholeMilliseconds shouldBeLessThan 1_000L
    }

    private fun guideOf(line: StakeLine) = LineGuide(line, LineLocator(line), LineStakeoutMode.ALONG, 1.0)

    @Test
    fun `S041 RNF-T13 la guia de una linea de 100000 vertices a 10 Hz tarda menos de 16 ms por epoca`() {
        val controller = StakeoutController(silent, publishIntervalMs = 0)
        val context = context(guideOf(StakeLine.of(entity).shouldNotBeNull()))
        val epochs = List(2_000, ::epoch)
        epochs.take(200).forEach { controller.onEpoch(it, context) } // calentamiento

        val elapsed = measureTime { epochs.forEach { controller.onEpoch(it, context) } }

        controller.state.value.shouldBeInstanceOf<GuidanceState.On>().ui.line.shouldNotBeNull()
        (elapsed.inWholeMicroseconds / epochs.size) shouldBeLessThan 16_000L
    }

    @Test
    fun `S041 RNF-T13 la peor epoca de una caminata por la linea tampoco pasa de 16 ms`() {
        val controller = StakeoutController(silent, publishIntervalMs = 0)
        val context = context(guideOf(StakeLine.of(entity).shouldNotBeNull()))
        List(200, ::epoch).forEach { controller.onEpoch(it, context) }

        val worst = List(1_500) { epoch(it + 200) }.maxOf { epoch ->
            measureTime { controller.onEpoch(epoch, context) }.inWholeMicroseconds
        }

        worst shouldBeLessThan 16_000L
    }

    @Test
    fun `S041 RNF de una epoca nueva a la pantalla pasan menos de 300 ms con una linea de 100000 vertices`() {
        val controller = StakeoutController(silent, publishIntervalMs = 0)
        val context = context(guideOf(StakeLine.of(entity).shouldNotBeNull()))
        controller.onEpoch(epoch(0), context)
        val element = LineElementInfo("l1", "Eje", "calle.dxf", CadEntityKind.POLYLINE, 50_000.0, false, null, null)
        var state by mutableStateOf(
            LineStakeoutUiState(project = project, element = element, mode = LineStakeoutMode.ALONG, guidance = controller.state.value),
        )
        compose.setContent {
            TopoPhoneTheme(EffectiveTheme.LIGHT) { LineStakeoutPanel(state, LineStakeoutActions()) }
        }
        compose.waitForIdle()
        repeat(3) {
            // calentamiento
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
