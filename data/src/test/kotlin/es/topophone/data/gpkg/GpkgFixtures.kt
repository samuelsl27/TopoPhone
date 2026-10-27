package es.topophone.data.gpkg

import java.io.File

/** Los ficheros de ejemplo de `fixtures/formatos/gpkg/` y lo que se espera de ellos (S048). */
object GpkgFixtures {
    private val root: File by lazy {
        generateSequence(File("").absoluteFile) { it.parentFile }
            .first { File(it, "settings.gradle.kts").exists() && File(it, "fixtures").isDirectory }
    }

    val folder: File get() = File(root, "fixtures/formatos/gpkg")

    fun file(name: String): File = File(folder, name)

    fun bytes(name: String): ByteArray = file(name).readBytes()

    /** Las filas de un CSV de lo esperado, sin los comentarios, como mapas columna → valor (sin comillas ni saltos dentro de las celdas). */
    fun expected(name: String): List<Map<String, String>> {
        val lines = file(name).readText().lines().filter { it.isNotEmpty() && !it.startsWith("#") }
        val header = lines.first().split(",")
        return lines.drop(1).map { line -> header.zip(line.split(",")).toMap() }
    }
}
