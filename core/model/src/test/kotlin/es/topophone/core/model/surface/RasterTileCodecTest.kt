package es.topophone.core.model.surface

import es.topophone.core.model.cad.CadSourceCrs
import es.topophone.core.model.project.HeightSystem
import es.topophone.core.model.project.LinearUnit
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** El bloque de un ráster de cotas y lo que guarda su superficie (S045/RF-14, RF-17). */
class RasterTileCodecTest {

    private val edge = RasterTile.EDGE

    private fun floats() = FloatArray(edge * edge) { if (it % 97 == 0) Float.NaN else 600f + it / 1000f }

    private fun doubles() = DoubleArray(edge * edge) { if (it % 89 == 0) Double.NaN else 600.0 + it / 3.0e5 + 1e-9 }

    @Test
    fun `S045 RF-14 un bloque float32 vuelve igual y con sus celdas sin dato`() {
        val tile = RasterTile.ofFloats(floats())

        val back = RasterTileCodec.decode(RasterTileCodec.encode(tile))

        back.valueType shouldBe RasterValueType.FLOAT32
        back shouldBe tile
        back.valueAt(0, 0).isNaN() shouldBe true
        back.valueAt(1, 0) shouldBe (600f + 1 / 1000f).toDouble()
    }

    @Test
    fun `S045 RF-14 un bloque float64 guarda cada valor sin redondear`() {
        val values = doubles()
        val tile = RasterTile.ofDoubles(values)

        val back = RasterTileCodec.decode(RasterTileCodec.encode(tile))

        back.valueType shouldBe RasterValueType.FLOAT64
        back shouldBe tile
        for (k in 1 until 500) {
            if (!values[k].isNaN()) back.valueAt(k % edge, k / edge) shouldBe values[k]
        }
    }

    @Test
    fun `S045 RF-14 el valor de una celda se pide por columna y fila`() {
        val values = FloatArray(edge * edge) { it.toFloat() }
        val tile = RasterTile.ofFloats(values)

        tile.valueAt(5, 3) shouldBe (3 * edge + 5).toDouble()
        tile.valueAt(edge - 1, edge - 1) shouldBe (edge * edge - 1).toDouble()
    }

    @Test
    fun `S045 RF-14 un bloque con otro numero de celdas no se admite`() {
        shouldThrow<IllegalArgumentException> { RasterTile.ofFloats(FloatArray(10)) }
        shouldThrow<IllegalArgumentException> { RasterTile.ofDoubles(DoubleArray(edge * edge + 1)) }
    }

    @Test
    fun `S045 RF-14 un bloque cortado o de otro formato no se lee`() {
        val bytes = RasterTileCodec.encode(RasterTile.ofFloats(floats()))

        shouldThrow<IllegalArgumentException> { RasterTileCodec.decode(bytes.copyOf(bytes.size - 1)) }
        shouldThrow<IllegalArgumentException> { RasterTileCodec.decode(bytes.copyOf(bytes.size + 1)) }
        shouldThrow<IllegalArgumentException> { RasterTileCodec.decode(byteArrayOf(99) + bytes.copyOfRange(1, bytes.size)) }
        shouldThrow<IllegalArgumentException> { RasterTileCodec.decode(ByteArray(0)) }
    }

    @Test
    fun `S045 RF-14 un bloque no pasa de 128 kB`() {
        RasterTileCodec.encode(RasterTile.ofDoubles(doubles())).size shouldBe 2 + edge * edge * 8
        (2 + edge * edge * 8 <= RasterTileCodec.MAX_TILE_BYTES) shouldBe true
    }

    @Test
    fun `S045 RF-17 una superficie raster guarda su geometria y sus recuentos y una TIN sigue sin ellos`() {
        val extent = SurfaceExtent(440000.0, 440400.0, 4473650.0, 4474010.0)
        val info = RasterInfo(
            columns = 40, rows = 36, originX = 440005.0, originY = 4474005.0, stepX = 10.0, stepY = 10.0,
            valueType = RasterValueType.FLOAT32, heightSystem = HeightSystem.ORTHOMETRIC, dataCells = 1436, noDataCells = 4,
        )
        val raster = Surface(
            "r1", "p1", "mdt", "mdt.tif", 1_000L, CadSourceCrs.Project, LinearUnit.METRE, extent, 600.0, 650.0, 0, 0, 0,
            kind = SurfaceKind.RASTER, raster = info,
        )
        val tin =
            Surface("s1", "p1", "terreno", "obra.xml", 1_000L, CadSourceCrs.Project, LinearUnit.METRE, extent, 600.0, 650.0, 42, 60, 2)

        raster.kind shouldBe SurfaceKind.RASTER
        raster.raster?.cellCount shouldBe 1440L
        tin.kind shouldBe SurfaceKind.TIN
        tin.raster shouldBe null
        info.tileColumns shouldBe 1
        info.tileRows shouldBe 1
        info.copy(columns = 129, rows = 257).tileColumns shouldBe 2
        info.copy(columns = 129, rows = 257).tileRows shouldBe 3
    }

    @Test
    fun `S045 RF-14 un raster sin celdas o con celda de tamano cero no existe`() {
        shouldThrow<IllegalArgumentException> {
            RasterInfo(0, 36, 0.0, 0.0, 10.0, 10.0, RasterValueType.FLOAT32, HeightSystem.ORTHOMETRIC, 0, 0)
        }
        shouldThrow<IllegalArgumentException> {
            RasterInfo(40, 36, 0.0, 0.0, 0.0, 10.0, RasterValueType.FLOAT32, HeightSystem.ORTHOMETRIC, 0, 0)
        }
    }
}
