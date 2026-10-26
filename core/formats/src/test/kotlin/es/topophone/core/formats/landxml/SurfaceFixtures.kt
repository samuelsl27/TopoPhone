package es.topophone.core.formats.landxml

import java.io.File

/** Los ficheros de `fixtures/superficies/` generados por `tools/superficies/generar_superficies.py` (S042). */
object SurfaceFixtures {
    private val folder: File by lazy {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }
            .first { File(it, "settings.gradle.kts").exists() && File(it, "fixtures").isDirectory }
        File(root, "fixtures/superficies")
    }

    fun stream(name: String) = File(folder, name).inputStream().buffered()

    fun bytes(name: String): ByteArray = File(folder, name).readBytes()

    /** Las filas de un CSV de referencia, sin comentarios ni cabecera, partidas por comas. */
    fun rows(name: String): List<List<String>> =
        File(folder, name).readLines().filter { it.isNotBlank() && !it.startsWith("#") }.drop(1).map { it.split(",") }
}
