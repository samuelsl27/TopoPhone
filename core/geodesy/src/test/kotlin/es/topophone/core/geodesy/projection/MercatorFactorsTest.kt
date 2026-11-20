package es.topophone.core.geodesy.projection

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.doubles.shouldBeLessThan
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import kotlin.math.abs
import org.junit.jupiter.api.Test

/** Factor de escala y convergencia de la Mercator frente a `get_factors` de pyproj (S032/RF-5; constitución nº 2). */
class MercatorFactorsTest {

    private val vectors = MercatorVector.all()

    private fun projectionOf(setup: ProjectionSetup): MapProjection = (ProjectionFactory.create(setup) as ProjectionResult.Ok).projection

    @Test
    fun `S032 RF-5 el factor de escala coincide con pyproj a menos de 1e-9`() {
        vectors.size shouldBeGreaterThan 40

        val failures = vectors.mapNotNull { v ->
            val factors = projectionOf(v.setup).factors(v.latitudeDeg, v.longitudeDeg)
            val diff = factors?.let { abs(it.scale - v.scale) }
            if (diff == null || diff > 1e-9) "${v.case} (${v.latitudeDeg}, ${v.longitudeDeg}): Δk=$diff" else null
        }

        failures.shouldBeEmpty()
    }

    @Test
    fun `S032 RF-5 la convergencia es nula en todos los puntos`() {
        vectors.forEach { v ->
            projectionOf(v.setup).factors(v.latitudeDeg, v.longitudeDeg)!!.convergenceRad shouldBe 0.0
        }
    }

    @Test
    fun `S032 RF-5 en el paralelo estandar de la B la escala es 1 y en el ecuador de la A es k0`() {
        val b = vectors.first { it.case == "EPSG:3388" }.setup
        val a = vectors.first { it.case == "EPSG:3002" }.setup

        abs(projectionOf(b).factors(42.0, 51.0)!!.scale - 1.0) shouldBeLessThan 1e-12
        abs(projectionOf(a).factors(0.0, 110.0)!!.scale - 0.997) shouldBeLessThan 1e-12
    }

    @Test
    fun `S032 RF-5 en los polos o con un valor que no es un numero no hay factor`() {
        val projection = projectionOf(vectors.first { it.case == "EPSG:3395" }.setup)

        projection.factors(90.0, 0.0) shouldBe null
        projection.factors(-90.0, 0.0) shouldBe null
        projection.factors(Double.NaN, 0.0) shouldBe null
        projection.factors(45.0, Double.NaN) shouldBe null
        projection.factors(100.0, 0.0) shouldBe null
    }
}
