package es.topophone.receiver.session

import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Ficheros de sesión en el almacenamiento privado de la app (S002/RF-42, RF-44): uno por
 * conexión, con los bytes tal como llegan del receptor. Sirven para comprobar los cálculos con
 * referencias independientes (G0) y, más adelante, como datos de prueba (RNF-T12).
 */
class SessionLogStore(
    private val directory: File,
    private val clock: () -> Instant = Instant::now,
    private val maxSessions: Int = MAX_SESSIONS,
) : SessionRecorder {

    override fun start(description: String): SessionWriter? {
        if (!directory.exists() && !directory.mkdirs()) return null
        val base = NAME_FORMAT.format(clock())
        var file = File(directory, "$base$EXTENSION")
        var suffix = 1
        while (file.exists()) file = File(directory, "$base-${suffix++}$EXTENSION")
        val stream = BufferedOutputStream(FileOutputStream(file))
        prune(keep = maxSessions)
        return object : SessionWriter {
            override fun write(bytes: ByteArray, offset: Int, length: Int) = stream.write(bytes, offset, length)

            override fun close() = stream.close()
        }
    }

    /** Sesiones de la más reciente a la más antigua. */
    fun sessions(): List<File> = directory.listFiles { file -> file.isFile && file.name.endsWith(EXTENSION) }
        .orEmpty()
        .sortedWith(compareByDescending<File> { it.lastModified() }.thenByDescending { it.name })

    /** Sesión más reciente (S002/RF-43). */
    fun latest(): File? = sessions().firstOrNull()

    private fun prune(keep: Int) {
        sessions().drop(keep).forEach { it.delete() }
    }

    companion object {
        const val MAX_SESSIONS = 10
        const val EXTENSION = ".nmea"
        private val NAME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC)
    }
}
