package es.topophone.data.log

import es.topophone.core.model.log.AppLog
import es.topophone.core.model.log.LogCategory
import es.topophone.core.model.log.LogLevel

/** Log en memoria para los tests de S014. */
class RecordingLog : AppLog {
    val entries = mutableListOf<Triple<LogLevel, LogCategory, String>>()

    val messages: List<String> get() = entries.map { it.third }

    override fun record(level: LogLevel, category: LogCategory, message: String) {
        synchronized(entries) { entries += Triple(level, category, message) }
    }
}
