package es.topophone.core.formats.raster

import java.io.File

/** Los ficheros de `fixtures/raster/` y sus tablas de referencia, generados aparte por `tools/raster` (S045). */
object RasterFixtures {
    private val folder: File by lazy {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }
            .first { File(it, "settings.gradle.kts").exists() && File(it, "fixtures").isDirectory }
        File(root, "fixtures/raster")
    }

    fun file(name: String) = File(folder, name)

    fun bytes(name: String): ByteArray = file(name).readBytes()

    fun source(name: String): RasterSource = ByteArrayRasterSource(bytes(name))

    /** Las filas de un CSV de referencia como mapas por columna. */
    fun table(name: String): List<Map<String, String>> {
        val lines = file(name).readLines().filter { it.isNotBlank() && !it.startsWith("#") }
        val head = lines.first().split(",")
        return lines.drop(1).map { line -> head.zip(line.split(",")).toMap() }
    }

    /** Qué es cada fichero y lo que debe decir el escaneo (`ficheros.csv`). */
    val files: List<Map<String, String>> by lazy { table("ficheros.csv") }

    /** Los ficheros que se importan. */
    val readable: List<Map<String, String>> by lazy { files.filter { it["resultado"] == "OK" } }
}

/** Un ráster leído entero en memoria, para comparar celdas en los tests. */
class DecodedRaster(val header: RasterHeader, val stats: RasterReadStats, val values: DoubleArray) {
    fun value(col: Int, row: Int): Double = values[row * header.columns + col]
}
