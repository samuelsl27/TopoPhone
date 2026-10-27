package es.topophone.core.formats.gpkg

import java.io.File

/** Los ficheros de ejemplo de `fixtures/formatos/gpkg/` y lo que se espera de ellos (S048). */
object GpkgFixtures {
    private val root: File by lazy {
        generateSequence(File("").absoluteFile) { it.parentFile }
            .first { File(it, "settings.gradle.kts").exists() && File(it, "fixtures").isDirectory }
    }

    private val folder: File get() = File(root, "fixtures/formatos/gpkg")

    fun bytes(name: String): ByteArray = File(folder, name).readBytes()

    fun size(name: String): Long = File(folder, name).length()

    /** Las filas de un CSV de lo esperado, sin los comentarios, como mapas columna → valor (sin comillas ni saltos dentro de las celdas). */
    fun expected(name: String): List<Map<String, String>> {
        val lines = File(folder, name).readText().lines().filter { it.isNotEmpty() && !it.startsWith("#") }
        val header = lines.first().split(",")
        return lines.drop(1).map { line -> header.zip(line.split(",")).toMap() }
    }
}
