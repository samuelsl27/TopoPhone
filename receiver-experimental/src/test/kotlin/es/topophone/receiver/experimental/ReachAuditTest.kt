package es.topophone.receiver.experimental

import es.topophone.core.model.log.AppLog
import es.topophone.core.model.log.LogCategory
import es.topophone.core.model.log.LogLevel
import es.topophone.core.model.log.LogLine
import io.kotest.matchers.shouldBe
import javax.net.SocketFactory
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Test

/** S044/RF-24: cada lectura de configuración y cada escritura quedan en el log de la app, con la hora del log y sin secretos; plan D12. */
class ReachAuditTest {

    private data class Entry(val level: LogLevel, val category: LogCategory, val message: String)

    private class RecordingLog : AppLog {
        val entries = mutableListOf<Entry>()

        override fun record(level: LogLevel, category: LogCategory, message: String) {
            entries += Entry(level, category, message)
        }
    }

    private val log = RecordingLog()
    private val audit = ReachAudit(log)

    @Test
    fun `S044 RF-24 una escritura confirmada anota operacion, valor anterior, enviado, leido despues y resultado`() {
        audit.write("altura de antena", before = "1.8", sent = "1.5", after = "1.5", result = "confirmado")

        log.entries shouldBe listOf(
            Entry(
                LogLevel.INFO,
                LogCategory.RECEIVER,
                "Experimental: altura de antena: antes 1.8, enviado 1.5, despues 1.5, resultado confirmado",
            ),
        )
    }

    @Test
    fun `S044 RF-21 una escritura no confirmada se anota como aviso y lo desconocido como tal`() {
        audit.write("logs", before = null, sent = "iniciar", after = null, result = "sin confirmacion")

        log.entries.single().level shouldBe LogLevel.WARNING
        log.entries.single().message shouldBe
            "Experimental: logs: antes desconocido, enviado iniciar, despues desconocido, resultado sin confirmacion"
    }

    @Test
    fun `S044 RF-24 una lectura de configuracion anota cuantos ajustes se leyeron y ninguno de sus valores`() {
        audit.configRead(settingCount = 17)

        log.entries shouldBe listOf(Entry(LogLevel.INFO, LogCategory.RECEIVER, "Experimental: configuracion leida, 17 ajustes"))
    }

    @Test
    fun `S044 RNF-T11 leer una configuracion con claves nunca deja un valor secreto en el log`() {
        val server = MockWebServer().also { it.start() }
        try {
            server.enqueue(MockResponse().setBody(ReachFixtures.text("configuration.json")))
            val client = ReachHttpClient(ReachEndpoint(server.hostName, server.port), SocketFactory.getDefault())

            runBlocking { ReachConfigReader(client, audit).read() }
        } finally {
            server.shutdown()
        }

        log.entries.size shouldBe 1
        val text = log.entries.joinToString("\n") { it.message }
        listOf("clave-de-mentira", "token-de-mentira", "psk-de-mentira").forEach { (it in text) shouldBe false }
    }

    @Test
    fun `S044 RF-24 la hora la pone el log de la app y sale en UTC con milisegundos`() {
        val timed = object : AppLog {
            val lines = mutableListOf<String>()

            override fun record(level: LogLevel, category: LogCategory, message: String) {
                lines += LogLine.format(AT_MS, level, category, message)
            }
        }

        ReachAudit(timed).configRead(3)

        timed.lines.single() shouldBe "2026-11-19T10:00:00.000Z  INFO   receptor  Experimental: configuracion leida, 3 ajustes\n"
    }

    @Test
    fun `S044 RF-24 sin rastro la lectura funciona igual`() {
        ReachTrail.NONE.configRead(1)
        ReachTrail.NONE.write("x", null, "y", null, "z")
    }

    private companion object {
        /** 2026-11-19T10:00:00Z */
        const val AT_MS = 1_795_082_400_000L
    }
}
