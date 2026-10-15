package es.topophone.receiver.session

import es.topophone.core.gnss.nmea.NmeaFramer
import es.topophone.core.gnss.nmea.NmeaSentence
import es.topophone.core.gnss.nmea.NmeaTime
import es.topophone.core.gnss.nmea.RmcParser
import es.topophone.core.gnss.nmea.ZdaParser
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Properties

/** Queda menos espacio libre del necesario para seguir grabando (S003/RF-28). */
class LowStorageException : IOException("Poco espacio libre para grabar la sesión")

/** Resultado de importar un fichero NMEA (S003/RF-33, S003/RF-34). */
sealed interface ImportResult {
    data class Imported(val id: String) : ImportResult

    /** No hay ninguna frase NMEA válida con hora. */
    data object NoNmea : ImportResult

    /** Supera [SessionLogStore.MAX_IMPORT_BYTES]. */
    data object TooLarge : ImportResult
}

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
    private val partSizeBytes: Long = PART_SIZE_BYTES,
    private val spaceLimitBytes: () -> Long = { DEFAULT_SPACE_LIMIT_BYTES },
    private val freeSpaceBytes: () -> Long = { directory.usableSpace },
    private val maxImportBytes: Long = MAX_IMPORT_BYTES,
) : SessionRecorder {

    /** @throws LowStorageException si el móvil tiene menos de [MIN_FREE_BYTES] libres (S003/RF-28). */
    override fun start(description: String): SessionWriter? {
        migrateLegacyFiles()
        if (!directory.exists() && !directory.mkdirs()) return null
        if (freeSpaceBytes() < MIN_FREE_BYTES) throw LowStorageException()
        val startedAt = clock()
        val folder = newFolder(startedAt) ?: return null
        val metadata = Metadata(SessionSource.RECORDED, description, startedAt, endedAt = null)
        metadata.writeTo(folder)
        val writer = Writer(folder, metadata)
        activeWriter = writer
        prune(writer)
        return writer
    }

    /**
     * Copia un fichero NMEA de texto como sesión "Importada" para reproducirlo (S003/RF-33). Se valida
     * por el contenido, no por la extensión (RNF-T5): sin frases NMEA válidas con hora, o con más de
     * [MAX_IMPORT_BYTES], no se importa (S003/RF-34). El inicio y la duración salen de sus horas NMEA.
     */
    fun import(name: String, input: InputStream, sizeHint: Long?): ImportResult {
        if (sizeHint != null && sizeHint > maxImportBytes) return ImportResult.TooLarge
        migrateLegacyFiles()
        if (!directory.exists() && !directory.mkdirs()) return ImportResult.NoNmea
        val importedAt = clock()
        val folder = newFolder(importedAt) ?: return ImportResult.NoNmea
        val scan = NmeaScan()
        val framer = NmeaFramer()
        val out = PartedFile(folder, RECEIVER_PREFIX, RECEIVER_EXTENSION)
        var total = 0L
        try {
            val buffer = ByteArray(IMPORT_BUFFER)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                total += n
                if (total > maxImportBytes) {
                    out.close()
                    folder.deleteRecursively()
                    return ImportResult.TooLarge
                }
                out.write(buffer, 0, n)
                framer.feed(buffer, 0, n).forEach(scan::accept)
            }
        } finally {
            out.close()
        }
        val started = scan.startedAt() ?: importedAt
        if (!scan.hasTime) {
            folder.deleteRecursively()
            return ImportResult.NoNmea
        }
        Metadata(SessionSource.IMPORTED, name, started, started.plusMillis(scan.durationMs)).writeTo(folder)
        prune(activeWriter)
        return ImportResult.Imported(folder.name)
    }

    private fun newFolder(startedAt: Instant): File? {
        val base = NAME_FORMAT.format(startedAt)
        var folder = File(directory, base)
        var suffix = 1
        while (folder.exists()) folder = File(directory, "$base-${suffix++}")
        return folder.takeIf { it.mkdirs() }
    }

    /** Horas NMEA de un fichero importado: si tiene alguna, su inicio (con fecha de RMC o ZDA) y su duración. */
    private class NmeaScan {
        private var firstTime: Int? = null
        private var lastTime: Int? = null
        private var datedAt: Pair<LocalDate, Int>? = null
        var durationMs = 0L
            private set
        val hasTime: Boolean get() = firstTime != null

        fun accept(sentence: NmeaSentence) {
            if (sentence.type !in TIMED_TYPES) return
            val time = NmeaTime.parse(sentence.fields.firstOrNull().orEmpty()) ?: return
            val previous = lastTime
            if (previous == null) {
                firstTime = time
            } else if (time != previous) {
                durationMs += forward(previous, time) ?: 0
            }
            lastTime = time
            if (datedAt == null) {
                val date = when (sentence.type) {
                    "RMC" -> RmcParser.parse(sentence)?.date
                    "ZDA" -> ZdaParser.parse(sentence)?.date
                    else -> null
                }
                if (date != null) datedAt = date to time
            }
        }

        /** Instante de la primera frase con hora, si el fichero trae alguna fecha. */
        fun startedAt(): Instant? {
            val (date, time) = datedAt ?: return null
            val elapsed = forward(checkNotNull(firstTime), time) ?: 0
            return date.atStartOfDay().toInstant(ZoneOffset.UTC).plusMillis(time.toLong() - elapsed)
        }

        /** Milisegundos de [from] a [to] hacia delante (con el paso de medianoche); `null` si es un salto atrás. */
        private fun forward(from: Int, to: Int): Long? {
            var delta = (to - from).toLong()
            if (delta < 0) delta += NmeaTime.MILLIS_PER_DAY
            return delta.takeIf { it < NmeaTime.MILLIS_PER_DAY / 2 }
        }
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

    @Volatile private var activeWriter: Writer? = null

    /** Sesión que se está grabando, si la hay (S003/RF-31). */
    fun activeSessionId(): String? = activeWriter?.folder?.name

    /** Borra una sesión; la que se está grabando no se puede borrar (S003/RF-31). */
    @Synchronized
    fun delete(id: String): Boolean {
        if (id == activeSessionId()) return false
        val folder = File(directory, id)
        if (folder.parentFile != directory || !folder.isDirectory) return false
        return folder.deleteRecursively()
    }

    /** Aplica el límite de espacio en ese momento, p. ej. al cambiarlo en Ajustes (S003/RF-26). */
    fun applySpaceLimit() = prune(activeWriter)

    /**
     * Borra las sesiones más antiguas mientras se pase del límite, sin tocar nunca la más reciente
     * ni la que se graba (S003/RF-25); si aun así se pasa, la que se graba pierde sus partes más
     * antiguas (S003/RF-27).
     */
    @Synchronized
    private fun prune(active: Writer?) {
        val all = sessions()
        val limit = spaceLimitBytes()
        var total = all.sumOf { it.sizeBytes }
        for (session in all.drop(1).asReversed()) {
            if (total <= limit) break
            if (session.directory == active?.folder) continue
            total -= session.sizeBytes
            session.directory.deleteRecursively()
        }
        if (total > limit) active?.dropOldestParts(total - limit)
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
    private inner class Writer(val folder: File, private var metadata: Metadata) : SessionWriter {
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

        /** Vacía a disco, actualiza el fin y aplica el límite de espacio (S003/RF-25, S003/RF-27, S003/RF-28). */
        @Synchronized
        override fun flush() {
            if (closed) return
            receiver.flush()
            corrections.flush()
            metadata = metadata.copy(endedAt = clock())
            metadata.writeTo(folder)
            if (freeSpaceBytes() < MIN_FREE_BYTES) {
                close()
                throw LowStorageException()
            }
            prune(this)
        }

        /** Borra las partes más antiguas, nunca las que se están escribiendo, hasta liberar [excess] bytes. */
        fun dropOldestParts(excess: Long) {
            var remaining = excess
            val current = setOfNotNull(receiver.currentFile, corrections.currentFile)
            folder.listFiles().orEmpty()
                .filter { (it.name.startsWith(RECEIVER_PREFIX) || it.name.startsWith(CORRECTIONS_PREFIX)) && it !in current }
                .sortedWith(compareBy<File> { it.lastModified() }.thenBy { it.name.substringAfter("-") })
                .forEach { file ->
                    if (remaining <= 0) return
                    remaining -= file.length()
                    file.delete()
                }
        }

        @Synchronized
        override fun close() {
            if (closed) return
            closed = true
            receiver.flush()
            corrections.flush()
            metadata = metadata.copy(endedAt = clock())
            metadata.writeTo(folder)
            receiver.close()
            corrections.close()
            if (activeWriter === this) activeWriter = null
        }
    }

    /** Fichero repartido en partes numeradas; la primera se crea con el primer byte. */
    private inner class PartedFile(private val folder: File, private val prefix: String, private val extension: String) {
        private var part = 0
        private var stream: OutputStream? = null
        private var written = 0L
        var currentFile: File? = null
            private set

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
            val file = File(folder, partName(prefix, part, extension))
            currentFile = file
            stream = BufferedOutputStream(FileOutputStream(file))
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
        /** 500 MB (en unidades decimales, como Android) (S003/RF-25). */
        const val DEFAULT_SPACE_LIMIT_BYTES = 500_000_000L

        /** Por debajo de 100 MB libres se deja de grabar (S003/RF-28). */
        const val MIN_FREE_BYTES = 100_000_000L
        const val PART_SIZE_BYTES = 16L * 1024 * 1024

        /** Tamaño máximo de un fichero importado: 200 MB (S003/RF-34). */
        const val MAX_IMPORT_BYTES = 200_000_000L
        private const val IMPORT_BUFFER = 64 * 1024
        private val TIMED_TYPES = setOf("GGA", "GST", "RMC", "ZDA", "ETC")
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
