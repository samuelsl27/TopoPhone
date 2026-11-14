package es.topophone.core.geodesy

import es.topophone.core.model.offline.TileKind
import es.topophone.core.model.offline.TileSourceSpec
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import kotlin.math.abs
import org.junit.jupiter.api.Test

private fun spec(row: Map<String, String>) = TileSourceSpec(
    kind = TileKind.valueOf(row.getValue("tile_kind").uppercase()),
    tileSize = row.getValue("tile_size").toInt(),
    minZoom = row.getValue("source_min").toInt(),
    maxZoom = row.getValue("source_max").toInt(),
)

private fun box(row: Map<String, String>) = GeoBox(
    south = row.getValue("south").toDouble(),
    west = row.getValue("west").toDouble(),
    north = row.getValue("north").toDouble(),
    east = row.getValue("east").toDouble(),
)

class TileCoverTest {

    private val raster256 = TileSourceSpec(TileKind.RASTER, 256, 0, 19)
    private val vector512 = TileSourceSpec(TileKind.VECTOR, 512, 0, 17)
    private val relief = TileSourceSpec(TileKind.RASTER, 512, 5, 15)

    @Test
    fun `S037 RF-11 las filas y columnas de cada caso son las de la referencia independiente`() {
        val cases = OfflineFixtures.rows("teselas.csv").groupBy { it.getValue("case") }
        cases.size shouldBe 240 // los otros 120 de los 360 caen fuera de los niveles de su fuente y no escriben filas
        cases.values.forEach { rows ->
            val first = rows.first()
            val ranges = TileCover.ranges(
                box(first),
                first.getValue("map_zoom_min").toDouble(),
                first.getValue("map_zoom_max").toDouble(),
                spec(first),
            )
            ranges.map { it.level } shouldBe rows.map { it.getValue("tile_zoom").toInt() }
            ranges.zip(rows).forEach { (range, row) ->
                listOf(range.xMin, range.xMax, range.yMin, range.yMax) shouldBe
                    listOf("x_min", "x_max", "y_min", "y_max").map { row.getValue(it).toInt() }
                range.count shouldBe row.getValue("count").toLong()
            }
        }
    }

    @Test
    fun `S037 RF-11 el numero de teselas es el que conto MapLibre en el emulador`() {
        OfflineFixtures.rows("medidos.csv").forEach { row ->
            val count = TileCover.count(
                box(row),
                row.getValue("map_zoom_min").toDouble(),
                row.getValue("map_zoom_max").toDouble(),
                spec(row),
            )
            count shouldBe row.getValue("tiles").toLong()
        }
    }

    @Test
    fun `S037 RF-11 el IGN vectorial suma sus teselas vectoriales y las de su relieve`() {
        val area = GeoBox(40.4160, -3.7050, 40.4180, -3.7020)

        TileCover.count(area, 15.0, 17.0, vector512) shouldBe 10L
        TileCover.count(area, 15.0, 17.0, relief) shouldBe 2L
        TileCover.count(area, 15.0, 17.0, listOf(vector512, relief)) shouldBe 12L
    }

    @Test
    fun `S037 RF-7 el nivel de tesela de una raster de 256 px es el zoom del mapa mas uno y el de una vectorial el mismo`() {
        TileCover.levels(15.0, 17.0, raster256) shouldBe listOf(16, 17, 18)
        TileCover.levels(15.0, 17.0, vector512) shouldBe listOf(15, 16, 17)
        TileCover.levels(15.0, 17.0, relief) shouldBe listOf(15)
    }

    @Test
    fun `S037 RF-7 un zoom fraccionario redondea las raster y trunca las vectoriales, y la mitad sube`() {
        TileCover.levels(16.2, 18.5, raster256) shouldBe listOf(17, 18, 19)
        TileCover.levels(16.2, 17.49, vector512) shouldBe listOf(16, 17)
        TileCover.coveringLevel(17.5, raster256) shouldBe 19
        TileCover.coveringLevel(17.49, raster256) shouldBe 18
        TileCover.coveringLevel(16.5, raster256) shouldBe 18
    }

    @Test
    fun `S037 RF-12 por encima del maximo de la fuente no hay teselas que bajar`() {
        val ofm = TileSourceSpec(TileKind.VECTOR, 512, 0, 14)

        TileCover.levels(20.0, 22.0, ofm) shouldBe emptyList()
        TileCover.count(GeoBox(40.4, -3.7, 40.5, -3.6), 20.0, 22.0, ofm) shouldBe 0L
    }

    @Test
    fun `S037 RF-7 el mundo entero a un nivel es toda su rejilla y el numero no desborda un Int`() {
        val world = GeoBox(-90.0, -180.0, 90.0, 180.0)

        TileCover.ranges(world, 2.0, 2.0, vector512).single().let {
            listOf(it.xMin, it.xMax, it.yMin, it.yMax) shouldBe listOf(0, 3, 0, 3)
            it.count shouldBe 16L
        }
        TileCover.ranges(world, 22.0, 22.0, TileSourceSpec(TileKind.VECTOR, 512, 0, 22)).single().count shouldBe (1L shl 22) * (1L shl 22)
    }

    @Test
    fun `S037 RF-7 las teselas de un rango salen por filas y son tantas como su numero`() {
        val range = TileCover.ranges(GeoBox(40.4160, -3.7050, 40.4180, -3.7020), 15.0, 15.0, raster256).single()

        val tiles = range.tiles().toList()

        tiles.size.toLong() shouldBe range.count
        tiles.first() shouldBe TileId(range.level, range.xMin, range.yMin)
        tiles.last() shouldBe TileId(range.level, range.xMax, range.yMax)
        tiles.toSet().size shouldBe tiles.size
    }

    @Test
    fun `S037 RF-7 la caja de una tesela en EPSG 3857 coincide con la de pyproj a menos de 1 mm`() {
        val rows = OfflineFixtures.rows("esquinas.csv")
        (rows.size >= 50) shouldBe true
        rows.forEach { row ->
            val box = TileCover.bounds3857(row.getValue("tile_zoom").toInt(), row.getValue("x").toInt(), row.getValue("y").toInt())
            listOf(
                box.minX to row.getValue("min_x"),
                box.minY to row.getValue("min_y"),
                box.maxX to row.getValue("max_x"),
                box.maxY to row.getValue("max_y"),
            ).forEach { (mine, saved) -> (abs(mine - saved.toDouble()) < 0.001) shouldBe true }
        }
    }

    @Test
    fun `S037 RF-7 una caja que pisa el polo no se sale de la rejilla`() {
        val range = TileCover.ranges(GeoBox(80.0, -10.0, 90.0, 10.0), 5.0, 5.0, vector512).single()

        range.yMin shouldBe 0
    }

    @Test
    fun `S037 RF-2 una caja con el sur por encima del norte no existe`() {
        shouldThrow<IllegalArgumentException> { GeoBox(41.0, -3.0, 40.0, -2.0) }
        shouldThrow<IllegalArgumentException> { GeoBox(40.0, -2.0, 41.0, -3.0) }
    }
}
