package es.topophone.core.formats.dxf

import java.io.File

/** Los ficheros de ejemplo de `fixtures/formatos/dxf/` y lo que se espera de ellos, byte a byte (S040). */
object DxfFixtures {
    private val root: File by lazy {
        generateSequence(File("").absoluteFile) { it.parentFile }
            .first { File(it, "settings.gradle.kts").exists() && File(it, "fixtures").isDirectory }
    }

    private val folder: File get() = File(root, "fixtures/formatos/dxf")

    fun bytes(name: String): ByteArray = File(folder, name).readBytes()

    fun size(name: String): Long = File(folder, name).length()

    /** Las filas de un CSV de lo esperado, sin los comentarios, como mapas columna → valor. */
    fun expected(name: String): List<Map<String, String>> {
        val lines = File(folder, name).readText().lines().filter { it.isNotEmpty() && !it.startsWith("#") }
        val header = splitCsv(lines.first())
        return lines.drop(1).map { line -> header.zip(splitCsv(line)).toMap() }
    }

    /** Un CSV con comillas dobles y sin saltos de línea dentro de las celdas, como los que escribe `generar_dxf.py`. */
    private fun splitCsv(line: String): List<String> {
        val out = mutableListOf<String>()
        val cell = StringBuilder()
        var quoted = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                quoted && c == '"' && line.getOrNull(i + 1) == '"' -> {
                    cell.append('"')
                    i++
                }
                c == '"' -> quoted = !quoted
                c == ',' && !quoted -> {
                    out += cell.toString()
                    cell.clear()
                }
                else -> cell.append(c)
            }
            i++
        }
        return out + cell.toString()
    }
}
