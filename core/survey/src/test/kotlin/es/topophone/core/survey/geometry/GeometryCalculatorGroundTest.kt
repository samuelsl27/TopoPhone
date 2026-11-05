package es.topophone.core.survey.geometry

import es.topophone.core.model.geometry.DistanceMode
import es.topophone.core.model.project.ProjectCrs
import es.topophone.core.survey.geometry.GeometryFixtures.number
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** Distancias y área de terreno frente a pyproj (S026/RF-10, RF-11). */
class GeometryCalculatorGroundTest {

    private val tolM = 1e-4
    private val projected = listOf("recta", "quebrada", "vertical", "sin_cota", "sin_elip", "concavo", "cruces", "parcela")

    private fun ground(case: String, fallback: Double? = 650.0, crs: ProjectCrs = ProjectCrs.ETRS89_UTM30): GeometryInfo = (
        GeometryCalculator.of(
            GeometryFixtures.vertices(case),
            GeometryFixtures.closed(case),
            GroundScale(crs, fallback),
        ) as GeometryResult.Info
        ).info

    @Test
    fun `S026 RF-10 cada segmento de terreno coincide con la referencia`() {
        for (case in projected) {
            val result = ground(case)
            result.mode shouldBe DistanceMode.GROUND
            val expected = GeometryFixtures.segments(case, "ground")
            result.segments.zip(expected).forEach { (s, e) ->
                s.chainage shouldBe (e.number("pk") plusOrMinus tolM)
                s.length2D shouldBe (e.number("longitud_2d") plusOrMinus tolM)
                s.azimuthRad shouldBe (e.number("azimut_rad") plusOrMinus 1e-9)
                s.length3D?.let { it shouldBe (e.number("longitud_3d") plusOrMinus tolM) }
                s.slopePercent?.let { it shouldBe (e.number("pendiente_pct") plusOrMinus 1e-5) }
            }
        }
    }

    @Test
    fun `S026 RF-10 las longitudes y el area de terreno totales coinciden con la referencia`() {
        for (case in projected) {
            val total = GeometryFixtures.total(case, "ground")
            val result = ground(case)
            result.length2D shouldBe (total.number("longitud_2d") plusOrMinus tolM)
            result.perimeter?.let { it shouldBe (total.number("perimetro") plusOrMinus tolM) }
            result.area?.let { it shouldBe (total.number("area") plusOrMinus 1e-3) }
        }
    }

    @Test
    fun `S026 RF-10 cada segmento usa el factor de su centro y no uno unico`() {
        val result = ground("quebrada")
        val grid = (
            GeometryCalculator.of(GeometryFixtures.vertices("quebrada"), false, SegmentScale.Grid) as GeometryResult.Info
            ).info
        val ratios = result.segments.zip(grid.segments).map { (g, c) -> g.length2D / c.length2D }
        (ratios.max() - ratios.min() > 1e-7) shouldBe true
    }

    @Test
    fun `S026 RF-10 sin altura elipsoidal usa la del proyecto y sin ella el factor de escala con aviso`() {
        val withFallback = ground("sin_elip", fallback = 650.0)
        withFallback.notices shouldBe emptySet()
        val withoutHeight = ground("sin_elip", fallback = null)
        withoutHeight.notices shouldBe setOf(GeometryNotice.FACTOR_WITHOUT_HEIGHT)
        // Sin la corrección de altura el factor es mayor y la distancia de terreno, menor.
        (withoutHeight.length2D < withFallback.length2D) shouldBe true
        ((withFallback.length2D - withoutHeight.length2D) / withFallback.length2D < 2e-4) shouldBe true
    }

    @Test
    fun `S026 RF-10 un vertice fuera del area del SC no tiene factor y se avisa`() {
        val vertices = listOf(
            GeometryVertex(easting = 0.0, northing = 0.0, latitudeDeg = 10.0, longitudeDeg = 10.0, ellipsoidalHeightM = 0.0),
            GeometryVertex(easting = 100.0, northing = 0.0, latitudeDeg = 10.0, longitudeDeg = 10.001, ellipsoidalHeightM = 0.0),
        )
        val result = (GeometryCalculator.of(vertices, false, GroundScale(ProjectCrs.ETRS89_UTM30, 650.0)) as GeometryResult.Info).info
        result.length2D shouldBe (100.0 plusOrMinus 1e-9)
        result.notices shouldBe setOf(GeometryNotice.NO_FACTOR)
    }

    @Test
    fun `S026 RF-10 la cuadricula no avisa de nada`() {
        val grid = (GeometryCalculator.of(GeometryFixtures.vertices("recta"), false, SegmentScale.Grid) as GeometryResult.Info).info
        grid.notices shouldBe emptySet()
        grid.mode shouldBe DistanceMode.GRID
    }
}
