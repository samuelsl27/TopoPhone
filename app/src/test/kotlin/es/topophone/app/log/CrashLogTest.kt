package es.topophone.app.log

import es.topophone.core.model.log.AppLog
import es.topophone.core.model.log.LogCategory
import es.topophone.core.model.log.LogLevel
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.After
import org.junit.Test

/** Un cierre inesperado queda en el log antes de que la app se cierre (S014/RF-6). */
class CrashLogTest {

    private val original = Thread.getDefaultUncaughtExceptionHandler()

    @After
    fun restore() = Thread.setDefaultUncaughtExceptionHandler(original)

    private class RecordingLog : AppLog {
        val lines = mutableListOf<Triple<LogLevel, LogCategory, String>>()
        var flushed = 0

        override fun record(level: LogLevel, category: LogCategory, message: String) {
            lines += Triple(level, category, message)
        }

        override fun flushNow() {
            flushed++
        }
    }

    @Test
    fun `S014 RF-6 el error queda anotado con su pila, en disco, y sigue al manejador anterior`() {
        val log = RecordingLog()
        var passed: Throwable? = null
        Thread.setDefaultUncaughtExceptionHandler { _, error -> passed = error }
        CrashLog.install(log)
        val error = IllegalStateException("estado imposible")

        Thread.getDefaultUncaughtExceptionHandler()!!.uncaughtException(Thread.currentThread(), error)

        val (level, category, message) = log.lines.single()
        level shouldBe LogLevel.ERROR
        category shouldBe LogCategory.APP
        message shouldContain "Cierre inesperado"
        message shouldContain "java.lang.IllegalStateException: estado imposible"
        message shouldContain "CrashLogTest" // El lugar del código.
        log.flushed shouldBe 1
        passed shouldBe error
    }

    @Test
    fun `S014 RF-6 si el log falla, el error llega igualmente al manejador anterior`() {
        var passed: Throwable? = null
        Thread.setDefaultUncaughtExceptionHandler { _, error -> passed = error }
        CrashLog.install(object : AppLog {
            override fun record(level: LogLevel, category: LogCategory, message: String) = error("disco lleno")
        })
        val error = RuntimeException("fallo")

        Thread.getDefaultUncaughtExceptionHandler()!!.uncaughtException(Thread.currentThread(), error)

        passed shouldBe error
    }
}
