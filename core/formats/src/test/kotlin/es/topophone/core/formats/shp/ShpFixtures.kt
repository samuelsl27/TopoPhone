package es.topophone.core.formats.shp

import java.io.File
import java.util.zip.ZipFile

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

    /** Los primeros [ShpFileKind.HEAD_BYTES] de un fichero. */
    fun head(path: String): ByteArray = file(path).inputStream().use { it.readNBytes(ShpFileKind.HEAD_BYTES) }

    /** Un fichero elegido en el selector: su nombre y su cabecera. */
    fun pick(path: String): ShpPick = ShpPick(file(path).name, head(path), file(path).length())

    /** Las entradas de un ZIP con su nombre, sus primeros bytes y su tamaño, sin las carpetas. */
    fun zipEntries(path: String): List<ShpEntry> = ZipFile(file(path)).use { zip ->
        zip.entries().asSequence().filter { !it.isDirectory }.map { entry ->
            ShpEntry(entry.name, zip.getInputStream(entry).use { it.readNBytes(ShpFileKind.HEAD_BYTES) }, entry.size)
        }.toList()
    }

    fun list(text: String): List<String> = text.split(";").filter { it.isNotEmpty() }
}
