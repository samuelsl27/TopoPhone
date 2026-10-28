package es.topophone.core.survey

import es.topophone.core.formats.raster.ByteArrayRasterSource
import es.topophone.core.formats.raster.RasterHeader
import es.topophone.core.formats.raster.RasterReader
import es.topophone.core.formats.raster.RasterScan
import java.io.File

/** Un ráster de `fixtures/raster/` leído entero con el lector de `core:formats`: su cabecera y todas sus celdas (S045). */
class LoadedRaster(val name: String, val header: RasterHeader, val values: DoubleArray) {
    fun value(col: Int, row: Int): Double = values[row * header.columns + col]
}

object RasterTestFiles {
    private val folder: File by lazy {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }
            .first { File(it, "settings.gradle.kts").exists() && File(it, "fixtures").isDirectory }
        File(root, "fixtures/raster")
    }

    fun table(name: String): List<Map<String, String>> {
        val lines = File(folder, name).readLines(Charsets.UTF_8).filter { it.isNotBlank() && !it.startsWith("#") }
        val head = lines.first().split(",")
        return lines.drop(1).map { line -> head.zip(line.split(",")).toMap() }
    }

    fun load(name: String): LoadedRaster {
        val source = ByteArrayRasterSource(File(folder, name).readBytes())
        val header = (RasterReader.scan(source) as RasterScan.Found).header
        val values = DoubleArray(header.columns * header.rows)
        RasterReader.readBands(source, { first, rows, band ->
            System.arraycopy(
                band,
                0,
                values,
                first * header.columns,
                rows * header.columns,
            )
        })
        return LoadedRaster(name, header, values)
    }
}
