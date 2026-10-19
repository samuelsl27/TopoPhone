package es.topophone.core.survey

import es.topophone.core.geodesy.Ecef
import es.topophone.core.gnss.nmea.GgaFix
import es.topophone.core.gnss.nmea.SolutionStatus
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import java.io.File
import org.junit.jupiter.api.Test

/** Línea base: distancia en línea recta entre la antena del rover y la base, frente a pyproj (S015/RF-14, RF-15). */
class BaselineTest {

    private val vectors: List<List<Double>> by lazy {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }
            .first { File(it, "settings.gradle.kts").exists() && File(it, "fixtures").isDirectory }
        File(root, "fixtures/geodesia/linea_base.csv").readLines(Charsets.UTF_8)
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .drop(1)
            .map { line -> line.split(',').map { it.toDouble() } }
    }

    private fun gga(lat: Double, lon: Double, altitude: Double?, separation: Double?) =
        GgaFix("101530.20", lat, lon, SolutionStatus.FIX, 24, 0.6, altitude, separation, 1.0, "")

    @Test
    fun `S015 RF-14 la linea base coincide con pyproj a menos de 1 mm`() {
        vectors.size shouldBeGreaterThan 20

        vectors.forEach { v ->
            val base = Ecef.fromGeodetic(v[3], v[4], v[5])

            Baseline.distanceM(v[0], v[1], v[2], base) shouldBe (v[6] plusOrMinus 0.001)
        }
    }

    @Test
    fun `S015 RF-14 la antena de la GGA tiene la altura elipsoidal de altitud mas separacion (ADR-6)`() {
        val v = vectors.first { it[6] > 1_000 }
        val base = Ecef.fromGeodetic(v[3], v[4], v[5])

        Baseline.fromGga(gga(v[0], v[1], altitude = v[2] - 51.234, separation = 51.234), base) shouldBe (v[6] plusOrMinus 0.001)
        Baseline.fromGga(gga(v[0], v[1], altitude = null, separation = 51.234), base).shouldBeNull()
        Baseline.fromGga(gga(v[0], v[1], altitude = 100.0, separation = null), base).shouldBeNull()
    }

    @Test
    fun `S015 RF-15 la linea base es larga a partir de 20 km`() {
        Baseline.isLong(20_000.0) shouldBe false
        Baseline.isLong(20_000.001) shouldBe true
    }
}
