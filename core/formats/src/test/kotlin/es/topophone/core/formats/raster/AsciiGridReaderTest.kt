package es.topophone.core.formats.raster

import es.topophone.core.model.surface.RasterValueType
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** La lectura de un ASCII Grid de Esri: cabecera, esquina o centro, sin dato y valores que no son un número (S045/RF-2, RF-3, RF-7). */
class AsciiGridReaderTest {

    private fun source(text: String) = ByteArrayRasterSource(text.toByteArray(Charsets.UTF_8))

    private fun scan(text: String) = AsciiGridReader.scan(source(text))

    private fun header(text: String) = (scan(text) as RasterScan.Found).header

    private fun readAll(source: RasterSource, bandRows: Int = RasterLimits.BAND_ROWS): DecodedRaster {
        val header = (AsciiGridReader.scan(source) as RasterScan.Found).header
        val values = DoubleArray(header.columns * header.rows)
        var next = 0
        val stats = AsciiGridReader.readBands(source, { first, rows, band ->
            first shouldBe next
            System.arraycopy(band, 0, values, first * header.columns, rows * header.columns)
            next += rows
        }, bandRows)
        next shouldBe header.rows
        return DecodedRaster(header, stats, values)
    }

    private val small = "ncols 3\nnrows 2\nxllcorner 100\nyllcorner 200\ncellsize 10\nNODATA_value -9999\n1 2 3\n4 -9999 6\n"

    @Test
    fun `S045 RF-7 la esquina inferior izquierda se lleva al centro de la celda de arriba a la izquierda`() {
        val corner = header(small)

        corner.columns shouldBe 3
        corner.rows shouldBe 2
        corner.originX shouldBe 105.0
        corner.originY shouldBe 215.0 // yllcorner + (filas - 1) celdas + media celda
        corner.stepX shouldBe 10.0
        corner.stepY shouldBe 10.0
        corner.epsg shouldBe null
        corner.verticalUnit shouldBe null
        corner.bandCount shouldBe 1
        corner.valueType shouldBe RasterValueType.FLOAT64
        corner.noDataValue shouldBe -9999.0
        header(small.replace("xllcorner", "xllcenter").replace("yllcorner", "yllcenter")).let {
            it.originX shouldBe 100.0
            it.originY shouldBe 210.0
        }
    }

    @Test
    fun `S045 RF-7 sin NODATA_value el valor sin dato es -9999 y las claves no distinguen mayusculas`() {
        val text = "NCOLS 2\nNROWS 1\nXLLCORNER 0\nYLLCORNER 0\nCELLSIZE 1\n5 -9999\n"

        header(text).noDataValue shouldBe -9999.0
        readAll(source(text)).values.map { it.toBits() } shouldBe listOf(5.0, Double.NaN).map { it.toBits() }
        header("$small".replace("NODATA_value -9999", "nodata_value -1")).noDataValue shouldBe -1.0
    }

    @Test
    fun `S045 RF-7 los saltos de linea de Windows las tabulaciones el BOM y las lineas en blanco`() {
        val bom = "﻿"
        val text = bom + "ncols\t3\r\nnrows 2\r\n\r\nxllcorner 100\r\nyllcorner 200\r\ncellsize 10\r\n" +
            "NODATA_value -9999\r\n1\t2 3\r\n4 -9999   6\r\n"

        val raster = readAll(source(text))

        raster.values.map { it.toBits() } shouldBe listOf(1.0, 2.0, 3.0, 4.0, Double.NaN, 6.0).map { it.toBits() }
        raster.stats shouldBe RasterReadStats(5, 1, 1.0, 6.0)
    }

    @Test
    fun `S045 RF-7 un valor que no es un numero cuenta como sin dato y la coma decimal no se entiende`() {
        val text = "ncols 4\nnrows 1\nxllcorner 0\nyllcorner 0\ncellsize 1\nNODATA_value -9999\n1.5 NaN abc 2,5\n"

        val raster = readAll(source(text))

        raster.values.map { it.toBits() } shouldBe listOf(1.5, Double.NaN, Double.NaN, Double.NaN).map { it.toBits() }
        raster.stats.dataCells shouldBe 1
        raster.stats.noDataCells shouldBe 3
    }

    @Test
    fun `S045 RF-14 los numeros se leen con punto decimal y sin redondear`() {
        val text = "ncols 3\nnrows 1\nxllcorner 0\nyllcorner 0\ncellsize 1\n610.0189819335938 1e2 -0.000001234\n"

        readAll(source(text)).values.toList() shouldBe listOf(610.0189819335938, 100.0, -0.000001234)
    }

