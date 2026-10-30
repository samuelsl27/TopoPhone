package es.topophone.core.survey

import es.topophone.core.model.point.AppliedShift
import es.topophone.core.model.point.BaseShiftVector
import es.topophone.core.model.point.GeodeticPosition
import es.topophone.core.model.point.Measurement
import es.topophone.core.model.point.MeasurementMode
import es.topophone.core.model.point.Point
import es.topophone.core.model.point.PointCoordinates
import es.topophone.core.model.point.PointQuality
import es.topophone.core.model.project.HeightSystem
import es.topophone.core.model.project.ProjectCrs
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** Corregir el jalón de un punto con desplazamiento usa el vector que ese punto guardó (S020/RF-10). */
class PointCorrectionsShiftTest {

    private val corrections = PointCorrections()
    private val crs = ProjectCrs.ETRS89_UTM30
    private val heights = HeightSystem.ORTHOMETRIC
    private val antenna = GeodeticPosition(40.41668, -3.70371, 701.357)
    private val stored = BaseShiftVector(2.1, -1.7, 0.4)
    private val other = BaseShiftVector(-5.0, 3.0, 1.0)

    private fun point(shift: AppliedShift?): Point {
        val unshifted = GeodeticPosition(antenna.latitudeDeg, antenna.longitudeDeg, antenna.ellipsoidalHeightM!! - 1.934)
        val ground = if (shift != null) BaseShiftApplier.apply(unshifted, shift.vector) else unshifted
        val position = ProjectPositionCalculator().fromGround(ground, crs, heights)
        return Point.measured(
            id = "id-1",
            projectId = "p1",
            name = "P1",
            description = "",
            measurement = Measurement(
                antenna = antenna,
                poleHeightM = 1.8,
                quality = PointQuality("FIX", 24, 0.6, 1.0, 0.009, 0.014, MeasurementMode.INSTANT, 1, 0, fromReplay = false),
                rawNmea = "",
                outOfTolerance = emptySet(),
                measuredFromMs = 0,
                measuredToMs = 0,
                coordinates = PointCoordinates(ground, position.easting, position.northing, position.height),
                shift = shift,
            ),
            createdAtMs = 1,
        )
    }

    private fun applied(vector: BaseShiftVector, unshifted: GeodeticPosition) = AppliedShift("s1", "V-12", vector, unshifted)

    private val unshifted = GeodeticPosition(antenna.latitudeDeg, antenna.longitudeDeg, antenna.ellipsoidalHeightM!! - 1.934)

    @Test
    fun `S020 RF-10 el suelo corregido lleva el vector que guardo el punto`() {
        val shifted = point(applied(stored, unshifted))

        val corrected = corrections.withPoleHeight(shifted, 2.0, crs, heights).shouldNotBeNull().point.measurement.shouldNotBeNull()

        val rawGround = GeodeticPosition(antenna.latitudeDeg, antenna.longitudeDeg, antenna.ellipsoidalHeightM!! - 2.134)
        corrected.coordinates.ground shouldBe BaseShiftApplier.apply(rawGround, stored)
        corrected.shift.shouldNotBeNull().unshiftedGround shouldBe rawGround
        corrected.shift.shouldNotBeNull().vector shouldBe stored
    }

    @Test
    fun `S020 RF-10 las coordenadas del SC se recalculan con el suelo desplazado`() {
        val shifted = point(applied(stored, unshifted))

        val corrected = corrections.withPoleHeight(shifted, 2.0, crs, heights).shouldNotBeNull().point.measurement.shouldNotBeNull()

        val expected = ProjectPositionCalculator().fromGround(corrected.coordinates.ground, crs, heights)
        corrected.coordinates.easting shouldBe expected.easting
        corrected.coordinates.northing shouldBe expected.northing
        corrected.coordinates.height shouldBe expected.height
    }

    @Test
    fun `S020 RF-10 un punto sin desplazamiento se corrige como siempre`() {
        val plain = point(null)

        val corrected = corrections.withPoleHeight(plain, 2.0, crs, heights).shouldNotBeNull().point.measurement.shouldNotBeNull()

        corrected.shift.shouldBeNull()
        corrected.coordinates.ground.ellipsoidalHeightM.shouldNotBeNull()
    }

    @Test
    fun `S020 RF-10 otro vector distinto del guardado no entra en el calculo`() {
        val shifted = point(applied(stored, unshifted))
        val viaStored = corrections.withPoleHeight(shifted, 2.0, crs, heights).shouldNotBeNull().point.measurement.shouldNotBeNull()

        val viaOther = corrections.withPoleHeight(point(applied(other, unshifted)), 2.0, crs, heights).shouldNotBeNull()
            .point.measurement.shouldNotBeNull()

        (viaStored.coordinates.ground == viaOther.coordinates.ground) shouldBe false
    }
}
