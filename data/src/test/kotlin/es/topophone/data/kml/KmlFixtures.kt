package es.topophone.data.kml

import java.io.File

/** Los ficheros de ejemplo de `fixtures/formatos/kml/` y lo que se espera de ellos, que genera `tools/formatos/generar_kml.py` (S031). */
object KmlFixtures {
    private val root: File by lazy {
        generateSequence(File("").absoluteFile) { it.parentFile }
            .first { File(it, "settings.gradle.kts").exists() && File(it, "fixtures").isDirectory }
    }

    val folder: File get() = File(root, "fixtures/formatos/kml")

    fun file(path: String): File = File(folder, path)

    fun bytes(path: String): ByteArray = file(path).readBytes()

    /** Las filas de un CSV de lo esperado sin comillas (los que no llevan textos libres) como mapas columna → valor. */
    fun expected(name: String): List<Map<String, String>> {
        val lines = file(name).readText().lines().filter { it.isNotEmpty() }
        val header = lines.first().split(",")
        return lines.drop(1).map { line -> header.zip(line.split(",", limit = header.size)).toMap() }
    }

    /** Lo que se espera de abrir cada fichero: `esperado-archivos.csv` por nombre de fichero. */
    val files: Map<String, Map<String, String>> by lazy { expected("esperado-archivos.csv").associateBy { it.getValue("archivo") } }

    /** Un fichero de ejemplo como lo da el selector: su nombre, su tamaño y un flujo nuevo en cada llamada. */
    fun input(path: String, declaredSize: Long? = file(path).length()): KmlInput =
        KmlInput(file(path).name, declaredSize) { file(path).inputStream() }
}
