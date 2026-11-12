package es.topophone.data.shp

import java.io.File

/** Los ficheros de ejemplo de `fixtures/formatos/shp/` y lo que se espera de ellos (S030). */
object ShpFixtures {
    private val root: File by lazy {
        generateSequence(File("").absoluteFile) { it.parentFile }
            .first { File(it, "settings.gradle.kts").exists() && File(it, "fixtures").isDirectory }
    }

    val folder: File get() = File(root, "fixtures/formatos/shp")

    fun file(path: String): File = File(folder, path)

    fun bytes(path: String): ByteArray = file(path).readBytes()

    /** Las filas de un CSV de lo esperado, sin los comentarios, como mapas columna → valor (sin comillas ni saltos dentro de las celdas). */
    fun expected(name: String): List<Map<String, String>> {
        val lines = file(name).readText().lines().filter { it.isNotEmpty() && !it.startsWith("#") }
        val header = lines.first().split(",")
        return lines.drop(1).map { line -> header.zip(line.split(",")).toMap() }
    }

    /** Un fichero de ejemplo como lo da el selector: su nombre, su tamaño y un flujo nuevo en cada llamada. */
    fun input(path: String, declaredSize: Long? = file(path).length()): ShpInput =
        ShpInput(file(path).name, declaredSize) { file(path).inputStream() }

    fun list(text: String): List<String> = text.split(";").filter { it.isNotEmpty() }
}
