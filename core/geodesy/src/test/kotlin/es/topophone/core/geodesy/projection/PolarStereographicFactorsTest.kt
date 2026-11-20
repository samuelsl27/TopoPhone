package es.topophone.core.geodesy.projection

import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import kotlin.math.abs
import org.junit.jupiter.api.Test

/** Factor de escala y convergencia de la Estereográfica polar frente a `get_factors` de pyproj (S032/RF-14; constitución nº 2). */
class PolarStereographicFactorsTest {

    private val vectors = StereoPolarVector.all()

    private fun projectionOf(setup: ProjectionSetup): MapProjection = (ProjectionFactory.create(setup) as ProjectionResult.Ok).projection

    private fun setupOf(case: String) = vectors.first { it.case == case }.setup

    @Test
    fun `S032 RF-14 el factor de escala coincide con pyproj a menos de 1e-9`() {
        vectors.size shouldBeGreaterThan 500

        val failures = vectors.mapNotNull { v ->
            val factors = projectionOf(v.setup).factors(v.latitudeDeg, v.longitudeDeg)
            val diff = factors?.let { abs(it.scale - v.scale) }
            if (diff == null || diff > 1e-9) "${v.case} (${v.latitudeDeg}, ${v.longitudeDeg}): Δk=$diff" else null
        }

        withClue(failures.joinToString("\n")) { failures.shouldBeEmpty() }
    }

    @Test
    fun `S032 RF-14 la convergencia coincide con pyproj a menos de 0,01 segundos de arco`() {
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
    fun `S032 RF-14 en el paralelo estandar de la B y de la C la escala es 1`() {
        abs(projectionOf(setupOf("EPSG:3031")).factors(-71.0, 40.0)!!.scale - 1.0) shouldBe (0.0).plusOrMinus(1e-12)
        abs(projectionOf(setupOf("EPSG:3413")).factors(70.0, -100.0)!!.scale - 1.0) shouldBe (0.0).plusOrMinus(1e-12)
        abs(projectionOf(setupOf("EPSG:2985")).factors(-67.0, 10.0)!!.scale - 1.0) shouldBe (0.0).plusOrMinus(1e-12)
    }

    @Test
    fun `S032 RF-14 cerca del polo la escala de la A es el factor del polo`() {
        projectionOf(setupOf("EPSG:5041")).factors(89.999999, 20.0)!!.scale shouldBe 0.994.plusOrMinus(1e-9)
        projectionOf(setupOf("EPSG:5042")).factors(-89.999999, 20.0)!!.scale shouldBe 0.994.plusOrMinus(1e-9)
    }

    @Test
    fun `S032 RF-14 la convergencia es la diferencia de longitud en el polo norte y su opuesta en el sur`() {
        // EPSG:5041 y 5042 tienen la longitud de origen en 0°.
        Math.toDegrees(projectionOf(setupOf("EPSG:5041")).factors(70.0, 30.0)!!.convergenceRad) shouldBe 30.0.plusOrMinus(1e-9)
        Math.toDegrees(projectionOf(setupOf("EPSG:5042")).factors(-70.0, 30.0)!!.convergenceRad) shouldBe (-30.0).plusOrMinus(1e-9)
    }

    @Test
    fun `S032 RF-14 en los polos o con un valor que no es un numero no hay factor`() {
        val projection = projectionOf(setupOf("EPSG:3413"))

        projection.factors(90.0, 0.0) shouldBe null
        projection.factors(-90.0, 0.0) shouldBe null
        projection.factors(Double.NaN, 0.0) shouldBe null
        projection.factors(75.0, Double.NaN) shouldBe null
        projection.factors(100.0, 0.0) shouldBe null
    }
}
