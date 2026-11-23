package es.topophone.app.ui.geometry

import es.topophone.core.model.geometry.DistanceMode
import es.topophone.core.model.project.SpainCrs
import es.topophone.core.model.project.TerrainPlane
import es.topophone.core.survey.geometry.GeometryResult
import es.topophone.core.survey.geometry.GeometryVertex
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.Test

/** La información geométrica en terreno: longitudes por k, áreas por k² y azimuts sin cambio (S058/RF-9). */
class TerrainGeometryTest {
    private val crs = SpainCrs.ETRS89_UTM30
    private val factor = 1.0004
    private val terrain = GeometryInput.terrainOf(TerrainPlane(40.4, -3.7, factor), crs)

    // Un cuadrado de 100 m de lado en cuadrícula.
    private val square = listOf(
        GeometryVertex(easting = 440_000.0, northing = 4_474_000.0),
        GeometryVertex(easting = 440_100.0, northing = 4_474_000.0),
        GeometryVertex(easting = 440_100.0, northing = 4_474_100.0),
        GeometryVertex(easting = 440_000.0, northing = 4_474_100.0),
    )

    private fun info(view: es.topophone.core.survey.TerrainView) =
        (GeometryInput.compute(square, closed = true, DistanceMode.GRID, crs, view).shouldBeInstanceOf<GeometryResult.Info>()).info

    @Test
    fun `S058 RF-9 la longitud y el area en terreno van por k y por k al cuadrado`() {
        val grid = info(GeometryInput.terrainOf(null, crs))
        val ground = info(terrain)

        ground.perimeter!! shouldBe (grid.perimeter!! * factor plusOrMinus 1e-6)
        ground.area!! shouldBe (grid.area!! * factor * factor plusOrMinus 1e-6)
        ground.segments.size shouldBe grid.segments.size
        ground.segments[0].length2D shouldBe (100.0 * factor plusOrMinus 1e-6)
    }

    @Test
    fun `S058 RF-9 los azimuts de los segmentos no cambian en terreno`() {
        val grid = info(GeometryInput.terrainOf(null, crs))
        val ground = info(terrain)

        ground.segments.map { it.azimuthRad } shouldBe grid.segments.map { it.azimuthRad }
    }

    @Test
    fun `S058 RF-9 en terreno la distancia elegida de terreno no se escala dos veces`() {
        val chosenGround = (GeometryInput.compute(square, true, DistanceMode.GROUND, crs, terrain) as GeometryResult.Info).info
        val chosenGrid = info(terrain)

        chosenGround.perimeter!! shouldBe (chosenGrid.perimeter!! plusOrMinus 1e-9)
    }
}
