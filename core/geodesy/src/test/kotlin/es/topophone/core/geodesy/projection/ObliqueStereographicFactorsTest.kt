package es.topophone.core.geodesy.projection

import es.topophone.core.geodesy.Ellipsoid
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import kotlin.math.abs
import org.junit.jupiter.api.Test

/** Factor de escala y convergencia de la Estereográfica oblicua frente a `get_factors` de pyproj (S032/RF-11; constitución nº 2). */
class ObliqueStereographicFactorsTest {

    private val vectors = StereoObliqueVector.all()

    private fun projectionOf(setup: ProjectionSetup): MapProjection = (ProjectionFactory.create(setup) as ProjectionResult.Ok).projection

    @Test
    fun `S032 RF-11 el factor de escala coincide con pyproj a menos de 1e-9`() {
        vectors.size shouldBeGreaterThan 100

        val failures = vectors.mapNotNull { v ->
            val factors = projectionOf(v.setup).factors(v.latitudeDeg, v.longitudeDeg)
            val diff = factors?.let { abs(it.scale - v.scale) }
            if (diff == null || diff > 1e-9) "${v.case} (${v.latitudeDeg}, ${v.longitudeDeg}): Δk=$diff" else null
        }

        withClue(failures.joinToString("\n")) { failures.shouldBeEmpty() }
    }

    @Test
    fun `S032 RF-11 la convergencia coincide con pyproj a menos de 0,01 segundos de arco`() {
        val failures = vectors.mapNotNull { v ->
            val factors = projectionOf(v.setup).factors(v.latitudeDeg, v.longitudeDeg)
            val diffArcSeconds = factors?.let { abs(Math.toDegrees(it.convergenceRad) - v.convergenceDeg) * 3600.0 }
            if (diffArcSeconds == null || diffArcSeconds > 0.01) {
                "${v.case} (${v.latitudeDeg}, ${v.longitudeDeg}): Δγ=$diffArcSeconds″"
            } else {
                null
            }
        }

        withClue(failures.joinToString("\n")) { failures.shouldBeEmpty() }
    }

    @Test
    fun `S032 RF-11 en el origen el factor de escala es el factor dado y la convergencia es nula`() {
        vectors.map { it.case }.distinct().forEach { name ->
            val setup = vectors.first { it.case == name }.setup
            val parameters = setup.parameters as ObliqueStereographic

            val originLongitude = parameters.longitudeOfOriginDeg + setup.primeMeridianDeg
            val factors = projectionOf(setup).factors(parameters.latitudeOfOriginDeg, originLongitude)!!

            factors.scale shouldBe parameters.scaleFactor.plusOrMinus(1e-12)
            factors.convergenceRad shouldBe 0.0.plusOrMinus(1e-12)
        }
    }

    @Test
    fun `S032 RF-11 en los polos o con un valor que no es un numero no hay factor`() {
        val projection = projectionOf(vectors.first { it.case == "EPSG:28992" }.setup)

        projection.factors(90.0, 0.0) shouldBe null
        projection.factors(-90.0, 0.0) shouldBe null
        projection.factors(Double.NaN, 0.0) shouldBe null
        projection.factors(50.0, Double.NaN) shouldBe null
        projection.factors(100.0, 0.0) shouldBe null
    }

    @Test
    fun `S032 RF-11 la antipoda del origen no tiene factor`() {
        val sphere = Ellipsoid(6_371_000.0, Double.POSITIVE_INFINITY)
        val projection = projectionOf(ProjectionSetup(sphere, ObliqueStereographic(40.0, 10.0, 1.0, 0.0, 0.0)))

        projection.factors(-40.0, -170.0) shouldBe null
    }
}
