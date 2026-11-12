package es.topophone.core.formats.shp.export

import java.io.File

/** Los ficheros de referencia de `fixtures/formatos/shp-exportado/`, que genera `tools/formatos/generar_shp_exportado.py` (S055). */
object ShpExportFixtures {
    private val root: File by lazy {
        generateSequence(File("").absoluteFile) { it.parentFile }
            .first { File(it, "settings.gradle.kts").exists() && File(it, "fixtures").isDirectory }
    }

    val folder: File get() = File(root, "fixtures/formatos/shp-exportado")

    fun file(path: String): File = File(folder, path)

    fun bytes(path: String): ByteArray = file(path).readBytes()

    /** Las capas de ejemplo: `puntos`, `lineas` y `poligonos`. */
    val layers = listOf("puntos", "lineas", "poligonos")

    /**
     * Lo que se le da a cada escritor para obtener los ficheros `ejemplo-<capa>.*`: las columnas del `.dbf`, el texto de la geometría de cada
     * objeto (vértices `x y z` separados por `;`; `null` si no tiene) y sus valores.
     */
    class Entry(val columns: List<DbfColumn>, val geometry: List<String?>, val rows: List<Array<Any?>>)

    fun entry(layer: String): Entry {
        val lines = file("entrada-$layer.csv").readText().lines().filter { it.isNotEmpty() }
        val header = lines.first().split(",").drop(1)
        val types = header.map { cell ->
            when (cell.substringAfter(':')) {
                "C" -> DbfType.TEXT
                "I" -> DbfType.INTEGER
                "D" -> DbfType.DECIMAL
                else -> error("Tipo desconocido en $cell")
            }
        }
        val columns = header.mapIndexed { i, cell -> DbfColumn(cell.substringBefore(':'), types[i]) }
        val geometry = ArrayList<String?>()
        val rows = ArrayList<Array<Any?>>()
        for (line in lines.drop(1)) {
            val cells = line.split(",")
            check(cells.size == columns.size + 1) { "Fila con ${cells.size} celdas: $line" }
            geometry += cells.first().ifEmpty { null }
            rows += Array(columns.size) { i ->
                val cell = cells[i + 1]
                when {
                    cell.isEmpty() -> null
                    types[i] == DbfType.TEXT -> cell
                    types[i] == DbfType.INTEGER -> cell.toLong()
                    else -> cell.toDouble()
                }
            }
        }
        return Entry(columns, geometry, rows)
    }
}
