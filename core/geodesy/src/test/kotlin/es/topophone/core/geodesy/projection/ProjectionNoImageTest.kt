package es.topophone.core.geodesy.projection

import es.topophone.core.geodesy.Ellipsoid
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** Un punto sin imagen en la proyección se rechaza como no disponible, nunca con un valor extrapolado (S032/RF-19; ADR-5). */
class ProjectionNoImageTest {

    private val sphere = Ellipsoid(6_371_000.0, Double.POSITIVE_INFINITY)

    private fun projectionOf(parameters: ProjectionParameters, ellipsoid: Ellipsoid = Ellipsoid.GRS80): MapProjection =
        (ProjectionFactory.create(ProjectionSetup(ellipsoid, parameters)) as ProjectionResult.Ok).projection

    @Test
    fun `S032 RF-19 el polo opuesto al origen de un LCC no tiene imagen ni factor`() {
        val north1 = projectionOf(LambertConic1Sp(40.0, -3.0, 0.9996, 0.0, 0.0))
        val north2 = projectionOf(LambertConic2Sp(40.0, -3.0, 38.0, 42.0, 0.0, 0.0))
        val south2 = projectionOf(LambertConic2Sp(-30.0, 20.0, -20.0, -40.0, 0.0, 0.0))

        listOf(north1, north2).forEach {
            it.forward(-90.0, 10.0) shouldBe null
            it.factors(-90.0, 10.0) shouldBe null
        }
        south2.forward(90.0, 10.0) shouldBe null
        south2.factors(90.0, 10.0) shouldBe null
    }

    @Test
    fun `S032 RF-19 los polos de una Mercator no tienen imagen ni factor`() {
        listOf(MercatorA(10.0, 0.9996, 0.0, 0.0), MercatorB(10.0, 40.0, 0.0, 0.0)).forEach { parameters ->
            val projection = projectionOf(parameters)

            listOf(90.0, -90.0).forEach { pole ->
                projection.forward(pole, 10.0) shouldBe null
                projection.factors(pole, 10.0) shouldBe null
            }
        }
    }

    @Test
    fun `S032 RF-19 un punto a 90 grados de la linea central de una OM no tiene imagen ni factor`() {
        listOf(ObliqueMercatorA(0.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0), ObliqueMercatorB(0.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0)).forEach {
            val projection = projectionOf(it, sphere)

            projection.forward(0.0, 90.0) shouldBe null
            projection.forward(0.0, -90.0) shouldBe null
            projection.factors(0.0, 90.0) shouldBe null
        }
    }

    @Test
    fun `S032 RF-19 el punto antipoda del origen de la estereografica oblicua no tiene imagen ni factor`() {
        val projection = projectionOf(ObliqueStereographic(40.0, 10.0, 1.0, 0.0, 0.0), sphere)

        projection.forward(-40.0, -170.0) shouldBe null
        projection.factors(-40.0, -170.0) shouldBe null
    }

    @Test
    fun `S032 RF-19 el polo opuesto de la estereografica polar no tiene imagen ni factor`() {
        listOf(
            PolarStereographicA(90.0, 0.0, 0.994, 0.0, 0.0) to -90.0,
            PolarStereographicB(70.0, -45.0, 0.0, 0.0) to -90.0,
            PolarStereographicC(75.0, 20.0, 0.0, 0.0) to -90.0,
            PolarStereographicA(-90.0, 0.0, 0.994, 0.0, 0.0) to 90.0,
            PolarStereographicB(-71.0, 0.0, 0.0, 0.0) to 90.0,
            PolarStereographicC(-67.0, 140.0, 0.0, 0.0) to 90.0,
        ).forEach { (parameters, oppositePole) ->
            val projection = projectionOf(parameters)

            projection.forward(oppositePole, 33.0) shouldBe null
            projection.factors(oppositePole, 33.0) shouldBe null
        }
    }

    @Test
    fun `S032 RF-19 con un valor que no es un numero o una latitud fuera de menos 90 a 90 ningun metodo devuelve nada`() {
        ProjectionTestData.all.forEach { parameters ->
            val projection = projectionOf(parameters)

            listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY).forEach { bad ->
                projection.forward(bad, 0.0) shouldBe null
                projection.forward(40.0, bad) shouldBe null
                projection.inverse(bad, 0.0) shouldBe null
                projection.inverse(0.0, bad) shouldBe null
                projection.factors(bad, 0.0) shouldBe null
            }
            projection.forward(90.1, 0.0) shouldBe null
            projection.forward(-91.0, 0.0) shouldBe null
            projection.factors(95.0, 0.0) shouldBe null
        }
    }
}
