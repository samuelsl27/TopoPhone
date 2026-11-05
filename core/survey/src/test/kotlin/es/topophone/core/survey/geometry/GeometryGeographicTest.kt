package es.topophone.core.survey.geometry

import es.topophone.core.model.project.ProjectCrs
import es.topophone.core.survey.geometry.GeometryFixtures.number
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** Un SC geográfico usa el plano local del primer vértice y no tiene factor combinado (S026/RF-11). */
class GeometryGeographicTest {

    private fun info(scale: SegmentScale): GeometryInfo =
        (GeometryCalculator.of(GeometryFixtures.vertices("geografico"), false, scale) as GeometryResult.Info).info

    @Test
    fun `S026 RF-11 las distancias y el azimut salen del plano tangente local de la referencia`() {
        val result = info(SegmentScale.Grid)
        val expected = GeometryFixtures.segments("geografico", "grid")
        result.segments.size shouldBe expected.size
        result.segments.zip(expected).forEach { (s, e) ->
            s.length2D shouldBe (e.number("longitud_2d") plusOrMinus 1e-6)
            s.azimuthRad shouldBe (e.number("azimut_rad") plusOrMinus 1e-9)
            s.chainage shouldBe (e.number("pk") plusOrMinus 1e-6)
        }
        result.length2D shouldBe (GeometryFixtures.total("geografico", "grid").number("longitud_2d") plusOrMinus 1e-6)
    }

    @Test
    fun `S026 RF-11 se avisa del plano local en cuadricula y en terreno`() {
        info(SegmentScale.Grid).notices shouldBe setOf(GeometryNotice.LOCAL_PLANE)
        info(GroundScale(ProjectCrs.ETRS89_GEOGRAPHIC, 650.0)).notices shouldBe
            setOf(GeometryNotice.LOCAL_PLANE, GeometryNotice.NO_FACTOR)
    }

    @Test
    fun `S026 RF-11 en terreno coincide con la cuadricula porque no hay factor combinado`() {
        val grid = info(SegmentScale.Grid)
        val ground = info(GroundScale(ProjectCrs.ETRS89_GEOGRAPHIC, 650.0))
        ground.length2D shouldBe (grid.length2D plusOrMinus 1e-9)
        val expected = GeometryFixtures.total("geografico", "ground").number("longitud_2d")
        ground.length2D shouldBe (expected plusOrMinus 1e-6)
    }

    @Test
    fun `S026 RF-11 la altura que falta cuenta como cero en el plano y el desnivel no existe sin cota`() {
        val result = info(SegmentScale.Grid)
        result.length3D shouldBe null
        result.segments[0].slopePercent shouldBe null
    }

    @Test
    fun `S026 RF-11 sin Este y Norte ni posicion geodesica no se puede calcular`() {
        val vertices = listOf(GeometryVertex(height = 1.0), GeometryVertex(height = 2.0))
        GeometryCalculator.of(vertices, false, SegmentScale.Grid) shouldBe GeometryResult.NoPosition
    }
}
