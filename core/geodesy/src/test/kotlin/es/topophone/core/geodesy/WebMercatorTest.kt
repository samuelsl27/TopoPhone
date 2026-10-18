package es.topophone.core.geodesy

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.doubles.shouldBeLessThan
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import kotlin.math.abs
import org.junit.jupiter.api.Test

/** Web Mercator (EPSG:3857) del mapa y metros por píxel frente a pyproj (S009/RF-12, RF-19; constitución nº 2). */
class WebMercatorTest {

    private val vectors = GeodesyFixtures.rows("web_mercator.csv")

    @Test
    fun `S009 RF-19 las coordenadas del mapa coinciden con pyproj EPSG 3857 a menos de 1 mm`() {
        vectors.size shouldBeGreaterThan 100

        val failures = vectors.mapNotNull { row ->
            val (x, y) = WebMercator.forward(row[0].toDouble(), row[1].toDouble())
            val dx = abs(x - row[2].toDouble())
            val dy = abs(y - row[3].toDouble())
            if (dx > 0.001 || dy > 0.001) "(${row[0]}, ${row[1]}): Δx=${dx * 1000} mm, Δy=${dy * 1000} mm" else null
        }

        failures.shouldBeEmpty()
    }

    @Test
    fun `S009 RF-19 la inversa vuelve al punto de partida`() {
        vectors.forEach { row ->
            val back = WebMercator.inverse(row[2].toDouble(), row[3].toDouble())
            back.latitudeDeg shouldBe (row[0].toDouble() plusOrMinus 1e-9)
            back.longitudeDeg shouldBe (row[1].toDouble() plusOrMinus 1e-9)
        }
    }

    @Test
    fun `S009 RF-12 los metros del terreno por pixel coinciden con la geodesica de pyproj`() {
        val failures = vectors.mapNotNull { row ->
            val expected = row[5].toDouble()
            val metres = WebMercator.metresPerPixel(row[0].toDouble(), zoom = row[4].toDouble())
            val relative = abs(metres - expected) / expected
            if (relative > 1e-6) "(${row[0]}, z=${row[4]}): ${relative * 1e6} ppm" else null
        }

        failures.shouldBeEmpty()
    }

    @Test
    fun `S009 RF-12 con el zoom maximo 100 px miden menos de 1,7 m en toda España`() {
        listOf(27.0, 28.5, 36.0, 40.4, 43.8).forEach { latitude ->
            100 * WebMercator.metresPerPixel(latitude, WebMercator.MAX_ZOOM) shouldBeLessThan 1.7
        }
    }

    @Test
    fun `S009 RF-19 el zoom para 1 2000 da esa escala nominal`() {
        listOf(0.0, 28.4636, 40.41668, 43.8).forEach { latitude ->
            val zoom = WebMercator.zoomForScale(2_000.0, latitude)
            WebMercator.scaleDenominator(latitude, zoom) shouldBe (2_000.0 plusOrMinus 1e-6)
        }
        // En Madrid, 1:2.000 es un zoom de unos 17,5.
        WebMercator.zoomForScale(2_000.0, 40.41668) shouldBe (17.5 plusOrMinus 0.1)
    }
}
