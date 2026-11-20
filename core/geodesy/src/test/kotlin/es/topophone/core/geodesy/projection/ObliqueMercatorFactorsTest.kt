package es.topophone.core.geodesy.projection

import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import kotlin.math.abs
import org.junit.jupiter.api.Test

/** Factor de escala y convergencia de la Oblique Mercator frente a `get_factors` de pyproj (S032/RF-8; constitución nº 2). */
class ObliqueMercatorFactorsTest {

    private val vectors = OmercVector.all()

    private fun projectionOf(setup: ProjectionSetup): MapProjection = (ProjectionFactory.create(setup) as ProjectionResult.Ok).projection

    @Test
    fun `S032 RF-8 el factor de escala coincide con pyproj a menos de 1e-9`() {
        vectors.size shouldBeGreaterThan 100

        val failures = vectors.mapNotNull { v ->
            val factors = projectionOf(v.setup).factors(v.latitudeDeg, v.longitudeDeg)
            val diff = factors?.let { abs(it.scale - v.scale) }
            if (diff == null || diff > 1e-9) "${v.case} (${v.latitudeDeg}, ${v.longitudeDeg}): Δk=$diff" else null
        }

        withClue(failures.joinToString("\n")) { failures.shouldBeEmpty() }
    }

    @Test
    fun `S032 RF-8 la convergencia coincide con pyproj a menos de 0,01 segundos de arco`() {
        val failures = vectors.mapNotNull { v ->
            val factors = projectionOf(v.setup).factors(v.latitudeDeg, v.longitudeDeg)
            val diffArcSeconds = factors?.let { abs(Math.toDegrees(it.convergenceRad) - v.convergenceDeg) * 3600.0 }
            if (diffArcSeconds == null ||
                diffArcSeconds > 0.01
            ) {
                "${v.case} (${v.latitudeDeg}, ${v.longitudeDeg}): Δγ=$diffArcSeconds″"
            } else {
                null
            }
        }

        withClue(failures.joinToString("\n")) { failures.shouldBeEmpty() }
    }

    @Test
    fun `S032 RF-8 en el centro de la proyeccion el factor de escala es el factor dado`() {
        listOf("EPSG:26931", "EPSG:3375", "EPSG:29873", "b-gamma-distinto", "sur-a", "sur-b").forEach { name ->
            val setup = vectors.first { it.case == name }.setup
            val (latitude, longitude, scale) = when (val parameters = setup.parameters) {
                is ObliqueMercatorA ->
                    Triple(parameters.latitudeOfCentreDeg, parameters.longitudeOfCentreDeg, parameters.scaleFactor)
                is ObliqueMercatorB ->
                    Triple(parameters.latitudeOfCentreDeg, parameters.longitudeOfCentreDeg, parameters.scaleFactor)
                else -> error("no es una OM")
            }

            projectionOf(setup).factors(latitude, longitude + setup.primeMeridianDeg)!!.scale shouldBe scale.plusOrMinus(1e-9)
        }
    }

    @Test
    fun `S032 RF-8 en los polos o con un valor que no es un numero no hay factor`() {
        val projection = projectionOf(vectors.first { it.case == "EPSG:26931" }.setup)

        projection.factors(90.0, 0.0) shouldBe null
        projection.factors(-90.0, 0.0) shouldBe null
        projection.factors(Double.NaN, 0.0) shouldBe null
        projection.factors(55.0, Double.NaN) shouldBe null
        projection.factors(100.0, 0.0) shouldBe null
    }

    @Test
    fun `S032 RF-8 un punto sin imagen no tiene factor`() {
        val sphere = es.topophone.core.geodesy.Ellipsoid(6_371_000.0, Double.POSITIVE_INFINITY)
        val projection = projectionOf(ProjectionSetup(sphere, ObliqueMercatorB(0.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0)))

        projection.factors(0.0, 90.0) shouldBe null
        projection.factors(0.0, 89.0)!!.scale shouldBe (1.0 / kotlin.math.cos(Math.toRadians(89.0))).plusOrMinus(1e-6)
    }
}
