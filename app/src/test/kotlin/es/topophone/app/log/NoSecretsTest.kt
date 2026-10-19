package es.topophone.app.log

import es.topophone.app.receiver.CorrectionsController
import es.topophone.app.receiver.FakeNtripProfiles
import es.topophone.app.ui.receiver.FakePreferences
import es.topophone.app.ui.receiver.nmea
import es.topophone.core.formats.backup.BackupSettings
import es.topophone.core.formats.backup.SettingValue
import es.topophone.core.model.ntrip.NtripProfileDraft
import es.topophone.core.ntrip.NtripClient
import es.topophone.data.log.FileAppLog
import es.topophone.receiver.session.ConnectionState
import es.topophone.receiver.session.NtripState
import es.topophone.receiver.session.ReceiverSession
import es.topophone.receiver.session.SessionLogStore
import es.topophone.receiver.transport.StreamTransport
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import java.io.ByteArrayOutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import java.util.zip.ZipInputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Ni el log ni el informe llevan el usuario ni la contraseña NTRIP, tampoco tras un rechazo del caster (S014/RF-7, RF-15). */
class NoSecretsTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val username = "sgarcia"
    private val password = "s3cr3t"
    private val basic = Base64.getEncoder().encodeToString("$username:$password".toByteArray())
    private val secrets = listOf(username, password, basic)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val caster = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
    private val requests = CopyOnWriteArrayList<String>()

    @After
    fun tearDown() {
        scope.cancel()
        caster.close()
    }

    /** Un caster que lee la petición y rechaza el usuario y la contraseña. */
    private fun rejectEveryLogin() = scope.launch {
        while (true) {
            val socket = runCatching { caster.accept() }.getOrNull() ?: break
            socket.use {
                val reader = it.getInputStream().bufferedReader(Charsets.ISO_8859_1)
                requests += generateSequence { reader.readLine() }.takeWhile(String::isNotEmpty).joinToString("\n")
                it.getOutputStream().write("HTTP/1.1 401 Unauthorized\r\n\r\n".toByteArray())
            }
        }
    }

    private fun connectReceiver(session: ReceiverSession) = runBlocking {
        val toApp = PipedOutputStream()
        session.connect(StreamTransport("reach-RS3", PipedInputStream(toApp, 64 * 1024), ByteArrayOutputStream()))
        toApp.write(nmea("GNGGA,101530.20,4025.0080000,N,00342.2228000,W,4,24,0.6,650.123,M,51.234,M,1.0,0001").toByteArray())
        toApp.flush()
        withTimeout(5_000) { session.state.first { it.connection is ConnectionState.Receiving } }
    }

    private fun entries(bytes: ByteArray): Map<String, String> = buildMap {
        ZipInputStream(bytes.inputStream()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                put(entry.name, zip.readBytes().decodeToString())
            }
        }
    }

    @Test
    fun `S014 RF-7 y RF-15 tras un rechazo NTRIP no hay usuario ni clave`() = runBlocking<Unit> {
        rejectEveryLogin()
        val log = FileAppLog(folder.newFolder("log"), flowOf(10), scope)
        val store = SessionLogStore(folder.newFolder("sessions"))
        val session = ReceiverSession(scope, NtripClient("TopoPhone-test"), recorder = store, ntripBackoffMs = listOf(60_000L))
        ReceiverLogRecorder(session.state, log, scope).start()
        val profiles = FakeNtripProfiles()
        val draft = NtripProfileDraft("ERGNSS (IGN)", "127.0.0.1", caster.localPort, false, username, "VRS3M", mountpointNeedsGga = false)
        profiles.add("ign", draft, password)
        connectReceiver(session)

        CorrectionsController(session, profiles, FakePreferences(), scope, log).connect("ign")
        withTimeout(10_000) { session.state.first { it.ntrip is NtripState.Stopped } }
        session.disconnect()
        withTimeout(5_000) {
            while (log.files().none { "rechazó el usuario o la contraseña" in it.readText() }) {
                log.flushNow()
                delay(20)
            }
        }
        val out = ByteArrayOutputStream()
        val writer = ZipSystemReportWriter(
            device = { DeviceInfo("0.14.0", "0123456789ab", "Google", "Pixel 7", "15", 35, 0, emptyMap(), false, "reach-RS3") },
            settings = { BackupSettings(mapOf("settings" to mapOf("log_size_mb" to SettingValue.Int32(10))), appLanguage = "es") },
            profiles = { profiles.profiles().first() },
            sessions = store::sessions,
            activeSessionId = store::activeSessionId,
            logFiles = log::files,
            flushLog = log::flushNow,
        )
        writer.write(out) shouldBe true
        val report = entries(out.toByteArray())

        requests.single() shouldContain "Authorization: Basic $basic" // el caster sí los recibió
        report.getValue("informe.txt") shouldContain "ERGNSS (IGN): 127.0.0.1:${caster.localPort}, TLS no, punto de montaje VRS3M"
        report.keys.filter { it.startsWith("sesiones/") }.shouldNotBeEmpty()
        val texts = log.files().map { it.name to it.readText() } + report.toList()
        texts.forEach { (name, text) -> secrets.forEach { secret -> withClue(name) { text shouldNotContain secret } } }
    }

    private inline fun withClue(name: String, block: () -> Unit) = io.kotest.assertions.withClue(name, block)
}
