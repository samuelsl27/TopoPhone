package es.topophone.app.log

import es.topophone.core.model.log.AppLog
import es.topophone.core.model.log.LogCategory
import es.topophone.core.model.log.LogLevel

/** Log en memoria para los tests de S014. */
class RecordingLog : AppLog {
    private val list = mutableListOf<Triple<LogLevel, LogCategory, String>>()

    val entries: List<Triple<LogLevel, LogCategory, String>> get() = synchronized(list) { list.toList() }

    val messages: List<String> get() = entries.map { it.third }

    override fun record(level: LogLevel, category: LogCategory, message: String) {
        synchronized(list) { list += Triple(level, category, message) }
    }
}
