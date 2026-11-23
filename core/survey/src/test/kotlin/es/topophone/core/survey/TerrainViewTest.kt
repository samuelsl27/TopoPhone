package es.topophone.core.survey

import es.topophone.core.model.project.SpainCrs
import es.topophone.core.model.project.TerrainPlane
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** Lo que usan las pantallas para mostrar y escribir en terreno: la identidad en cuadrícula (S058/RF-9, RF-10, RF-11). */
class TerrainViewTest {
    private val crs = SpainCrs.ETRS89_UTM30
    private val terrain = TerrainView.of(TerrainPlane(40.4, -3.7, 0.9995), crs).shouldNotBeNull()

    @Test
    fun `S058 RF-9 sin plano todo es la identidad`() {
        val grid = TerrainView.of(null, crs).shouldNotBeNull()

        grid.isTerrain shouldBe false
        grid.factor shouldBe 1.0
        grid.coordinates(440_000.5, 4_470_000.25) shouldBe EastNorth(440_000.5, 4_470_000.25)
        grid.toGrid(440_000.5, 4_470_000.25) shouldBe EastNorth(440_000.5, 4_470_000.25)
        grid.distance(123.456) shouldBe 123.456
        grid.area(1000.0) shouldBe 1000.0
        TerrainView.GRID.isTerrain shouldBe false
    }

    @Test
    fun `S058 RF-9 en terreno las coordenadas, las distancias y las areas pasan por el factor`() {
        terrain.isTerrain shouldBe true
        terrain.factor shouldBe 0.9995
        terrain.plane shouldBe TerrainPlane(40.4, -3.7, 0.9995)
        terrain.distance(1000.0) shouldBe (999.5 plusOrMinus 1e-9)
        terrain.area(1_000_000.0) shouldBe (999_000.25 plusOrMinus 1e-6)
        terrain.gridDistance(999.5) shouldBe (1000.0 plusOrMinus 1e-9)
        val t = terrain.coordinates(441_098.0, 4_473_190.0)
        (t.easting != 441_098.0) shouldBe true
        terrain.easting(441_098.0, 4_473_190.0) shouldBe t.easting
        terrain.northing(441_098.0, 4_473_190.0) shouldBe t.northing
    }

    @Test
    fun `S058 RF-10 lo que se escribe en terreno vuelve a cuadricula y la ida y vuelta cierra`() {
        val grid = EastNorth(441_098.123456, 4_473_190.654321)

        val t = terrain.coordinates(grid.easting, grid.northing)
        val back = terrain.toGrid(t.easting, t.northing)

        back.easting shouldBe (grid.easting plusOrMinus 1e-4)
        back.northing shouldBe (grid.northing plusOrMinus 1e-4)
    }

    @Test
    fun `S058 RF-9 la guia del replanteo y la de un eje van por el factor y no cambian ni el rumbo ni la cota`() {
        val grid = Guidance(10.0, 6.0, 8.0, 0.5, 4.0, 3.0, -0.2)

        val shown = terrain.guidance(grid)

        shown.distanceM shouldBe (10.0 * 0.9995 plusOrMinus 1e-12)
        shown.dEastM shouldBe (6.0 * 0.9995 plusOrMinus 1e-12)
        shown.dNorthM shouldBe (8.0 * 0.9995 plusOrMinus 1e-12)
        shown.aheadM shouldBe (4.0 * 0.9995 plusOrMinus 1e-12)
        shown.rightM shouldBe (3.0 * 0.9995 plusOrMinus 1e-12)
        shown.bearingRad shouldBe 0.5
        shown.dHeightM shouldBe -0.2
        TerrainView.GRID.guidance(grid) shouldBe grid
        val axis = terrain.axisGuidance(AxisGuidanceValue(2.0, -1.0, AcrossMove.LEFT))
        axis.alongM shouldBe (2.0 * 0.9995 plusOrMinus 1e-12)
        axis.acrossM shouldBe (-1.0 * 0.9995 plusOrMinus 1e-12)
        axis.across shouldBe AcrossMove.LEFT
    }

    @Test
    fun `S058 RF-9 un plano que el SC no admite no da vista y no cuadricula en silencio`() {
        TerrainView.of(TerrainPlane(40.4, -3.7, 0.9995), SpainCrs.ETRS89_GEOGRAPHIC).shouldBeNull()
    }
}
