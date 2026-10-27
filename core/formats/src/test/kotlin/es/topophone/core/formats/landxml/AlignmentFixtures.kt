package es.topophone.core.formats.landxml

import java.io.File

/** Los ficheros de `fixtures/ejes/` generados por `tools/ejes/generar_ejes.py` (S049). */
object AlignmentFixtures {
    private val folder: File by lazy {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }
            .first { File(it, "settings.gradle.kts").exists() && File(it, "fixtures").isDirectory }
        File(root, "fixtures/ejes")
    }

    fun stream(name: String) = File(folder, name).inputStream().buffered()

    fun bytes(name: String): ByteArray = File(folder, name).readBytes()

    /** Las filas de un CSV de referencia, sin cabecera, partidas por comas. */
    fun rows(name: String): List<List<String>> =
        File(folder, name).readLines().filter { it.isNotBlank() && !it.startsWith("#") }.drop(1).map { it.split(",") }
}
