package es.topophone.core.formats.photo

import java.io.File

/** Los ejemplos de `fixtures/formatos/fotos-zip/`, que genera `tools/formatos/generar_fotos_zip.py` (S056/RF-4 a RF-7). */
object PhotoZipFixtures {
    private val root: File by lazy {
        generateSequence(File("").absoluteFile) { it.parentFile }
            .first { File(it, "settings.gradle.kts").exists() && File(it, "fixtures").isDirectory }
    }

    val folder: File get() = File(root, "fixtures/formatos/fotos-zip")

    /** Una foto de la entrada: de dónde viene (`camera` o `gallery`), su fecha en ms UTC y los bytes de su fichero. */
    class Photo(val origin: String, val takenAtMs: Long, val size: Int, val exif: Boolean)

    /** Un punto de la entrada con sus fotos en el orden en que se añadieron; puede no tener ninguna. */
    class Point(val name: String, val photos: List<Photo>)

    /** El índice que debe escribir la app para esa entrada, tal como está en el disco. */
    fun index(): ByteArray = File(folder, "fotos.csv").readBytes()

    /** Los nombres de la primera columna del índice (las fotos del ZIP, por orden), sin la cabecera. */
    fun photoNames(): List<String> =
        String(index(), Charsets.UTF_8).split("\r\n").drop(1).filter { it.isNotEmpty() }.map { it.substringBefore(',') }

    /** Los puntos de `entrada-fotos.txt`, en el orden en que se exportan: `point|<nombre>` y `photo|<origen>|<ms>|<bytes>|<exif|noexif>`. */
    fun points(): List<Point> {
        val points = ArrayList<Pair<String, MutableList<Photo>>>()
        for (line in File(folder, "entrada-fotos.txt").readLines(Charsets.UTF_8).filter { it.isNotBlank() }) {
            val parts = line.split("|")
            when (parts[0]) {
                "point" -> points += parts[1] to ArrayList()
                "photo" -> points.last().second += Photo(parts[1], parts[2].toLong(), parts[3].toInt(), parts[4] == "exif")
                else -> error("línea no reconocida: $line")
            }
        }
        return points.map { Point(it.first, it.second) }
    }
}
