package es.topophone.core.geodesy

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import kotlin.math.abs
import kotlin.math.cos
import org.junit.jupiter.api.Test

/** Círculo de precisión del rover frente al problema directo de la geodésica de pyproj (S009/RF-17). */
class AccuracyCircleTest {

    @Test
    fun `S009 RF-17 los vertices del circulo coinciden con la geodesica de pyproj a menos de 1 mm`() {
        val vectors = GeodesyFixtures.rows("circulo_precision.csv")
        vectors.size shouldBeGreaterThan 500

        val failures = vectors.mapNotNull { row ->
            val center = GeoPoint(row[0].toDouble(), row[1].toDouble())
            val polygon = AccuracyCircle.polygon(center, radiusM = row[2].toDouble(), segments = 64)
            // Rumbos de 22,5° en 22,5°: uno de cada cuatro vértices del polígono de 64.
            val vertex = polygon[(row[3].toDouble() / 5.625).toInt()]
            val latitude = row[4].toDouble()
            val dNorth = abs(vertex.latitudeDeg - latitude) * METRES_PER_DEGREE
            val dEast = abs(vertex.longitudeDeg - row[5].toDouble()) * METRES_PER_DEGREE * cos(Math.toRadians(latitude))
            if (dNorth > 0.001 ||
                dEast > 0.001
            ) {
                "(${row[0]}, ${row[1]}) r=${row[2]} θ=${row[3]}: ${dNorth * 1000} mm, ${dEast * 1000} mm"
            } else {
                null
            }
        }

        failures.shouldBeEmpty()
    }

    @Test
    fun `S009 RF-17 el poligono tiene los vertices pedidos empezando por el norte`() {
        val center = GeoPoint(40.0, -3.0)

        val polygon = AccuracyCircle.polygon(center, radiusM = 0.012)

        polygon shouldHaveSize 64
        polygon.first().longitudeDeg shouldBe center.longitudeDeg
        (polygon.first().latitudeDeg > center.latitudeDeg) shouldBe true
        shouldThrow<IllegalArgumentException> { AccuracyCircle.polygon(center, radiusM = -1.0) }
    }

    private companion object {
        /** Basta para pasar el error de grados a metros (≈ 111 km por grado). */
        const val METRES_PER_DEGREE = 111_000.0
    }
}
