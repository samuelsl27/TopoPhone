package es.topophone.core.formats.raster

import es.topophone.core.model.project.LinearUnit
import es.topophone.core.model.surface.RasterValueType
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.jupiter.api.Test

/** La lectura de un GeoTIFF: cabecera, georreferencia, tipos, tiras y teselas, sin dato, unidad y rechazos (S045/RF-2 a RF-7, RF-9). */
class GeoTiffRasterReaderTest {

    private fun found(name: String): RasterHeader = (GeoTiffRasterReader.scan(RasterFixtures.source(name)) as RasterScan.Found).header

    private fun rejection(name: String): RasterRejection? =
        (GeoTiffRasterReader.scan(RasterFixtures.source(name)) as? RasterScan.Rejected)?.reason

    private fun readAll(source: RasterSource, bandRows: Int = RasterLimits.BAND_ROWS): DecodedRaster {
        val header = (GeoTiffRasterReader.scan(source) as RasterScan.Found).header
        val values = DoubleArray(header.columns * header.rows)
        var next = 0
        val stats = GeoTiffRasterReader.readBands(source, { first, rows, band ->
            first shouldBe next
            (rows <= bandRows) shouldBe true
            System.arraycopy(band, 0, values, first * header.columns, rows * header.columns)
            next += rows
        }, bandRows)
        next shouldBe header.rows
        return DecodedRaster(header, stats, values)
    }

    private fun readAll(name: String, bandRows: Int = RasterLimits.BAND_ROWS) = readAll(RasterFixtures.source(name), bandRows)

    private fun parse(text: String): Double = if (text == "nan") Double.NaN else text.toDouble()

    @Test
    fun `S045 RF-2 la cabecera de cada GeoTIFF dice su tamano su sistema y su celda`() {
        for (row in RasterFixtures.readable.filter { it["formato"] == "geotiff" }) {
            val header = found(row.getValue("fichero"))

            header.format shouldBe RasterFormat.GEOTIFF
            header.columns shouldBe row.getValue("columnas").toInt()
            header.rows shouldBe row.getValue("filas").toInt()
            header.epsg shouldBe row.getValue("epsg").toInt()
            header.stepX shouldBe row.getValue("paso_x").toDouble()
            header.stepY shouldBe row.getValue("paso_y").toDouble()
            header.originX shouldBe (row.getValue("centro_x").toDouble() plusOrMinus 1e-9)
            header.originY shouldBe (row.getValue("centro_y").toDouble() plusOrMinus 1e-9)
            header.bandCount shouldBe row.getValue("bandas").toInt()
            header.geographic shouldBe (row.getValue("epsg") == "4258")
            header.valueType shouldBe if (row.getValue("tipo") == "f64") RasterValueType.FLOAT64 else RasterValueType.FLOAT32
            header.noDataValue!!.toBits() shouldBe parse(row.getValue("sin_dato")).toBits()
        }
    }

    @Test
    fun `S045 RF-7 la celda como area y como punto dan el mismo centro de la celda 0 0`() {
        val area = found("f32-sin-tiras-area.tif")
        val point = found("f64-sin-tiras-punto.tif")

        area.originX shouldBe (440005.0 plusOrMinus 1e-9)
        area.originY shouldBe (4474005.0 plusOrMinus 1e-9)
        point.originX shouldBe area.originX
        point.originY shouldBe area.originY
    }

    @Test
    fun `S045 RF-2 todas las celdas de cada GeoTIFF salen igual en tiras y en teselas con cada compresion`() {
        val expected = RasterFixtures.table("celdas.csv").groupBy { it.getValue("fichero") }
        for (row in RasterFixtures.readable.filter { it["formato"] == "geotiff" }) {
            val name = row.getValue("fichero")
            val raster = readAll(name)
            for (cell in expected.getValue(name)) {
                val value = raster.value(cell.getValue("columna").toInt(), cell.getValue("fila").toInt())
                if (cell.getValue("valor") == "nodata") value.isNaN() shouldBe true else value shouldBe cell.getValue("valor").toDouble()
            }
        }
    }

    @Test
    fun `S045 RF-16 los recuentos y las cotas minima y maxima son los esperados`() {
        for (row in RasterFixtures.readable.filter { it["formato"] == "geotiff" }) {
            val stats = readAll(row.getValue("fichero")).stats

            stats.dataCells shouldBe row.getValue("con_dato").toLong()
            stats.noDataCells shouldBe row.getValue("sin_dato_celdas").toLong()
            stats.minValue shouldBe row.getValue("cota_min").toDouble()
            stats.maxValue shouldBe row.getValue("cota_max").toDouble()
        }
    }

