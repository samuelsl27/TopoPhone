package es.topophone.app.log

import es.topophone.core.formats.backup.BackupSettings
import es.topophone.receiver.session.RecordedSession
import es.topophone.receiver.session.SessionSource
import io.kotest.matchers.longs.shouldBeLessThan
import io.kotest.matchers.longs.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import java.util.zip.ZipFile
import kotlin.time.Duration.Companion.seconds
import kotlin.time.measureTimedValue
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Con 25 MB de sesiones el informe se crea en menos de 10 s (S014, RNF de la spec). El PC sirve de cota; la medida en el móvil es de la demo. */
class SystemReportPerformanceTest {

    @get:Rule
    val folder = TemporaryFolder()

    /** [bytes] de frases GGA con la hora y la posición cambiando, para que no se compriman más que las de verdad. */
    private fun nmeaFile(file: File, bytes: Long): File {
        file.bufferedWriter().use { out ->
            var written = 0L
            var i = 0
            while (written < bytes) {
                val s = i % 86_400
                val line = String.format(
                    Locale.ROOT,
                    "\$GNGGA,%02d%02d%02d.%02d,4025.%07d,N,00342.%07d,W,4,%02d,0.6,%.3f,M,51.234,M,1.0,0001*5C\r\n",
                    s / 3600, s / 60 % 60, s % 60, i % 5 * 20, (i * 7_919L) % 10_000_000, (i * 104_729L) % 10_000_000, 18 + i % 9,
                    650 + (i % 1000) / 1000.0,
                )
                out.write(line)
                written += line.length
                i++
            }
        }
        return file
    }

    private fun session(index: Int, megabytes: Int): RecordedSession {
        val id = "20261019-0${index}0000"
        val dir = folder.newFolder(id)
        File(dir, "session.properties").writeText("source=RECORDED\n")
        val nmea = nmeaFile(File(dir, "receiver-0001.nmea"), megabytes * MB)
        val started = Instant.parse("2026-10-19T0$index:00:00Z")
        return RecordedSession(id, dir, SessionSource.RECORDED, "RS3", started, started.plusSeconds(3_000), listOf(nmea), emptyList())
    }

    @Test
    fun `S014 RNF el informe con 25 MB de sesiones tarda menos de 10 s`() {
        val sessions = (1..6).map { session(it, 5) } // 30 MB: entran las más recientes hasta 25 MB
        val logs = (1..10).map { nmeaFile(File(folder.root, "app-$it.log"), MB) }
        val writer = ZipSystemReportWriter(
            device = { DeviceInfo("0.14.0", "0123456789ab", "Google", "Pixel 7", "15", 35, 0, emptyMap(), false, null) },
            settings = { BackupSettings(emptyMap(), appLanguage = "es") },
            profiles = { emptyList() },
            sessions = { sessions },
            activeSessionId = { null },
            logFiles = { logs },
            flushLog = {},
            zone = { ZoneId.of("Europe/Madrid") },
        )
        val report = File(folder.root, "TopoPhone_informe_20261019-1140.zip")

        val (ok, time) = measureTimedValue { runBlocking { report.outputStream().use { writer.write(it) } } }

        ok shouldBe true
        time.inWholeMilliseconds shouldBeLessThan 10.seconds.inWholeMilliseconds
        val sessionBytes = ZipFile(report).use { zip ->
            zip.entries().toList().filter { it.name.startsWith("sesiones/") }.sumOf { it.size }
        }
        sessionBytes shouldBeLessThanOrEqual 25 * MB + 1_024 // las sesiones de 5 MB, más su session.properties
        sessionBytes shouldBeLessThan 30 * MB
    }

    private companion object {
        const val MB = 1024L * 1024
    }
}
