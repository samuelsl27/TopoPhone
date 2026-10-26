package es.topophone.core.survey

import es.topophone.core.geodesy.Ecef
import es.topophone.core.gnss.nmea.GgaFix
import es.topophone.core.gnss.nmea.SolutionStatus
import es.topophone.core.model.point.GeodeticPosition
import es.topophone.core.model.point.PoleKind
import es.topophone.core.model.point.PoleVector
import es.topophone.core.model.point.TiltMode
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlin.math.cos
import kotlin.math.sin
import org.junit.jupiter.api.Test

/** La punta del jalón con cada perfil de antena, frente a `fixtures/antena/` calculado con pyproj (S046/RF-20, RF-21, RF-22). */
class AntennaProfileTipTest {

    private val rows = SurveyFixtures.rows("punta_perfiles.csv", "antena").filter { it[5] == "OK" }

    private fun profile(row: List<String>) = AntennaProfile(
        "own:test",
        row[0],
        row[1].toDouble(),
        row[4].takeIf {
            it.isNotEmpty()
        }?.toDouble(),
        false,
    )

    private fun gear(row: List<String>): ActiveGear {
        val pole = SavedPole("p", "jalón", PoleKind.valueOf(row[2]), row[3].toDouble())
        return GearResolver.resolve("own:test", listOf(profile(row)), "p", listOf(pole))
    }

    private fun distance(a: GeodeticPosition, b: GeodeticPosition) =
        Ecef.fromGeodetic(a.latitudeDeg, a.longitudeDeg, a.ellipsoidalHeightM!!)
            .distanceTo(Ecef.fromGeodetic(b.latitudeDeg, b.longitudeDeg, b.ellipsoidalHeightM!!))

    @Test
    fun `S046 RF-20 la punta inclinada con cada perfil coincide con pyproj a menos de 1 mm`() {
        rows.size shouldBeGreaterThan 100
        val failures = rows.mapNotNull { row ->
            val antennaHeight = gear(row).antennaHeightM.shouldNotBeNull()
            val theta = Math.toRadians(row[11].toDouble())
            val alpha = Math.toRadians(row[12].toDouble())
            val pole = PoleVector(sin(theta) * sin(alpha), sin(theta) * cos(alpha), cos(theta))
            val tip = PoleTip.fromAntenna(GeodeticPosition(row[8].toDouble(), row[9].toDouble(), row[10].toDouble()), pole, antennaHeight)
            val expected = GeodeticPosition(row[13].toDouble(), row[14].toDouble(), row[15].toDouble())
            val error = distance(tip, expected)
            if (error > 0.001) "(${row.take(6)}): ${error * 1000} mm" else null
        }
        failures.shouldBeEmpty()
    }

    @Test
    fun `S046 RF-20 el jalon vertical en directo con cada perfil da la punta del fixture`() {
        val vertical = rows.filter { it[11].toDouble() == 0.0 }
        vertical.size shouldBeGreaterThan 50
        vertical.forEach { row ->
            val g = gear(row)
            // GGA sin geoide: altitud elipsoidal = altitud + separación (0).
            val fix =
                GgaFix("101530.20", row[8].toDouble(), row[9].toDouble(), SolutionStatus.FIX, 24, 0.6, row[10].toDouble(), 0.0, 1.0, "")
            val live = LiveTip.of(fix, null, TiltMode.VERTICAL, g.verticalPoleM!!, g.profile).shouldNotBeNull()
            live.ground.ellipsoidalHeightM.shouldNotBeNull() shouldBe (row[15].toDouble() plusOrMinus 1e-3)
        }
    }

    @Test
    fun `S046 RF-21 el RS3 con 1,8 m da la misma punta que antes`() {
        val fix = GgaFix("101530.20", 40.4168, -3.7038, SolutionStatus.FIX, 24, 0.6, 650.123, 51.234, 1.0, "")
        val live = LiveTip.of(fix, null, TiltMode.VERTICAL, 1.8).shouldNotBeNull()
        live.ground.ellipsoidalHeightM.shouldNotBeNull() shouldBe (650.123 + 51.234 - 1.934 plusOrMinus 1e-9)
        LiveTip.of(fix, null, TiltMode.VERTICAL, 1.8, AntennaProfile.RS3) shouldBe live
    }

    @Test
    fun `S046 RF-20 otro perfil cambia la cota del suelo en su desplazamiento`() {
        val fix = GgaFix("101530.20", 40.4168, -3.7038, SolutionStatus.FIX, 24, 0.6, 650.123, 51.234, 1.0, "")
        val rs4 = AntennaCatalog.ALL.first { it.ref == "catalog:RS4" }
        val live = LiveTip.of(fix, null, TiltMode.VERTICAL, 1.8, rs4).shouldNotBeNull()
        live.ground.ellipsoidalHeightM.shouldNotBeNull() shouldBe (650.123 + 51.234 - 1.895 plusOrMinus 1e-9)
    }

    @Test
    fun `S046 RF-22 con el receptor compensando la posicion no resta ninguna altura de antena`() {
        val fix = GgaFix("101530.20", 40.4168, -3.7038, SolutionStatus.FIX, 24, 0.6, 650.123, 51.234, 1.0, "")
        val rs4 = AntennaCatalog.ALL.first { it.ref == "catalog:RS4" }
        val live = LiveTip.of(fix, null, TiltMode.RECEIVER, 1.8, rs4).shouldNotBeNull()
        live.ground.ellipsoidalHeightM.shouldNotBeNull() shouldBe (650.123 + 51.234 plusOrMinus 1e-9)
    }

    @Test
    fun `S046 RF-20 el calculador del punto del suelo usa el desplazamiento del perfil`() {
        val rs4 = AntennaCatalog.ALL.first { it.ref == "catalog:RS4" }
        val fix = GgaFix("101530.20", 40.4168, -3.7038, SolutionStatus.FIX, 24, 0.6, 650.123, 51.234, 1.0, "")
        val rs3 = GroundPointCalculator().compute(fix, 1.8).shouldNotBeNull()
        val other = GroundPointCalculator(profile = rs4).compute(fix, 1.8).shouldNotBeNull()
        (other.ellipsoidalHeightM!! - rs3.ellipsoidalHeightM!!) shouldBe (0.134 - 0.095 plusOrMinus 1e-9)
    }
}