    @Test
    fun `S045 RF-13 leer por bandas pequenas da lo mismo que por bandas de 128 filas`() {
        for (name in listOf(
            "f32-lzw-teselas-area.tif",
            "f32-deflate-pred3-teselas.tif",
            "i16-lzw-pred2-tiras.tif",
            "i32-deflate-pred2-teselas.tif",
        )) {
            val whole = readAll(name)
            val banded = readAll(name, bandRows = 5)

            banded.values.map { it.toBits() } shouldBe whole.values.map { it.toBits() }
        }
    }

    @Test
    fun `S045 RF-6 con varias bandas se usa la primera y se cuentan`() {
        val raster = readAll("f32-multibanda.tif")

        raster.header.bandCount shouldBe 2
        raster.value(7, 9) shouldBe readAll("f32-sin-tiras-area.tif").value(7, 9)
        raster.stats.maxValue shouldBe readAll("f32-sin-tiras-area.tif").stats.maxValue
    }

    @Test
    fun `S045 RF-9 la unidad de la cota la dice la clave de unidades verticales`() {
        found("f32-pies.tif").verticalUnit shouldBe LinearUnit.INTERNATIONAL_FOOT
        found("f32-sin-tiras-area.tif").verticalUnit shouldBe null
        TinyTiff(verticalUnits = 9001).scan().verticalUnit shouldBe LinearUnit.METRE
        TinyTiff(verticalUnits = 9003).scan().verticalUnit shouldBe LinearUnit.US_SURVEY_FOOT
        TinyTiff(verticalUnits = 9999).scan().verticalUnit shouldBe null
    }

    @Test
    fun `S045 RF-8 un sistema definido por el usuario no cuenta como declarado`() {
        TinyTiff(epsg = 32767).scan().epsg shouldBe null
        TinyTiff(epsg = 0).scan().epsg shouldBe null
        TinyTiff(epsg = 25829).scan().epsg shouldBe 25829
        TinyTiff(geographic = true, epsg = 4258).scan().epsg shouldBe 4258
    }

    @Test
    fun `S045 RF-7 el valor sin dato se declara con GDAL_NODATA y sin el no hay ninguno`() {
        TinyTiff(noData = "-3.4028234663852886e+38").scan().noDataValue shouldBe -3.4028234663852886E38
        TinyTiff(noData = "nan").scan().noDataValue!!.isNaN() shouldBe true
        TinyTiff(noData = null).scan().noDataValue shouldBe null
    }

    @Test
    fun `S045 RF-7 las celdas de un tipo entero se comparan con el valor declarado y el resto son datos`() {
        val raster = readAll("u16-sin-tiras.tif")

        raster.value(10, 8).isNaN() shouldBe true
        raster.value(9, 8).isNaN() shouldBe false
        raster.stats.noDataCells shouldBe 4
        raster.stats.maxValue shouldBe 647.0
    }

    @Test
    fun `S045 RF-3 un fichero sin ninguna celda con dato se lee y lo dice el recuento`() {
        val raster = readAll("todo-sin-dato.tif")

        raster.stats.dataCells shouldBe 0
        raster.stats.noDataCells shouldBe 64
    }

    @Test
    fun `S045 RF-3 un fichero vacio un texto o sin georreferencia se rechaza con su motivo`() {
        rejection("vacio.tif") shouldBe RasterRejection.EMPTY
        rejection("no-es-raster.txt") shouldBe RasterRejection.NOT_A_RASTER
        rejection("sin-georreferencia.tif") shouldBe RasterRejection.NOT_GEOREFERENCED
    }

    @Test
    fun `S045 RF-4 BigTIFF compresion que no se lee girado y de color se rechazan con su motivo`() {
        rejection("bigtiff.tif") shouldBe RasterRejection.BIGTIFF
        rejection("compresion-jpeg.tif") shouldBe RasterRejection.UNSUPPORTED_COMPRESSION
        rejection("girado.tif") shouldBe RasterRejection.ROTATED
        rejection("color-rgb.tif") shouldBe RasterRejection.COLOUR
        TinyTiff(predictor = 4).scanOrReject() shouldBe RasterScan.Rejected(RasterRejection.UNSUPPORTED_COMPRESSION)
        TinyTiff(bits = 8, format = 1, samples = 3, photometric = 2).scanOrReject() shouldBe RasterScan.Rejected(RasterRejection.COLOUR)
        TinyTiff(photometric = 3).scanOrReject() shouldBe RasterScan.Rejected(RasterRejection.COLOUR)
        TinyTiff(bits = 8, format = 1).scanOrReject() shouldBe RasterScan.Rejected(RasterRejection.UNSUPPORTED_DATA_TYPE)
        TinyTiff(bits = 16, format = 3).scanOrReject() shouldBe RasterScan.Rejected(RasterRejection.UNSUPPORTED_DATA_TYPE)
    }

