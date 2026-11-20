package es.topophone.core.geodesy.projection

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.doubles.shouldBeGreaterThan
import io.kotest.matchers.doubles.shouldBeLessThan
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import kotlin.math.abs
import org.junit.jupiter.api.Test

/** Factor de escala y convergencia de meridianos de la Lambert frente a `get_factors` de pyproj (S032/RF-2; constitución nº 2). */
class LambertConicFactorsTest {

    private val vectors = LccVector.all()

    private fun projectionOf(setup: ProjectionSetup): MapProjection = (ProjectionFactory.create(setup) as ProjectionResult.Ok).projection

    @Test
    fun `S032 RF-2 el factor de escala coincide con pyproj a menos de 1e-9`() {
        vectors.size shouldBeGreaterThan 40

        val failures = vectors.mapNotNull { v ->
            val factors = projectionOf(v.setup).factors(v.latitudeDeg, v.longitudeDeg)
            val diff = factors?.let { abs(it.scale - v.scale) }
            if (diff == null || diff > 1e-9) "${v.case} (${v.latitudeDeg}, ${v.longitudeDeg}): Δk=$diff" else null
        }

        failures.shouldBeEmpty()
    }

    @Test
    fun `S032 RF-2 la convergencia coincide con pyproj a menos de 0,01 segundos de arco`() {
        val failures = vectors.mapNotNull { v ->
            val factors = projectionOf(v.setup).factors(v.latitudeDeg, v.longitudeDeg)
            val diffArcsec = factors?.let { abs(Math.toDegrees(it.convergenceRad) - v.convergenceDeg) * 3600.0 }
            if (diffArcsec == null || diffArcsec > 0.01) "${v.case} (${v.latitudeDeg}, ${v.longitudeDeg}): Δγ=$diffArcsec″" else null
        }

        failures.shouldBeEmpty()
    }

    @Test
    fun `S032 RF-2 la convergencia es positiva al este del meridiano central y negativa al oeste con el criterio de UTM`() {
        val setup = vectors.first { it.case == "EPSG:2154" }.setup
        val projection = projectionOf(setup)

        projection.factors(46.5, 6.0)!!.convergenceRad shouldBeGreaterThan 0.0
        projection.factors(46.5, 0.0)!!.convergenceRad shouldBeLessThan 0.0
        projection.factors(46.5, 3.0)!!.convergenceRad shouldBe 0.0
    }

    @Test
    fun `S032 RF-2 en el hemisferio sur el signo de la convergencia es el contrario`() {
        val projection = projectionOf(vectors.first { it.case == "sur-2sp" }.setup)

        // En el sur n < 0: al este del meridiano central la convergencia es negativa.
        projection.factors(-30.0, -57.0)!!.convergenceRad shouldBeLessThan 0.0
        projection.factors(-30.0, -63.0)!!.convergenceRad shouldBeGreaterThan 0.0
    }

    @Test
    fun `S032 RF-2 en los paralelos estandar de un 2SP la escala es 1`() {
        val projection = projectionOf(vectors.first { it.case == "EPSG:2154" }.setup)

        // Lambert-93: paralelos estándar 44° y 49°.
        abs(projection.factors(44.0, 3.0)!!.scale - 1.0) shouldBeLessThan 1e-12
        abs(projection.factors(49.0, 3.0)!!.scale - 1.0) shouldBeLessThan 1e-12
    }

    @Test
    fun `S032 RF-2 en el paralelo de origen de un 1SP la escala es el factor k0`() {
        val vector = vectors.first { it.case == "sur-1sp" }
        val parameters = vector.setup.parameters as LambertConic1Sp

        val factors = projectionOf(vector.setup).factors(parameters.latitudeOfOriginDeg, parameters.longitudeOfOriginDeg)!!

        abs(factors.scale - parameters.scaleFactor) shouldBeLessThan 1e-12
    }

    @Test
    fun `S032 RF-2 en un polo, o con un valor que no es un numero, no hay factor`() {
        val projection = projectionOf(vectors.first { it.case == "EPSG:3034" }.setup)

        // En el vértice del cono la escala tiende a infinito; en el otro polo no hay imagen.
        projection.factors(90.0, 0.0) shouldBe null
        projection.factors(-90.0, 0.0) shouldBe null
        projection.factors(Double.NaN, 0.0) shouldBe null
        projection.factors(45.0, Double.NaN) shouldBe null
        projection.factors(95.0, 0.0) shouldBe null
    }
}
