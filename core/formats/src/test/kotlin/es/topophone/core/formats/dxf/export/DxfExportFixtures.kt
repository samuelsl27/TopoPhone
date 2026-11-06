package es.topophone.core.formats.dxf.export

import java.io.File

/** Los ficheros de referencia de `fixtures/formatos/dxf-exportado/` que genera `tools/formatos/generar_dxf_exportado.py` (S029). */
object DxfExportFixtures {
    private val folder: File by lazy {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }
            .first { File(it, "settings.gradle.kts").exists() && File(it, "fixtures").isDirectory }
        File(root, "fixtures/formatos/dxf-exportado")
    }

    fun bytes(name: String): ByteArray = File(folder, name).readBytes()

    /** Un DXF de referencia partido en pares (código, valor). */
    fun pairs(name: String): List<Pair<String, String>> {
        val lines = File(folder, name).readText().split("\r\n").dropLast(1)
        return lines.chunked(2).map { it[0].trim() to it[1] }
    }

    /** Las filas de un CSV de referencia como mapas columna → valor. */
    fun csv(name: String): List<Map<String, String>> {
        val lines = File(folder, name).readText().lines().filter { it.isNotEmpty() }
        val header = lines.first().split(",")
        return lines.drop(1).map { line -> header.zip(line.split(",")).toMap() }
    }
}
