package es.topophone.app.log

import es.topophone.core.model.log.AppLog
import es.topophone.core.model.log.LogCategory

/**
 * Manejador de errores no capturados (S014/RF-6; plan, A3): anota el error con su pila en el log, lo escribe en disco y
 * pasa el error al manejador que había (el del sistema, que cierra la app). Si el log falla, el error sigue su camino.
 */
object CrashLog {
    fun install(log: AppLog) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                log.error(LogCategory.APP, "Cierre inesperado en el hilo ${thread.name}: ${error.stackTraceToString().trimEnd()}")
                log.flushNow()
            }
            previous?.uncaughtException(thread, error)
        }
    }
}
