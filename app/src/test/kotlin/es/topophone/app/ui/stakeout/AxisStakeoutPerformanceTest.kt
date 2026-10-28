package es.topophone.app.ui.stakeout

import es.topophone.core.gnss.nmea.Epoch
import es.topophone.core.gnss.nmea.GgaFix
import es.topophone.core.gnss.nmea.GstAccuracy
import es.topophone.core.gnss.nmea.SolutionStatus
import es.topophone.core.model.alignment.AlignmentElement
import es.topophone.core.model.alignment.VerticalProfile
import es.topophone.core.model.alignment.VerticalVertex
import es.topophone.core.model.point.AdmittedSolutions
import es.topophone.core.model.point.AxisHeightRef
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
import es.topophone.core.survey.AxisAnalysis
import es.topophone.core.survey.AxisGuidance
import es.topophone.core.survey.AxisStakeResult
import es.topophone.core.survey.AxisStakeTarget
import es.topophone.core.survey.CadPlacement
import es.topophone.core.survey.ElementGeometry
import es.topophone.core.survey.ManualCoordinates
import es.topophone.core.survey.ManualResult
import es.topophone.core.survey.ProjectPositionCalculator
import es.topophone.core.survey.VerticalProfileEval
import es.topophone.receiver.platform.Alerts
import io.kotest.matchers.longs.shouldBeLessThan
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.math.PI
import kotlin.time.measureTime
import org.junit.Test

/**
 * Un eje de 5.000 tramos (más de 90 km) con una caminata a 10 Hz a lo largo de él: lo que se calcula con cada época del replanteo por PK
 * (la guía, el punto de diseño, el PK y la guía a lo largo y a través) y del análisis no pasa de 16 ms, ni de media ni en la peor época
 * (S054, RNF-T13).
 */
class AxisStakeoutPerformanceTest {

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
    private val target = AxisStakeTarget.of(geometry, profile, designChainage, 3.5, 0.1).shouldBeInstanceOf<AxisStakeResult.Target>()
    private val design = run {
        val typed = TypedCoordinates(EntryForm.PROJECT_CRS, target.point.easting, target.point.northing, null, HeightKind.PROJECT)
        val coordinates = (
            ManualCoordinates(
                calculator,
            ).compute(typed, project.crs, project.heightSystem) as ManualResult.Computed
            ).coordinates
        StakeTarget("axis:Eje:24100000", "Eje", "", coordinates)
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
        designElevationM = target.elevationM,
    )

    /** Lo que se hace con cada época: la guía, el punto de diseño del estado, el PK de la punta y la guía a lo largo y a través. */
    private fun perEpoch(controller: StakeoutController, epoch: Epoch) {
        controller.onEpoch(epoch, context)
        val ui = (controller.state.value as GuidanceState.On).ui
        val again = AxisStakeTarget.of(geometry, profile, designChainage, 3.5, 0.1).shouldBeInstanceOf<AxisStakeResult.Target>()
        val tipE = again.point.easting + ui.tipEastM
        val tipN = again.point.northing + ui.tipNorthM
        geometry.locate(tipE, tipN).shouldNotBeNull()
        AxisGuidance.of(again, tipE, tipN)
        AxisAnalysis.of(geometry, profile, AxisHeightRef.PROFILE, null, tipE, tipN, 700.0)
    }

    @Test
    fun `S054 RNF-T13 un eje de 5000 tramos tiene mas de 90 km`() {
        (elements.size >= 5_000) shouldBe true
        (geometry.endChainage > 90_000.0) shouldBe true
    }

    @Test
    fun `S054 RNF-T13 la guia de un replanteo por PK a 10 Hz en un eje de 5000 tramos tarda menos de 16 ms por epoca`() {
        val controller = StakeoutController(silent, publishIntervalMs = 0)
        val epochs = List(2_000, ::epoch)
        epochs.take(200).forEach { perEpoch(controller, it) } // calentamiento

        val elapsed = measureTime { epochs.forEach { perEpoch(controller, it) } }

        println("Replanteo por PK, media por época: ${elapsed.inWholeMicroseconds / epochs.size} µs")
        (elapsed.inWholeMicroseconds / epochs.size) shouldBeLessThan 16_000L
    }

    @Test
    fun `S054 RNF-T13 la peor epoca de una caminata por el eje tampoco pasa de 16 ms`() {
        val controller = StakeoutController(silent, publishIntervalMs = 0)
        val epochs = List(2_000, ::epoch)
        epochs.take(200).forEach { perEpoch(controller, it) } // calentamiento
        var worst = 0L

        for (epoch in epochs) {
            val elapsed = measureTime { perEpoch(controller, epoch) }
            worst = maxOf(worst, elapsed.inWholeMicroseconds)
        }

        println("Replanteo por PK, peor época: $worst µs")
        worst shouldBeLessThan 16_000L
    }
}
