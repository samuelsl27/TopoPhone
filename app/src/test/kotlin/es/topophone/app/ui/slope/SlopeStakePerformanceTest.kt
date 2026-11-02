package es.topophone.app.ui.slope

import es.topophone.app.ui.stakeout.GuidanceState
import es.topophone.app.ui.stakeout.StakeContext
import es.topophone.app.ui.stakeout.StakeTarget
import es.topophone.app.ui.stakeout.StakeoutController
import es.topophone.core.gnss.nmea.Epoch
import es.topophone.core.gnss.nmea.GgaFix
import es.topophone.core.gnss.nmea.GstAccuracy
import es.topophone.core.gnss.nmea.SolutionStatus
import es.topophone.core.model.alignment.AlignmentElement
import es.topophone.core.model.alignment.VerticalProfile
import es.topophone.core.model.alignment.VerticalVertex
import es.topophone.core.model.alignment.slope.SlopeKind
import es.topophone.core.model.alignment.slope.SlopeRule
import es.topophone.core.model.alignment.slope.SlopeSide
import es.topophone.core.model.point.AdmittedSolutions
import es.topophone.core.model.point.EntryForm
import es.topophone.core.model.point.HeightKind
import es.topophone.core.model.point.StakeoutSettings
import es.topophone.core.model.point.TypedCoordinates
import es.topophone.core.model.project.AngularUnit
import es.topophone.core.model.project.CorrectionsDatum
import es.topophone.core.model.project.HeightSystem
import es.topophone.core.model.project.LinearUnit
import es.topophone.core.model.project.Project
import es.topophone.core.model.project.ProjectCrs
import es.topophone.core.survey.AlignmentGeometry
import es.topophone.core.survey.CadPlacement
import es.topophone.core.survey.ElementGeometry
import es.topophone.core.survey.ManualCoordinates
import es.topophone.core.survey.ManualResult
import es.topophone.core.survey.ProjectPositionCalculator
import es.topophone.core.survey.SlopeCatchResult
import es.topophone.core.survey.SlopeEdgeResult
import es.topophone.core.survey.SlopeLive
import es.topophone.core.survey.VerticalProfileEval
import es.topophone.receiver.platform.Alerts
import io.kotest.matchers.longs.shouldBeLessThan
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlin.math.PI
import kotlin.time.measureTime
import org.junit.Test

/**
 * Un eje de 5.000 tramos (más de 90 km) con una caminata a 10 Hz a lo largo de él: lo que se calcula con cada época del replanteo de
 * taludes (la guía, la punta frente al talud de diseño y los datos del alzado) no pasa de 16 ms, ni de media ni en la peor época
 * (S051, RNF-T13).
 */
class SlopeStakePerformanceTest {

    private val project = Project(
        id = "p1",
        name = "Obra",
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
        heightSystem = HeightSystem.ELLIPSOIDAL,
    )
    private val calculator = ProjectPositionCalculator()
    private val silent = object : Alerts {
        override fun connectionLost() = Unit

        override fun correctionsLost() = Unit

        override fun pointSaved() = Unit

        override fun solutionLost() = Unit

        override fun tiltLost() = Unit
    }

    /** Cada 250 m una recta de 100 m, una clotoide, un arco y otra clotoide alternando el giro; el resto hasta 5.000 tramos, rectas de 10 m. */
    private fun longAxis(): List<AlignmentElement> {
        val elements = ArrayList<AlignmentElement>()
        var chainage = 0.0
        var east = 440_000.0
        var north = 4_474_000.0
        var azimuth = PI / 2
        var sign = 1.0
        val k = 1.0 / 400
        fun add(element: AlignmentElement) {
            elements += element
            val end = ElementGeometry.stateAt(element, element.length)
            east = end.easting
            north = end.northing
            azimuth = end.azimuth
            chainage += element.length
        }
        repeat(200) {
            add(AlignmentElement.line(chainage, 100.0, east, north, azimuth))
            add(AlignmentElement.clothoid(chainage, 50.0, east, north, azimuth, 0.0, sign * k))
            add(AlignmentElement.arc(chainage, 50.0, east, north, azimuth, sign * k))
            add(AlignmentElement.clothoid(chainage, 50.0, east, north, azimuth, sign * k, 0.0))
            sign = -sign
        }
        while (elements.size < 5_000) add(AlignmentElement.line(chainage, 10.0, east, north, azimuth))
        return elements
    }

