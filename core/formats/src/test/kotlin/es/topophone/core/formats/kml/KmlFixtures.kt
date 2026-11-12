package es.topophone.core.formats.kml

import java.io.File
import java.util.zip.ZipFile

/** Los ficheros de ejemplo de `fixtures/formatos/kml/` y lo que se espera de ellos, que genera `tools/formatos/generar_kml.py` (S031). */
object KmlFixtures {
    private val root: File by lazy {
        generateSequence(File("").absoluteFile) { it.parentFile }
            .first { File(it, "settings.gradle.kts").exists() && File(it, "fixtures").isDirectory }
    }

    val folder: File get() = File(root, "fixtures/formatos/kml")

    fun file(path: String): File = File(folder, path)

    fun bytes(path: String): ByteArray = file(path).readBytes()

    /** Los primeros [bytes] de un fichero. */
    fun head(path: String, bytes: Int = KmlFileKind.HEAD_BYTES): ByteArray = file(path).inputStream().use { it.readNBytes(bytes) }

    /** Las filas de un CSV de lo esperado como mapas columna → valor. Sirve para los CSV sin comillas (los que no llevan textos libres). */
    fun expected(name: String): List<Map<String, String>> {
        val lines = file(name).readText().lines().filter { it.isNotEmpty() }
        val header = lines.first().split(",")
        return lines.drop(1).map { line -> header.zip(line.split(",", limit = header.size)).toMap() }
    }

    /** Lo que se espera de abrir cada fichero: `esperado-archivos.csv` por nombre de fichero. */
    val files: Map<String, Map<String, String>> by lazy { expected("esperado-archivos.csv").associateBy { it.getValue("archivo") } }

    /** Los nombres de las entradas de un KMZ, sin las carpetas. */
    fun zipNames(path: String): List<String> = ZipFile(file(path)).use { zip ->
        zip.entries().asSequence().filter { !it.isDirectory }.map { it.name }.toList()
    }
}
