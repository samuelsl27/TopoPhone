package es.topophone.data.log

import es.topophone.core.model.log.AppLog
import es.topophone.core.model.log.LogCategory
import es.topophone.core.model.log.LogLevel
import es.topophone.core.model.log.LogLine
import es.topophone.core.model.log.LogRetention
import es.topophone.core.model.log.LogSize
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.Writer
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentLinkedQueue
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

/**
 * El log de la app en `dir` (S014/RF-1 a RF-9; plan, A1 y A2). Anotar pone la línea en una cola, avisa al escritor y
 * vuelve (RNF-T13); el escritor la escribe y vuelca a disco en cuanto la cola se vacía, así que lo anotado está en disco
 * antes de 1 s. Cada línea está en la cola o ya escrita: [flushNow] no puede dejarse ninguna a medio camino.
 * Los ficheros se cortan a la décima parte del límite ([sizeMb]) y, al pasarlo, se borran los más antiguos.
 * [flushNow] escribe lo pendiente en el hilo que la llama, para el manejador de errores no capturados (S014/RF-6).
 */
class FileAppLog(
    private val dir: File,
    sizeMb: Flow<Int>,
    scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
    ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : AppLog {
    private val pending = ConcurrentLinkedQueue<String>()
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private val lock = Any()

    @Volatile private var limitBytes = LogSize.DEFAULT_MB * MB
    private var writer: Writer? = null
    private var current: File? = null
    private var currentBytes = 0L

    init {
        scope.launch(ioDispatcher) {
            sizeMb.collect { mb ->
                limitBytes = LogSize.clamp(mb) * MB
                synchronized(lock) { prune() } // S014/RF-9: el nuevo límite, en el momento.
            }
        }
        scope.launch(ioDispatcher) {
            for (signal in wake) {
                synchronized(lock) {
                    drain()
                    flush()
                }
            }
        }
    }

    override fun record(level: LogLevel, category: LogCategory, message: String) {
        pending.add(LogLine.format(clock(), level, category, message))
        wake.trySend(Unit)
    }

    override fun flushNow() {
        synchronized(lock) {
            drain()
            flush()
        }
    }

    /** Los ficheros del log, del más antiguo al más reciente (S014/RF-11). */
    fun files(): List<File> = dir.listFiles { file -> file.isFile && NAME.matches(file.name) }.orEmpty().sortedBy { it.name }

    private fun drain() {
        while (true) write(pending.poll() ?: return)
    }

    private fun write(line: String) {
        val bytes = line.toByteArray(Charsets.UTF_8).size
        try {
            if (writer == null || currentBytes + bytes > LogRetention.chunkBytes(limitBytes)) openNext()
            writer?.write(line)
            currentBytes += bytes
        } catch (_: IOException) {
            // Sin espacio o sin la carpeta: el log no puede impedir que la app siga funcionando.
            close()
        }
    }

    private fun flush() {
        try {
            writer?.flush()
        } catch (_: IOException) {
            close()
        }
    }

    /** El último fichero si cabe algo más; si no, uno nuevo con un nombre posterior a todos los que hay. */
    private fun openNext() {
        val existing = files()
        val last = existing.lastOrNull()
        val file = if (writer == null && last != null && last.length() < LogRetention.chunkBytes(limitBytes)) {
            last
        } else {
            close()
            var at = clock()
            var candidate = File(dir, name(at))
            while (candidate.exists() || (last != null && candidate.name <= last.name)) candidate = File(dir, name(++at))
            candidate
        }
        dir.mkdirs()
        writer = FileOutputStream(file, true).bufferedWriter(Charsets.UTF_8) // Añade: nunca trunca lo que había.
        current = file
        currentBytes = file.length()
        prune()
    }

    /**
     * S014/RF-8: borra los ficheros más antiguos mientras el total pase del límite; el que está en uso, nunca. Del fichero en
     * uso se cuenta lo que ocupará al cortarse, para que el total no pase del límite mientras crece.
     */
    private fun prune() {
        val files = files()
        val chunk = LogRetention.chunkBytes(limitBytes)
        val sizes = files.map { if (it == current) maxOf(it.length(), currentBytes, chunk) else it.length() }
        files.take(LogRetention.surplus(sizes, limitBytes)).filter { it != current }.forEach { it.delete() }
    }

    private fun close() {
        try {
            writer?.close()
        } catch (_: IOException) {
            // Ya se ha perdido; se abrirá otro al escribir.
        }
        writer = null
    }

    private fun name(atMs: Long): String = "app-${STAMP.format(Instant.ofEpochMilli(atMs))}.log"

    private companion object {
        const val MB = 1024L * 1024
        val STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS").withZone(ZoneOffset.UTC)
        val NAME = Regex("""app-\d{8}-\d{6}-\d{3}\.log""")
    }
}
