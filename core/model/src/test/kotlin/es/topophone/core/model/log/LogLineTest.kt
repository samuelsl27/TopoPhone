package es.topophone.core.model.log

import io.kotest.matchers.shouldBe
import java.time.Instant
import org.junit.jupiter.api.Test

/** Formato de cada anotación del log: hora UTC con milisegundos, nivel, categoría y texto (S014/RF-1; RNF-T10). */
class LogLineTest {

    private val at = Instant.parse("2026-10-19T09:15:52.123Z").toEpochMilli()

    @Test
    fun `S014 RF-1 cada anotacion lleva la hora UTC, el nivel, la categoria y el texto`() {
        LogLine.format(at, LogLevel.INFO, LogCategory.RECEIVER, "Conectado: RS3 Ana") shouldBe
            "2026-10-19T09:15:52.123Z  INFO   receptor  Conectado: RS3 Ana\n"
        LogLine.format(at, LogLevel.WARNING, LogCategory.NTRIP, "Cortada") shouldBe
            "2026-10-19T09:15:52.123Z  AVISO  ntrip     Cortada\n"
        LogLine.format(at, LogLevel.ERROR, LogCategory.BACKUPS, "No se pudo") shouldBe
            "2026-10-19T09:15:52.123Z  ERROR  copias    No se pudo\n"
    }

    @Test
    fun `S014 RF-1 los milisegundos se escriben siempre, tambien si son cero`() {
        val exact = Instant.parse("2026-10-19T09:15:52Z").toEpochMilli()

        LogLine.format(exact, LogLevel.INFO, LogCategory.APP, "x") shouldBe "2026-10-19T09:15:52.000Z  INFO   app       x\n"
    }

    @Test
    fun `S014 RF-6 un texto de varias lineas sigue con sangria para que cada anotacion empiece por su fecha`() {
        val text = "Cierre inesperado: java.lang.IllegalStateException: mal\r\n\tat es.topophone.A.b(A.kt:3)\n\tat es.topophone.C.d(C.kt:9)"

        LogLine.format(at, LogLevel.ERROR, LogCategory.APP, text) shouldBe
            "2026-10-19T09:15:52.123Z  ERROR  app       Cierre inesperado: java.lang.IllegalStateException: mal\n" +
            "  \tat es.topophone.A.b(A.kt:3)\n" +
            "  \tat es.topophone.C.d(C.kt:9)\n"
    }

    @Test
    fun `S014 RF-1 sin log no se anota nada y no falla`() {
        AppLog.NONE.record(LogLevel.INFO, LogCategory.APP, "nada")
        AppLog.NONE.flushNow()
    }
}