    @Test
    fun `S045 RF-4 un eje Y que sube o una escala negativa tambien cuenta como girado`() {
        TinyTiff(scaleY = -10.0).scanOrReject() shouldBe RasterScan.Rejected(RasterRejection.ROTATED)
        TinyTiff(scaleX = -10.0).scanOrReject() shouldBe RasterScan.Rejected(RasterRejection.ROTATED)
    }

    @Test
    fun `S045 RF-4 una matriz de transformacion sin giro vale como escala y punto de enlace`() {
        val header = TinyTiff(transformation = true, scaleX = 10.0, scaleY = 10.0, tieX = 440000.0, tieY = 4474010.0).scan()

        header.stepX shouldBe 10.0
        header.stepY shouldBe 10.0
        header.originX shouldBe 440005.0
        header.originY shouldBe 4474005.0
    }

    @Test
    fun `S045 RF-5 mas de 250 millones de celdas o 250 MB se rechazan antes de leer nada`() {
        TinyTiff(width = 20_000, height = 20_000).scanOrReject() shouldBe RasterScan.Rejected(RasterRejection.TOO_LARGE)
        TinyTiff(width = 16_000, height = 15_625, rowsPerStrip = 1).scan().cellCount shouldBe 250_000_000L
        val huge = object : RasterSource {
            override val size: Long = RasterLimits.MAX_FILE_BYTES + 1

            override fun read(position: Long, buffer: ByteArray, offset: Int, length: Int) = error("No se lee")
        }
        GeoTiffRasterReader.scan(huge) shouldBe RasterScan.Rejected(RasterRejection.TOO_LARGE)
    }

    @Test
    fun `S045 plan D6 una banda de mas de 64 MB o un bloque de mas de 16 MB se rechaza`() {
        TinyTiff(width = RasterLimits.MAX_COLUMNS + 1, height = 4).scanOrReject() shouldBe RasterScan.Rejected(RasterRejection.TOO_WIDE)
        TinyTiff(width = RasterLimits.MAX_COLUMNS, height = 4).scan().columns shouldBe RasterLimits.MAX_COLUMNS
        TinyTiff(width = 2_500, height = 2_000, rowsPerStrip = 2_000).scanOrReject() shouldBe RasterScan.Rejected(RasterRejection.TOO_WIDE)
    }

    @Test
    fun `S045 RF-2 un GeoTIFF big endian se lee igual`() {
        val values = floatArrayOf(600f, 601.5f, -9999f, 603f, 610f, 611f, 612f, 613f)
        val file = TinyTiff(width = 4, height = 2, bigEndian = true, noData = "-9999", data = values)
        val raster = readAll(ByteArrayRasterSource(file.bytes()))

        raster.values.map { it.toBits() } shouldBe listOf(600.0, 601.5, Double.NaN, 603.0, 610.0, 611.0, 612.0, 613.0).map { it.toBits() }
        raster.stats.noDataCells shouldBe 1
    }

    @Test
    fun `S045 RF-3 un TIFF cortado por la mitad no es un raster`() {
        val bytes = RasterFixtures.bytes("f32-lzw-teselas-area.tif")

        GeoTiffRasterReader.scan(ByteArrayRasterSource(bytes.copyOf(bytes.size / 2))) shouldBe
            RasterScan.Rejected(RasterRejection.NOT_A_RASTER)
        GeoTiffRasterReader.scan(ByteArrayRasterSource(bytes.copyOf(5))) shouldBe RasterScan.Rejected(RasterRejection.NOT_A_RASTER)
    }

