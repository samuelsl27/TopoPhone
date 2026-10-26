package es.topophone.core.survey

import es.topophone.core.model.point.GearUsed
import es.topophone.core.model.point.GeodeticPosition
import es.topophone.core.model.point.Measurement
import es.topophone.core.model.point.MeasurementMode
import es.topophone.core.model.point.Point
import es.topophone.core.model.point.PointCoordinates
import es.topophone.core.model.point.PointQuality
import es.topophone.core.model.point.PoleKind
import es.topophone.core.model.project.HeightSystem
import es.topophone.core.model.project.ProjectCrs
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** Corregir el jalón de un punto usa el equipo con el que se midió (S046/RF-24, RF-25; S011/RF-14). */
class PointCorrectionsGearTest {

    private val corrections = PointCorrections()
    private val antenna = GeodeticPosition(40.41668, -3.70371, 701.357)
    private val sol = PointCoordinates(GeodeticPosition(40.41668, -3.70371, 699.423), 440_297.988, 4_474_244.001, 648.311)

    private fun measured(gear: GearUsed?) = Point.measured(
        id = "id-1",
        projectId = "p1",
        name = "P12",
        description = "",
        measurement = Measurement(
            antenna = antenna,
            poleHeightM = 1.8,
            quality = PointQuality("FIX", 24, 0.6, 1.0, 0.009, 0.014, MeasurementMode.INSTANT, 1, 0, fromReplay = false),
            rawNmea = "",
            outOfTolerance = emptySet(),
            measuredFromMs = 1_000,
            measuredToMs = 1_000,
            coordinates = sol,
            gear = gear,
        ),
        createdAtMs = 1_000,
    )

    private fun correct(point: Point, poleM: Double) = corrections.withPoleHeight(
        point,
        poleM,
        ProjectCrs.ETRS89_UTM30,
        HeightSystem.ORTHOMETRIC,
    ).shouldNotBeNull().point.measurement.shouldNotBeNull()

    @Test
    fun `S046 RF-24 un punto medido con otro perfil se corrige con el desplazamiento de ese perfil`() {
        val rs4 = GearUsed("Jalón", PoleKind.VERTICAL, 1.8, "Reach RS4 y RS4 Pro", 0.095, 1.895)

        val corrected = correct(measured(rs4), 2.0)

        corrected.coordinates.ground.ellipsoidalHeightM.shouldNotBeNull() shouldBe (701.357 - 2.095 plusOrMinus 1e-9)
    }

    @Test
    fun `S046 RF-24 la correccion deja el jalon como vertical y recalcula la altura de antena`() {
        val slant = GearUsed("Inclinado", PoleKind.SLANT, 1.95, "Propio", 0.12, 2.069076)

        val gear = correct(measured(slant), 2.0).gear.shouldNotBeNull()

        gear shouldBe GearUsed("Inclinado", PoleKind.VERTICAL, 2.0, "Propio", 0.12, 2.12)
    }

    @Test
    fun `S046 RF-29 un punto de antes de S046 se corrige con el desplazamiento del RS3 y sigue sin equipo`() {
        val corrected = correct(measured(null), 2.0)

        corrected.coordinates.ground.ellipsoidalHeightM.shouldNotBeNull() shouldBe (701.357 - 2.134 plusOrMinus 1e-9)
        corrected.gear.shouldBeNull()
    }
}