    private val elements = longAxis()
    private val geometry = AlignmentGeometry(elements)
    private val profile = VerticalProfileEval(
        VerticalProfile(
            listOf(
                VerticalVertex(0.0, 100.0),
                VerticalVertex(geometry.endChainage / 2, 180.0),
                VerticalVertex(geometry.endChainage, 150.0),
            ),
        ),
    )
    private val designChainage = 24_100.0
    private val rule = SlopeRule(SlopeSide.RIGHT, 0.0, 90_000.0, 1.5, 2.0, "BD")
    private val edgePoint = geometry.pointAt(designChainage, 3.5).shouldNotBeNull()
    private val edge = SlopeEdgeResult.Edge(rule, designChainage, 3.5, 130.0, edgePoint)
    private val catch = SlopeCatchResult.Found(SlopeKind.CUT, 1.5, 5.0, 131.0, 1.0, geometry.pointAt(designChainage, 5.0).shouldNotBeNull())
    private val design = run {
        val typed = TypedCoordinates(EntryForm.PROJECT_CRS, catch.point.easting, catch.point.northing, null, HeightKind.PROJECT)
        val coordinates = (
            ManualCoordinates(
                calculator,
            ).compute(typed, project.crs, project.heightSystem) as ManualResult.Computed
            ).coordinates
        StakeTarget("slope:Eje:24100000:RIGHT", "Eje", "", coordinates)
    }

    /** Una época caminando por el eje a 1,2 m/s desde el PK 24.000, 1 m a la derecha. */
    private fun epoch(index: Int): Epoch {
        val point = geometry.pointAt(24_000.0 + index * 0.12, 1.0).shouldNotBeNull()
        val (lat, lon) = CadPlacement.geographic(project.crs, point.easting, point.northing).shouldNotBeNull()
        val height = 700.0 + 1.934
        val fix = GgaFix("t$index", lat, lon, SolutionStatus.FIX, 24, 0.6, height, 0.0, 1.0, "")
        val gst = GstAccuracy("t", 0.008, 0.006, 0.015)
        return Epoch(36_000_000 + index * 100, null, fix, gst, null, null, null, null, null, emptyMap(), emptyMap(), 0)
    }

    private val context = StakeContext(
        StakeoutSettings(),
        design,
        AdmittedSolutions.FIX_ONLY,
        null,
        1.8,
        project,
        calculator,
        designElevationM = catch.heightM,
    )

    private val settings = StakeoutSettings()

    /** Lo que se hace con cada época: la guía, la punta frente al talud y los datos del alzado. */
    private fun perEpoch(controller: StakeoutController, epoch: Epoch) {
        controller.onEpoch(epoch, context)
        val ui = (controller.state.value as GuidanceState.On).ui
        val live = SlopeLive.of(
            edge,
            SlopeKind.CUT,
            catch.point.easting + ui.tipEastM,
            catch.point.northing + ui.tipNorthM,
            catch.heightM + checkNotNull(ui.guidance.dHeightM),
            geometry,
            settings.validToleranceM,
            settings.validToleranceVM,
        )
        SlopeElevation.of(SlopeStakeUiState(outcome = SlopePointOutcome.Found(edge, catch, null), live = live)).shouldNotBeNull()
    }

    @Test
    fun `S051 RNF-T13 un eje de 5000 tramos tiene mas de 90 km`() {
        (elements.size >= 5_000) shouldBe true
        (geometry.endChainage > 90_000.0) shouldBe true
    }

    @Test
    fun `S051 RNF-T13 la guia de un replanteo de taludes a 10 Hz en un eje de 5000 tramos tarda menos de 16 ms por epoca`() {
        val controller = StakeoutController(silent, publishIntervalMs = 0)
        val epochs = List(2_000, ::epoch)
        epochs.take(200).forEach { perEpoch(controller, it) } // calentamiento

        val elapsed = measureTime { epochs.forEach { perEpoch(controller, it) } }

        println("Replanteo de taludes, media por época: ${elapsed.inWholeMicroseconds / epochs.size} µs")
        (elapsed.inWholeMicroseconds / epochs.size) shouldBeLessThan 16_000L
    }

    @Test
    fun `S051 RNF-T13 la peor epoca de una caminata por el eje tampoco pasa de 16 ms`() {
        val controller = StakeoutController(silent, publishIntervalMs = 0)
        val epochs = List(2_000, ::epoch)
        epochs.take(200).forEach { perEpoch(controller, it) } // calentamiento
        var worst = 0L

        for (epoch in epochs) {
            val elapsed = measureTime { perEpoch(controller, epoch) }
            worst = maxOf(worst, elapsed.inWholeMicroseconds)
        }

        println("Replanteo de taludes, peor época: $worst µs")
        worst shouldBeLessThan 16_000L
    }
}
