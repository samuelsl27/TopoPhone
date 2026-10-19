package es.topophone.core.model.log

import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** Gravedad de una anotación del log (S014). */
enum class LogLevel(val label: String) {
    INFO("INFO"),
    WARNING("AVISO"),
    ERROR("ERROR"),
}

/** De qué parte de la app es una anotación del log (S014). */
enum class LogCategory(val label: String) {
    RECEIVER("receptor"),
    NTRIP("ntrip"),
    POINTS("puntos"),
    BACKUPS("copias"),
    APP("app"),
}

/**
 * El log de la app (S014/RF-1 a RF-7): cada parte anota aquí lo que hace y lo que falla. Anotar nunca bloquea a quien
 * anota (RNF-T13). Los textos no llevan contraseñas, usuarios NTRIP ni coordenadas de los puntos (S014/RF-7).
 */
interface AppLog {
    fun record(level: LogLevel, category: LogCategory, message: String)

    /** Escribe en disco todo lo anotado antes de volver; lo usa el manejador de errores no capturados (S014/RF-6). */
    fun flushNow() {}

    fun info(category: LogCategory, message: String) = record(LogLevel.INFO, category, message)

    fun warning(category: LogCategory, message: String) = record(LogLevel.WARNING, category, message)

    fun error(category: LogCategory, message: String) = record(LogLevel.ERROR, category, message)

    companion object {
        /** Sin log: para los tests y las clases que no lo reciben. */
        val NONE: AppLog = object : AppLog {
            override fun record(level: LogLevel, category: LogCategory, message: String) = Unit
        }
    }
}

/**
 * Una anotación en el fichero (S014; plan, "Una anotación"): `2026-10-19T09:15:52.123Z  INFO   receptor  Conectado`. Hora UTC
 * con milisegundos (RNF-T10); las líneas siguientes de un texto de varias líneas llevan dos espacios de sangría, para que
 * cada anotación empiece por su fecha.
 */
object LogLine {
    private val TIME = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC)
    private const val LEVEL_WIDTH = 5
    private const val CATEGORY_WIDTH = 8

    fun format(atMs: Long, level: LogLevel, category: LogCategory, message: String): String {
        val time = TIME.format(Instant.ofEpochMilli(atMs))
        val text = message.replace("\r\n", "\n").replace('\r', '\n').replace("\n", "\n  ")
        return "$time  ${level.label.padEnd(LEVEL_WIDTH)}  ${category.label.padEnd(CATEGORY_WIDTH)}  $text\n"
    }
}

/** Cuánto ocupa cada fichero del log y cuántos sobran (S014/RF-8, RF-9; plan, A2). */
object LogRetention {
    const val MIN_CHUNK_BYTES = 512 * 1024L
    private const val CHUNKS = 10

    /** Tamaño al que se corta cada fichero: la décima parte del límite, al menos 512 kB. */
    fun chunkBytes(limitBytes: Long): Long = maxOf(limitBytes / CHUNKS, MIN_CHUNK_BYTES)

    /** Cuántos de los ficheros más antiguos ([sizes] del más antiguo al más reciente) sobran; el último, en uso, nunca. */
    fun surplus(sizes: List<Long>, limitBytes: Long): Int {
        var total = sizes.sum()
        var count = 0
        while (count < sizes.size - 1 && total > limitBytes) {
            total -= sizes[count]
            count++
        }
        return count
    }
}

/** Tamaño del log que se elige en Ajustes (S014/RF-8, RF-9): de 5 a 50 MB, 10 MB por defecto (suposición de la spec). */
object LogSize {
    const val DEFAULT_MB = 10
    const val MIN_MB = 5
    const val MAX_MB = 50
    val OPTIONS_MB = listOf(5, 10, 20, 50)

    fun clamp(mb: Int): Int = mb.coerceIn(MIN_MB, MAX_MB)
}
