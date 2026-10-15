package es.topophone.receiver.session

import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Properties

/** Origen de una sesión de la lista (S003/RF-29). */
enum class SessionSource { RECORDED, IMPORTED }

/**
 * Sesión grabada: una carpeta con los datos del receptor y las correcciones enviadas, repartidos
 * en partes, y sus metadatos (S002/RF-42, S003/RF-24).
 */
data class RecordedSession(
    val id: String,
    val directory: File,
    val source: SessionSource,
    val receiver: String,
    val startedAt: Instant,
    val endedAt: Instant?,
    val receiverFiles: List<File>,
    val correctionFiles: List<File>,
) {
    /** Los ficheros que se comparten: los del receptor y los de las correcciones (S003/RF-30). */
    val dataFiles: List<File> get() = receiverFiles + correctionFiles

    val sizeBytes: Long get() = directory.listFiles().orEmpty().sumOf { it.length() }
}

/**
 * Sesiones en el almacenamiento privado de la app (S002/RF-42 a RF-44, S003/RF-24): una carpeta
 * por conexión, con los bytes tal como llegan del receptor y las correcciones tal como se le envían.
 * Sirven para comprobar los cálculos con referencias independientes (G0) y como datos de prueba (RNF-T12).
 */
class SessionLogStore(
    private val directory: File,
    private val clock: () -> Instant = Instant::now,
    private val maxSessions: Int = MAX_SESSIONS,
    private val partSizeBytes: Long = PART_SIZE_BYTES,
) : SessionRecorder {

    override fun start(description: String): SessionWriter? {
        migrateLegacyFiles()
        if (!directory.exists() && !directory.mkdirs()) return null
        val startedAt = clock()
        val base = NAME_FORMAT.format(startedAt)
        var folder = File(directory, base)
        var suffix = 1
        while (folder.exists()) folder = File(directory, "$base-${suffix++}")
        if (!folder.mkdirs()) return null
        val metadata = Metadata(SessionSource.RECORDED, description, startedAt, endedAt = null)
        metadata.writeTo(folder)
        prune(keep = maxSessions)
        return Writer(folder, metadata)
    }

    /** Sesiones de la más reciente a la más antigua. */
    fun sessions(): List<RecordedSession> {
        migrateLegacyFiles()
        return directory.listFiles { file -> file.isDirectory }
            .orEmpty()
            .mapNotNull(::read)
            .sortedWith(compareByDescending<RecordedSession> { it.startedAt }.thenByDescending { it.id })
    }

    /** Sesión más reciente (S002/RF-43). */
    fun latest(): RecordedSession? = sessions().firstOrNull()

    private fun read(folder: File): RecordedSession? {
        val metadata = Metadata.readFrom(folder) ?: return null
        val files = folder.listFiles().orEmpty().sortedBy { it.name }
        return RecordedSession(
            id = folder.name,
            directory = folder,
            source = metadata.source,
            receiver = metadata.receiver,
            startedAt = metadata.startedAt,
            endedAt = metadata.endedAt,
            receiverFiles = files.filter { it.name.startsWith(RECEIVER_PREFIX) && it.name.endsWith(RECEIVER_EXTENSION) },
            correctionFiles = files.filter { it.name.startsWith(CORRECTIONS_PREFIX) && it.name.endsWith(CORRECTIONS_EXTENSION) },
        )
    }

    private fun prune(keep: Int) {
        sessions().drop(keep).forEach { it.directory.deleteRecursively() }
    }

    /** Las sesiones de S002 eran ficheros sueltos `yyyyMMdd-HHmmss.nmea`: pasan a su carpeta. */
    private fun migrateLegacyFiles() {
        directory.listFiles { file -> file.isFile && file.name.endsWith(RECEIVER_EXTENSION) }.orEmpty().forEach { file ->
            val base = file.name.removeSuffix(RECEIVER_EXTENSION)
            val startedAt = parseName(base) ?: Instant.ofEpochMilli(file.lastModified())
            val folder = File(directory, base)
            if (folder.exists() || !folder.mkdirs()) return@forEach
            val modified = Instant.ofEpochMilli(file.lastModified())
            if (file.renameTo(File(folder, partName(RECEIVER_PREFIX, 1, RECEIVER_EXTENSION)))) {
                Metadata(SessionSource.RECORDED, receiver = "", startedAt, endedAt = modified).writeTo(folder)
            }
        }
    }

    private fun parseName(base: String): Instant? = try {
        LocalDateTime.parse(base.take(NAME_LENGTH), LOCAL_NAME_FORMAT).toInstant(ZoneOffset.UTC)
    } catch (_: DateTimeParseException) {
        null
    }

    /** Escribe una sesión en partes de [partSizeBytes]. Lector y cola de escritura la usan a la vez. */
    private inner class Writer(private val folder: File, private var metadata: Metadata) : SessionWriter {
        // El fichero del receptor existe desde el principio, aunque no llegue nada; el de correcciones, con la primera.
        private val receiver = PartedFile(folder, RECEIVER_PREFIX, RECEIVER_EXTENSION).apply { openNext() }
        private val corrections = PartedFile(folder, CORRECTIONS_PREFIX, CORRECTIONS_EXTENSION)
        private var closed = false

        @Synchronized
        override fun write(bytes: ByteArray, offset: Int, length: Int) {
            if (!closed) receiver.write(bytes, offset, length)
        }

        @Synchronized
        override fun writeCorrections(bytes: ByteArray) {
            if (!closed) corrections.write(bytes, 0, bytes.size)
        }

        @Synchronized
        override fun flush() {
            if (closed) return
            receiver.flush()
            corrections.flush()
            metadata = metadata.copy(endedAt = clock())
            metadata.writeTo(folder)
        }

        @Synchronized
        override fun close() {
            if (closed) return
            flush()
            receiver.close()
            corrections.close()
            closed = true
        }
    }

    /** Fichero repartido en partes numeradas; la primera se crea con el primer byte. */
    private inner class PartedFile(private val folder: File, private val prefix: String, private val extension: String) {
        private var part = 0
        private var stream: OutputStream? = null
        private var written = 0L

        fun write(bytes: ByteArray, offset: Int, length: Int) {
            var position = offset
            var remaining = length
            while (remaining > 0) {
                if (stream == null || written >= partSizeBytes) openNext()
                val chunk = minOf(remaining.toLong(), partSizeBytes - written).toInt()
                checkNotNull(stream).write(bytes, position, chunk)
                written += chunk
                position += chunk
                remaining -= chunk
            }
        }

        fun flush() {
            stream?.flush()
        }

        fun close() {
            stream?.close()
            stream = null
        }

        fun openNext() {
            close()
            part++
            written = 0
            stream = BufferedOutputStream(FileOutputStream(File(folder, partName(prefix, part, extension))))
        }
    }

    private data class Metadata(val source: SessionSource, val receiver: String, val startedAt: Instant, val endedAt: Instant?) {
        fun writeTo(folder: File) {
            val properties = Properties().apply {
                setProperty(KEY_SOURCE, source.name)
                setProperty(KEY_RECEIVER, receiver)
                setProperty(KEY_STARTED, startedAt.toString())
                endedAt?.let { setProperty(KEY_ENDED, it.toString()) }
            }
            val target = File(folder, METADATA_FILE)
            val temporary = File(folder, "$METADATA_FILE.tmp")
            temporary.outputStream().use { properties.store(it, null) }
            if (!temporary.renameTo(target)) {
                target.delete()
                temporary.renameTo(target)
            }
        }

        companion object {
            fun readFrom(folder: File): Metadata? {
                val file = File(folder, METADATA_FILE)
                if (!file.isFile) return null
                val properties = Properties().apply { file.inputStream().use(::load) }
                return try {
                    Metadata(
                        source = SessionSource.valueOf(properties.getProperty(KEY_SOURCE, SessionSource.RECORDED.name)),
                        receiver = properties.getProperty(KEY_RECEIVER, ""),
                        startedAt = Instant.parse(properties.getProperty(KEY_STARTED) ?: return null),
                        endedAt = properties.getProperty(KEY_ENDED)?.let(Instant::parse),
                    )
                } catch (_: IllegalArgumentException) {
                    null
                } catch (_: DateTimeParseException) {
                    null
                }
            }
        }
    }

    companion object {
        const val MAX_SESSIONS = 10
        const val PART_SIZE_BYTES = 16L * 1024 * 1024
        const val METADATA_FILE = "session.properties"
        const val RECEIVER_PREFIX = "receiver-"
        const val RECEIVER_EXTENSION = ".nmea"
        const val CORRECTIONS_PREFIX = "corrections-"
        const val CORRECTIONS_EXTENSION = ".rtcm3"
        private const val KEY_SOURCE = "source"
        private const val KEY_RECEIVER = "receiver"
        private const val KEY_STARTED = "startedAt"
        private const val KEY_ENDED = "endedAt"
        private const val NAME_LENGTH = 15
        private const val PART_DIGITS = 4
        private val LOCAL_NAME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
        private val NAME_FORMAT: DateTimeFormatter = LOCAL_NAME_FORMAT.withZone(ZoneOffset.UTC)

        private fun partName(prefix: String, part: Int, extension: String) = prefix + part.toString().padStart(PART_DIGITS, '0') + extension
    }
}
