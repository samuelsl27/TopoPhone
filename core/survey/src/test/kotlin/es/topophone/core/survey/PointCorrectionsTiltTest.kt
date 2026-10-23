package es.topophone.core.survey

import es.topophone.core.geodesy.Ecef
import es.topophone.core.geodesy.GeodeticPoint
import es.topophone.core.geodesy.LocalTangentPlane
import es.topophone.core.model.point.GeodeticPosition
import es.topophone.core.model.point.Measurement
import es.topophone.core.model.point.MeasurementMode
import es.topophone.core.model.point.Point
import es.topophone.core.model.point.PointCoordinates
import es.topophone.core.model.point.PointQuality
import es.topophone.core.model.point.PointTilt
import es.topophone.core.model.point.PoleVector
import es.topophone.core.model.point.TiltAngles
import es.topophone.core.model.point.TiltMode
import es.topophone.core.model.project.HeightSystem
import es.topophone.core.model.project.ProjectCrs
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.doubles.shouldBeLessThan
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlin.math.cos
import kotlin.math.sin
import org.junit.jupiter.api.Test

/** Corregir el jalón de un punto medido con inclinación: la punta se mueve a lo largo del jalón (S016/RF-25; plan A6). */
class PointCorrectionsTiltTest {

    private val corrections = PointCorrections()
    private val crs = ProjectCrs.ETRS89_UTM30

    private fun pole(tiltDeg: Double, directionDeg: Double): PoleVector {
        val theta = Math.toRadians(tiltDeg)
        val alpha = Math.toRadians(directionDeg)
        return PoleVector(sin(theta) * sin(alpha), sin(theta) * cos(alpha), cos(theta))
    }

    private fun measured(
        antenna: GeodeticPosition,
        poleHeightM: Double,
        mode: TiltMode,
        pole: PoleVector,
        receiverPoleHeightM: Double? = null,
    ) = Point.measured(
        id = "id-1",
        projectId = "p1",
        name = "P1",
        description = "",
        measurement = Measurement(
            antenna = antenna,
            poleHeightM = poleHeightM,
            quality = PointQuality("FIX", 24, 0.6, 1.0, 0.01, 0.02, MeasurementMode.AVERAGED, 5, 0, fromReplay = false),
            rawNmea = "",
            outOfTolerance = emptySet(),
            measuredFromMs = 0,
            measuredToMs = 0,
            coordinates = PointCoordinates(antenna, null, null, null),
            tilt = PointTilt(
                mode,
                verified = true,
                angles = TiltAngles(0.2, 3.6, null, 0.2, 0.002, 0.002, pole, receiverPoleHeightM),
            ),
        ),
        createdAtMs = 0,
    )

    private fun distanceM(a: GeodeticPosition, b: GeodeticPosition) =
        Ecef.fromGeodetic(a.latitudeDeg, a.longitudeDeg, a.ellipsoidalHeightM!!)
            .distanceTo(Ecef.fromGeodetic(b.latitudeDeg, b.longitudeDeg, b.ellipsoidalHeightM!!))

    @Test
    fun `S016 RF-25 en APP la punta con el jalon nuevo coincide con pyproj`() {
        val rows = SurveyFixtures.rows("punta_jalon.csv")
        rows.size shouldBeGreaterThan 60

        val failures = rows.mapNotNull { row ->
            val antenna = GeodeticPosition(row[0].toDouble(), row[1].toDouble(), row[2].toDouble())
            val newPole = row[5].toDouble() - 0.134
            // Medido con 0,5 m de jalón de más; se corrige al de la fila.
            val point = measured(antenna, newPole + 0.5, TiltMode.APP, pole(row[3].toDouble(), row[4].toDouble()))

            val corrected = corrections.withPoleHeight(point, newPole, crs, HeightSystem.ORTHOMETRIC).shouldNotBeNull().point

            val expected = GeodeticPosition(row[6].toDouble(), row[7].toDouble(), row[8].toDouble())
            val error = distanceM(corrected.coordinates.ground, expected)
            if (error > 0.001) "(${row.take(6)}): ${error * 1000} mm" else null
        }

        failures.shouldBeEmpty()
    }

    @Test
    fun `S016 RF-25 en RECEIVER la punta baja lo que crece el jalon, a lo largo de el`() {
        val tip = GeodeticPosition(40.4168, -3.7038, 699.423)
        val u = pole(20.0, 135.0)
        val point = measured(tip, poleHeightM = 1.8, TiltMode.RECEIVER, u, receiverPoleHeightM = 1.8)

        val corrected = corrections.withPoleHeight(point, 2.0, crs, HeightSystem.ORTHOMETRIC).shouldNotBeNull().point

        val ground = corrected.coordinates.ground
        val moved = LocalTangentPlane.between(
            GeodeticPoint(tip.latitudeDeg, tip.longitudeDeg, tip.ellipsoidalHeightM!!),
            GeodeticPoint(ground.latitudeDeg, ground.longitudeDeg, ground.ellipsoidalHeightM!!),
        )
        moved.east shouldBe (-0.2 * u.east plusOrMinus 1e-7)
        moved.north shouldBe (-0.2 * u.north plusOrMinus 1e-7)
        moved.up shouldBe (-0.2 * u.up plusOrMinus 1e-7)
        corrected.measurement.shouldNotBeNull().poleHeightM shouldBe 2.0
        // El jalón declarado al medir no cambia: volver a 1,8 m deja la punta de la medida.
        val back = corrections.withPoleHeight(corrected, 1.8, crs, HeightSystem.ORTHOMETRIC).shouldNotBeNull().point
        distanceM(back.coordinates.ground, tip) shouldBeLessThan 1e-6
    }

    @Test
    fun `S016 RF-25 en jalon vertical, aunque tenga inclinacion, baja por la normal`() {
        val antenna = GeodeticPosition(40.4168, -3.7038, 701.357)
        val point = measured(antenna, 1.8, TiltMode.VERTICAL, pole(20.0, 135.0))

        val ground = corrections.withPoleHeight(point, 2.0, crs, HeightSystem.ORTHOMETRIC).shouldNotBeNull().point.coordinates.ground

        ground.latitudeDeg shouldBe antenna.latitudeDeg
        ground.longitudeDeg shouldBe antenna.longitudeDeg
        ground.ellipsoidalHeightM.shouldNotBeNull() shouldBe ((701.357 - 2.134) plusOrMinus 1e-9)
    }

    @Test
    fun `S016 RF-27 sin altura la punta corregida sigue sin altura`() {
        val antenna = GeodeticPosition(40.4168, -3.7038, null)
        val point = measured(antenna, 1.8, TiltMode.APP, pole(20.0, 135.0))

        val corrected = corrections.withPoleHeight(point, 2.0, crs, HeightSystem.ORTHOMETRIC).shouldNotBeNull().point

        corrected.coordinates.ground.ellipsoidalHeightM.shouldBeNull()
        corrected.coordinates.height.shouldBeNull()
    }
}
