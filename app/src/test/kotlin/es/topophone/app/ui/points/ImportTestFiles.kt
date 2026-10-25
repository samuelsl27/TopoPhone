package es.topophone.app.ui.points

import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream

/** Los ficheros de ejemplo de `fixtures/` como fuentes de la importación, sin pasar por el selector de ficheros (S028). */
object ImportTestFiles {
    private val root: File by lazy {
        generateSequence(File("").absoluteFile) { it.parentFile }
            .first { File(it, "settings.gradle.kts").exists() && File(it, "fixtures").isDirectory }
    }

    fun bytes(name: String): ByteArray = File(root, "fixtures/formatos/csv-importacion/$name").readBytes()

    fun s012(name: String): ByteArray = File(root, "fixtures/formatos/csv/$name").readBytes()

    /** Una fuente que sirve [bytes] cada vez que se abre, y cuenta cuántas veces se ha abierto. */
    class Source(val name: String, private val bytes: ByteArray, val declaredSize: Long? = bytes.size.toLong()) {
        var opened = 0
            private set

        fun asSource() = ImportSource(name, declaredSize) {
            opened++
            ByteArrayInputStream(bytes)
        }
    }

    fun fixture(name: String) = Source(name, bytes(name))

    fun of(name: String, text: String) = Source(name, text.toByteArray(Charsets.UTF_8))

    /** Una fuente de [size] bytes que no se conoce de antemano: repite [line] hasta llenarla, sin guardarla en memoria. */
    fun generated(name: String, size: Long, line: String = "a,b,c\n"): ImportSource = ImportSource(name, null) {
        val pattern = line.toByteArray(Charsets.UTF_8)
        object : InputStream() {
            private var position = 0L

            override fun read(): Int {
                if (position >= size) return -1
                return pattern[(position++ % pattern.size).toInt()].toInt() and 0xFF
            }

            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                if (position >= size) return -1
                val count = minOf(length.toLong(), size - position).toInt()
                for (i in 0 until count) buffer[offset + i] = pattern[((position + i) % pattern.size).toInt()]
                position += count
                return count
            }
        }
    }
}