    /** Un TIFF mínimo construido aquí con las etiquetas que se le pidan, para los casos que no están en `fixtures/raster/`. */
    private class TinyTiff(
        val width: Int = 4,
        val height: Int = 2,
        val bits: Int = 32,
        val format: Int = 3,
        val samples: Int = 1,
        val compression: Int = 1,
        val predictor: Int = 1,
        val photometric: Int = 1,
        val rowsPerStrip: Int = height,
        val scaleX: Double = 10.0,
        val scaleY: Double = 10.0,
        val tieX: Double = 440000.0,
        val tieY: Double = 4474010.0,
        val transformation: Boolean = false,
        val epsg: Int = 25830,
        val geographic: Boolean = false,
        val verticalUnits: Int? = null,
        val noData: String? = null,
        val bigEndian: Boolean = false,
        val data: FloatArray? = null,
    ) {
        private val order = if (bigEndian) ByteOrder.BIG_ENDIAN else ByteOrder.LITTLE_ENDIAN

        fun bytes(): ByteArray {
            val body = ByteArrayOutputStream()
            val entries = ArrayList<Triple<Int, Int, ByteArray>>() // etiqueta, tipo, valores

            fun shorts(vararg v: Int) = ByteBuffer.allocate(v.size * 2).order(order).also { b ->
                v.forEach { b.putShort(it.toShort()) }
            }.array()

            fun longs(vararg v: Int) = ByteBuffer.allocate(v.size * 4).order(order).also { b -> v.forEach { b.putInt(it) } }.array()

            fun doubles(vararg v: Double) = ByteBuffer.allocate(v.size * 8).order(order).also { b -> v.forEach { b.putDouble(it) } }.array()
            val pixels =
                data?.let { d -> ByteBuffer.allocate(d.size * 4).order(order).also { b -> d.forEach { b.putFloat(it) } }.array() }
                    ?: ByteArray(0)
            val dataOffset = 8
            body.write(pixels)

            entries += Triple(256, 4, longs(width))
            entries += Triple(257, 4, longs(height))
            entries += Triple(258, 3, shorts(*IntArray(samples) { bits }))
            entries += Triple(259, 3, shorts(compression))
            entries += Triple(262, 3, shorts(photometric))
            entries += Triple(273, 4, longs(dataOffset))
            entries += Triple(277, 3, shorts(samples))
            entries += Triple(278, 4, longs(rowsPerStrip))
            entries += Triple(279, 4, longs(pixels.size))
            entries += Triple(284, 3, shorts(1))
            if (predictor != 1) entries += Triple(317, 3, shorts(predictor))
            entries += Triple(339, 3, shorts(*IntArray(samples) { format }))
            if (transformation) {
                entries += Triple(
                    34264,
                    12,
                    doubles(scaleX, 0.0, 0.0, tieX, 0.0, -scaleY, 0.0, tieY, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 1.0),
                )
            } else {
                entries += Triple(33550, 12, doubles(scaleX, scaleY, 0.0))
                entries += Triple(33922, 12, doubles(0.0, 0.0, 0.0, tieX, tieY, 0.0))
            }
            val keys = ArrayList<Int>()
            keys += listOf(1024, 0, 1, if (geographic) 2 else 1)
            keys += listOf(1025, 0, 1, 1)
            if (epsg != 0) keys += listOf(if (geographic) 2048 else 3072, 0, 1, epsg)
            verticalUnits?.let { keys += listOf(4099, 0, 1, it) }
            entries += Triple(34735, 3, shorts(1, 1, 0, keys.size / 4, *keys.toIntArray()))
            noData?.let { entries += Triple(42113, 2, (it + "\u0000").toByteArray(Charsets.US_ASCII)) }

            // Los valores de más de 4 bytes van tras los píxeles, y la IFD al final.
            val sizes = mapOf(2 to 1, 3 to 2, 4 to 4, 12 to 8)
            val ifdEntries = ByteArrayOutputStream()
            val extra = ByteArrayOutputStream()
            val extraBase = 8 + pixels.size
            for ((tag, type, value) in entries.sortedBy { it.first }) {
                val count = value.size / sizes.getValue(type)
                val header = ByteBuffer.allocate(12).order(order).putShort(tag.toShort()).putShort(type.toShort()).putInt(count)
                if (value.size <= 4) {
                    header.put(value)
                    ifdEntries.write(header.array().copyOf(12))
                } else {
                    header.putInt(extraBase + extra.size())
                    extra.write(value)
                    if (extra.size() % 2 == 1) extra.write(0)
                    ifdEntries.write(header.array())
                }
            }
            val ifdOffset = extraBase + extra.size()
            val out = ByteArrayOutputStream()
            out.write(
                byteArrayOf(
                    if (bigEndian) 'M'.code.toByte() else 'I'.code.toByte(),
                    if (bigEndian) 'M'.code.toByte() else 'I'.code.toByte(),
                ),
            )
            out.write(shorts(42))
            out.write(longs(ifdOffset))
            out.write(body.toByteArray())
            out.write(extra.toByteArray())
            out.write(shorts(entries.size))
            out.write(ifdEntries.toByteArray())
            out.write(longs(0))
            return out.toByteArray()
        }

        fun scanOrReject(): RasterScan = GeoTiffRasterReader.scan(ByteArrayRasterSource(bytes()))

        fun scan(): RasterHeader = (scanOrReject() as RasterScan.Found).header
    }
}
