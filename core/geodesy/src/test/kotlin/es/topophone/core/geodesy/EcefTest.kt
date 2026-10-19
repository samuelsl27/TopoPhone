package es.topophone.core.geodesy

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import kotlin.math.abs
import org.junit.jupiter.api.Test

/** Geográficas con altura elipsoidal ↔ geocéntricas sobre GRS80, frente a pyproj (S015/RF-13, RF-14; constitución nº 2). */
class EcefTest {

    private val vectors = GeodesyFixtures.rows("ecef.csv")

    @Test
    fun `S015 RF-13 las geocentricas coinciden con pyproj a menos de 1 mm`() {
        vectors.size shouldBeGreaterThan 50

        val failures = vectors.mapNotNull { row ->
            val ecef = Ecef.fromGeodetic(row[0].toDouble(), row[1].toDouble(), row[2].toDouble())
            val d = maxOf(abs(ecef.x - row[3].toDouble()), abs(ecef.y - row[4].toDouble()), abs(ecef.z - row[5].toDouble()))
            if (d > 0.001) "(${row[0]}, ${row[1]}, ${row[2]}): ${d * 1000} mm" else null
        }

        failures.shouldBeEmpty()
    }

    @Test
    fun `S015 RF-13 las geograficas desde las geocentricas coinciden con pyproj`() {
        val failures = vectors.mapNotNull { row ->
            val point = Ecef(row[3].toDouble(), row[4].toDouble(), row[5].toDouble()).toGeodetic()
            val dLat = abs(point.latitudeDeg - row[0].toDouble())
            val dLon = abs(point.longitudeDeg - row[1].toDouble())
            val dH = abs(point.ellipsoidalHeightM - row[2].toDouble())
            val outside = dLat > 1e-9 || dLon > 1e-9 || dH > 0.001
            if (outside) "(${row[3]}, ${row[4]}, ${row[5]}): Δφ=$dLat° Δλ=$dLon° Δh=${dH * 1000} mm" else null
        }

        failures.shouldBeEmpty()
    }

    @Test
    fun `S015 RF-13 ida y vuelta a menos de 0,1 mm`() {
        vectors.forEach { row ->
            val ecef = Ecef(row[3].toDouble(), row[4].toDouble(), row[5].toDouble())
            val point = ecef.toGeodetic()

            Ecef.fromGeodetic(point.latitudeDeg, point.longitudeDeg, point.ellipsoidalHeightM).distanceTo(ecef) shouldBe
                (0.0 plusOrMinus 1e-4)
        }
    }

    @Test
    fun `S015 RF-13 la estacion AVL13M del 1006 real esta en Aviles`() {
        val point = Ecef(4_604_889.1789, -476_930.7239, 4_372_677.1220).toGeodetic()

        point.latitudeDeg shouldBe (43.5578666032 plusOrMinus 1e-9)
        point.longitudeDeg shouldBe (-5.9130704113 plusOrMinus 1e-9)
        point.ellipsoidalHeightM shouldBe (78.4338 plusOrMinus 0.001)
    }

    @Test
    fun `S015 RF-14 la distancia entre geocentricas coincide con pyproj`() {
        val baselines = GeodesyFixtures.rows("linea_base.csv")
        baselines.size shouldBeGreaterThan 20

        baselines.forEach { row ->
            val rover = Ecef.fromGeodetic(row[0].toDouble(), row[1].toDouble(), row[2].toDouble())
            val base = Ecef.fromGeodetic(row[3].toDouble(), row[4].toDouble(), row[5].toDouble())

            rover.distanceTo(base) shouldBe (row[6].toDouble() plusOrMinus 0.001)
        }
    }
}
