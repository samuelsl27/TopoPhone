package es.topophone.app.log

import es.topophone.core.formats.backup.BackupSettings
import es.topophone.core.formats.backup.SettingValue
import es.topophone.core.model.ntrip.NtripProfile
import es.topophone.core.model.ntrip.NtripProfileDraft
import es.topophone.receiver.session.RecordedSession
import es.topophone.receiver.session.SessionSource
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.time.Instant
import java.time.ZoneId
import java.util.zip.ZipInputStream
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** El informe de sistema: log, sesiones, datos de la app y del móvil, ajustes y perfiles sin secretos (S014/RF-11 a RF-15, RF-17). */
class SystemReportWriterTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val device = DeviceInfo(
        appVersion = "0.14.0",
        gitCommit = "0123456789ab",
        manufacturer = "Google",
        model = "Pixel 7",
        androidRelease = "15",
        sdkInt = 35,
        freeBytes = 18_432L * 1024 * 1024,
        permissions = linkedMapOf("BLUETOOTH_CONNECT" to true, "POST_NOTIFICATIONS" to false),
        batteryExempt = true,
        lastReceiver = "RS3 Ana",
    )
    private val profile = NtripProfile(
        "ign",
        NtripProfileDraft("ERGNSS (IGN)", "ergnss-tr.ign.es", 2101, useTls = false, username = "ana.perez", mountpoint = "VRS3M"),
        hasPassword = true,
        createdAtMs = 0,
        modifiedAtMs = 0,
    )
    private val settings = BackupSettings(
        mapOf(
            "settings" to mapOf("log_size_mb" to SettingValue.Int32(10)),
            "collector" to mapOf("collector_mode" to SettingValue.Text("AVERAGED")),
        ),
        appLanguage = "es",
    )
    private var flushed = 0

    private fun logFile(name: String, text: String) = File(folder.root, name).apply { writeText(text) }

    private fun session(id: String): RecordedSession {
        val dir = folder.newFolder(id)
        File(dir, "session.properties").writeText("source=RECORDED\n")
        val nmea = File(dir, "receiver-0001.nmea").apply { writeText("\$GNGGA,1*00\r\n") }
        return RecordedSession(
            id,
            dir,
            SessionSource.RECORDED,
            "RS3 Ana",
            Instant.parse("2026-10-19T09:00:00Z"),
            null,
            listOf(nmea),
            emptyList(),
        )
    }

    private fun writer(
        logs: List<File> = listOf(logFile("app-1.log", "uno\n"), logFile("app-2.log", "dos\n")),
        sessions: List<RecordedSession> = emptyList(),
    ) = ZipSystemReportWriter(
        device = { device },
        settings = { settings },
        profiles = { listOf(profile) },
        sessions = { sessions },
        activeSessionId = { null },
        logFiles = { logs },
        flushLog = { flushed++ },
        clock = { Instant.parse("2026-10-19T09:40:12Z").toEpochMilli() },
        zone = { ZoneId.of("Europe/Madrid") },
    )

    private fun entries(bytes: ByteArray): Map<String, String> = buildMap {
        ZipInputStream(bytes.inputStream()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                put(entry.name, zip.readBytes().decodeToString())
            }
        }
    }

    private fun report(writer: ZipSystemReportWriter = writer()): Map<String, String> {
        val out = ByteArrayOutputStream()
        runBlocking { writer.write(out) } shouldBe true
        return entries(out.toByteArray())
    }

    @Test
    fun `S014 RF-11 el informe lleva el log completo, escrito antes en disco`() {
        val zip = report()

        flushed shouldBe 1
        zip["log/app-1.log"] shouldBe "uno\n"
        zip["log/app-2.log"] shouldBe "dos\n"
    }

    @Test
    fun `S014 RF-12 el informe lleva las sesiones elegidas`() {
        val zip = report(writer(sessions = listOf(session("20261019-090000"))))

        zip["sesiones/20261019-090000/receiver-0001.nmea"] shouldBe "\$GNGGA,1*00\r\n"
        zip.keys shouldContainAll listOf("sesiones/20261019-090000/session.properties")
        zip.getValue("informe.txt") shouldContain "20261019-090000"
    }

    @Test
    fun `S014 RF-13 el informe dice la app, el movil, la zona horaria y el receptor`() {
        val text = report().getValue("informe.txt")

        for (expected in listOf(
            "Creado: 2026-10-19T09:40:12Z",
            "Zona horaria del móvil: Europe/Madrid",
            "App: 0.14.0 (commit 0123456789ab)",
            "Móvil: Google Pixel 7, Android 15 (API 35)",
            "Espacio libre: 18432 MB",
            "Permisos: BLUETOOTH_CONNECT=sí, POST_NOTIFICATIONS=no",
            "Sin optimización de batería: sí",
            "Último receptor: RS3 Ana",
        )) {
            text shouldContain expected
        }
    }

    @Test
    fun `S014 RF-14 el informe lleva los ajustes y donde conecta cada perfil NTRIP`() {
        val text = report().getValue("informe.txt")

        text shouldContain "  settings.log_size_mb = 10"
        text shouldContain "  collector.collector_mode = AVERAGED"
        text shouldContain "  Idioma de la app: es"
        text shouldContain "  ERGNSS (IGN): ergnss-tr.ign.es:2101, TLS no, punto de montaje VRS3M"
    }

    @Test
    fun `S014 RF-15 el informe no lleva el usuario NTRIP`() {
        report().values.forEach { it shouldNotContain "ana.perez" }
    }

    @Test
    fun `S014 RF-17 si no se puede escribir el informe no se ha guardado`() {
        val broken = object : OutputStream() {
            override fun write(b: Int) = throw IOException("sin espacio")
        }

        runBlocking { writer().write(broken) } shouldBe false
    }
}