    @Test
    fun `S045 RF-2 todas las celdas de cada ASCII Grid salen como las esperadas`() {
        val expected = RasterFixtures.table("celdas.csv").groupBy { it.getValue("fichero") }
        for (row in RasterFixtures.readable.filter { it["formato"] == "ascii" }) {
            val name = row.getValue("fichero")
            val raster = readAll(RasterFixtures.source(name))

            raster.header.columns shouldBe row.getValue("columnas").toInt()
            raster.header.rows shouldBe row.getValue("filas").toInt()
            raster.header.originX shouldBe (row.getValue("centro_x").toDouble() plusOrMinus 1e-9)
            raster.header.originY shouldBe (row.getValue("centro_y").toDouble() plusOrMinus 1e-9)
            raster.header.stepX shouldBe row.getValue("paso_x").toDouble()
            raster.stats.dataCells shouldBe row.getValue("con_dato").toLong()
            raster.stats.noDataCells shouldBe row.getValue("sin_dato_celdas").toLong()
            raster.stats.minValue shouldBe row.getValue("cota_min").toDouble()
            raster.stats.maxValue shouldBe row.getValue("cota_max").toDouble()
            for (cell in expected.getValue(name)) {
                val value = raster.value(cell.getValue("columna").toInt(), cell.getValue("fila").toInt())
                if (cell.getValue("valor") == "nodata") value.isNaN() shouldBe true else value shouldBe cell.getValue("valor").toDouble()
            }
        }
    }

    @Test
    fun `S045 RF-7 la esquina y el centro del mismo terreno dan la misma celda 0 0`() {
        val corner = (AsciiGridReader.scan(RasterFixtures.source("esquina.asc")) as RasterScan.Found).header
        val centre = (AsciiGridReader.scan(RasterFixtures.source("centro.asc")) as RasterScan.Found).header

        corner.originX shouldBe (centre.originX plusOrMinus 1e-9)
        corner.originY shouldBe (centre.originY plusOrMinus 1e-9)
        corner.originX shouldBe (440005.0 plusOrMinus 1e-9)
        corner.originY shouldBe (4474005.0 plusOrMinus 1e-9)
    }

    @Test
    fun `S045 RF-13 leer por bandas de pocas filas da lo mismo`() {
        val whole = readAll(RasterFixtures.source("esquina.asc"))
        val banded = readAll(RasterFixtures.source("esquina.asc"), bandRows = 7)

        banded.values.map { it.toBits() } shouldBe whole.values.map { it.toBits() }
        banded.stats shouldBe whole.stats
    }

    @Test
    fun `S045 RF-3 sin cabecera completa o con una celda que no es positiva no esta georreferenciado`() {
        scan("ncols 3\nnrows 2\ncellsize 10\n1 2 3\n4 5 6\n") shouldBe RasterScan.Rejected(RasterRejection.NOT_GEOREFERENCED)
        scan(small.replace("cellsize 10", "cellsize 0")) shouldBe RasterScan.Rejected(RasterRejection.NOT_GEOREFERENCED)
        scan(small.replace("cellsize 10\n", "")) shouldBe RasterScan.Rejected(RasterRejection.NOT_GEOREFERENCED)
        scan(small.replace("ncols 3", "ncols x")) shouldBe RasterScan.Rejected(RasterRejection.NOT_A_RASTER)
        scan(small.replace("nrows 2", "nrows 0")) shouldBe RasterScan.Rejected(RasterRejection.NOT_A_RASTER)
    }

    @Test
    fun `S045 RF-3 un texto cualquiera o un fichero vacio no son un ASCII Grid`() {
        scan("Hola, esto no es un ráster\n") shouldBe RasterScan.Rejected(RasterRejection.NOT_A_RASTER)
        AsciiGridReader.scan(ByteArrayRasterSource(ByteArray(0))) shouldBe RasterScan.Rejected(RasterRejection.EMPTY)
        AsciiGridReader.scan(RasterFixtures.source("no-es-raster.txt")) shouldBe RasterScan.Rejected(RasterRejection.NOT_A_RASTER)
    }

    @Test
    fun `S045 RF-3 con menos valores que celdas el fichero esta cortado`() {
        val short = small.replace("4 -9999 6\n", "4 -9999\n")

        shouldThrow<RasterFormatException> { readAll(source(short)) }
    }

    @Test
    fun `S045 RF-5 mas de 250 millones de celdas o un ancho que no cabe en una banda se rechaza al leer la cabecera`() {
        scan(small.replace("ncols 3", "ncols 20000").replace("nrows 2", "nrows 20000")) shouldBe
            RasterScan.Rejected(RasterRejection.TOO_LARGE)
        scan(small.replace("ncols 3", "ncols ${RasterLimits.MAX_COLUMNS + 1}")) shouldBe RasterScan.Rejected(RasterRejection.TOO_WIDE)
        val huge = object : RasterSource {
            override val size: Long = RasterLimits.MAX_FILE_BYTES + 1

            override fun read(position: Long, buffer: ByteArray, offset: Int, length: Int) = error("No se lee")
        }
        AsciiGridReader.scan(huge) shouldBe RasterScan.Rejected(RasterRejection.TOO_LARGE)
    }

    @Test
    fun `S045 RF-16 un fichero con todas las celdas sin dato se lee y lo dice el recuento`() {
        val text = "ncols 2\nnrows 2\nxllcorner 0\nyllcorner 0\ncellsize 1\n-9999 -9999\n-9999 -9999\n"

        readAll(source(text)).stats.let {
            it.dataCells shouldBe 0
            it.noDataCells shouldBe 4
        }
    }
}
