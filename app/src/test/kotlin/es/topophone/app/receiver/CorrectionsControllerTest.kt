package es.topophone.app.receiver

import es.topophone.app.ui.receiver.FakePreferences
import es.topophone.app.ui.receiver.nmea
import es.topophone.core.model.log.AppLog
import es.topophone.core.model.log.LogCategory
import es.topophone.core.model.log.LogLevel
import es.topophone.core.model.ntrip.NtripProfileDraft
import es.topophone.core.ntrip.NtripConfig
import es.topophone.core.ntrip.NtripConnector
import es.topophone.core.ntrip.NtripFailure
import es.topophone.data.ntrip.PasswordResult
import es.topophone.receiver.session.ConnectionState
import es.topophone.receiver.session.NtripState
import es.topophone.receiver.session.ReceiverSession
import es.topophone.receiver.session.ReceiverSnapshot
import es.topophone.receiver.transport.StreamTransport
import io.kotest.matchers.shouldBe
import java.io.ByteArrayOutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Test

/** Conectar con un perfil, detener, arranque automático y cambios del perfil en uso (S007/RF-6, RF-8, RF-9, RF-21, RF-22, RF-24, RF-26). */
class CorrectionsControllerTest {

    private val gga = nmea("GNGGA,101530.20,4025.0080000,N,00342.2228000,W,4,24,0.6,650.123,M,51.234,M,1.0,0001")
    private val sessionScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val controllerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val configs = CopyOnWriteArrayList<NtripConfig>()
    private var failure: NtripFailure? = null
    private val connector = NtripConnector { config, _, onConnected, _ ->
        configs += config
        failure?.let { return@NtripConnector it }
        onConnected()
        awaitCancellation()
    }
    private val session = ReceiverSession(sessionScope, connector, ntripBackoffMs = listOf(60_000L))
    private val preferences = FakePreferences()
    private val profiles = FakeNtripProfiles()

    private val ign = NtripProfileDraft("ERGNSS (IGN): red", "ergnss-tr.ign.es", 2101, false, "sgarcia", "VRS3M", mountpointNeedsGga = true)
    private val station =
        NtripProfileDraft("RGAPA", "rgapa.cartografia.asturias.es", 2101, false, "anonimo", "AVL13M", mountpointNeedsGga = false)

    @After
    fun tearDown() {
        controllerScope.cancel()
        sessionScope.cancel()
    }

    private fun controller() = CorrectionsController(session, profiles, preferences, controllerScope)

    private fun connectReceiver() {
        val toApp = PipedOutputStream()
        session.connect(StreamTransport("reach-RS3", PipedInputStream(toApp, 64 * 1024), ByteArrayOutputStream()))
        toApp.write(gga.toByteArray())
        toApp.flush()
        await { it.connection is ConnectionState.Receiving }
    }

    private fun await(predicate: (ReceiverSnapshot) -> Boolean): ReceiverSnapshot =
        runBlocking { withTimeout(5_000) { session.state.first(predicate) } }

    private fun <T> eventually(block: () -> T, expected: T) = runBlocking {
        withTimeout(5_000) { while (block() != expected) delay(20) }
    }

    @Test
    fun `S007 RF-22 al pulsar un perfil se conecta con sus datos y su contrasena guardada`() = runBlocking<Unit> {
        profiles.add("ign", ign, "s3cr3t")
        connectReceiver()

        controller().connect("ign") shouldBe ConnectResult.Started

        await { it.ntrip is NtripState.Streaming }
        configs.single() shouldBe NtripConfig("ergnss-tr.ign.es", 2101, "VRS3M", "sgarcia", "s3cr3t", useTls = false, sendGga = true)
    }

    @Test
    fun `S007 RF-21 la GGA se envia segun lo que dijo la tabla de fuentes y, si no se sabe, se envia`() = runBlocking<Unit> {
        profiles.add("station", station, "anonimo")
        profiles.add("manual", ign.copy(name = "A mano", mountpoint = "MIBASE", mountpointNeedsGga = null), "x")
        connectReceiver()
        val controller = controller()

        controller.connect("station")
        await { it.ntrip is NtripState.Streaming }
        controller.connect("manual")
        eventually({ configs.size }, 2)

        configs.map { it.sendGga } shouldBe listOf(false, true)
    }

    @Test
    fun `S007 RF-22 al pulsar otro perfil se cierra la conexion anterior y se abre la nueva`() = runBlocking<Unit> {
        profiles.add("ign", ign, "a")
        profiles.add("station", station, "anonimo")
        connectReceiver()
        val controller = controller()
        controller.connect("ign")
        await { it.ntrip is NtripState.Streaming }

        controller.connect("station")

        eventually({ configs.size }, 2)
        configs.last().host shouldBe "rgapa.cartografia.asturias.es"
        controller.status.value.activeProfileId shouldBe "station"
    }

    @Test
    fun `S007 RF-6 si la contrasena no se puede descifrar se pide y con la nueva se guarda y se conecta`() = runBlocking<Unit> {
        profiles.add("ign", ign, "vieja")
        profiles.undecryptable += "ign"
        connectReceiver()
        val controller = controller()

        controller.connect("ign") shouldBe ConnectResult.PasswordNeeded
        configs.size shouldBe 0

        controller.connect("ign", password = "nueva") shouldBe ConnectResult.Started
        await { it.ntrip is NtripState.Streaming }
        configs.single().password shouldBe "nueva"
        profiles.password("ign") shouldBe PasswordResult.Ok("nueva")
    }

    @Test
    fun `S007 RF-26 al conectar se recuerda el perfil y que estaba abierta, y al detener que no`() = runBlocking<Unit> {
        profiles.add("ign", ign, "s3cr3t")
        connectReceiver()
        val controller = controller()
        // El arranque automático mira la preferencia al enlazar el receptor. Si lo hiciera después de detener,
        // aún con la preferencia de antes, abriría otra vez la conexión: se espera a que ya la haya mirado.
        eventually({ preferences.ntripWasActiveReads.get() > 0 }, true)

        controller.connect("ign")
        eventually({ preferences.ntripWasActiveFlow.value }, true)
        preferences.lastNtripProfileFlow.value shouldBe "ign"

        controller.stop()

        eventually({ preferences.ntripWasActiveFlow.value }, false)
        // El estado del NTRIP cambia en la sesión, no a la vez que la preferencia: se espera también.
        await { it.ntrip == NtripState.Idle }
    }

    @Test
    fun `S007 RF-26 si estaba abierta al terminar la conexion anterior, se abre sola al conectar el receptor`() = runBlocking<Unit> {
        profiles.add("ign", ign, "s3cr3t")
        preferences.saveNtripSession("ign", active = true)
        controller()

        connectReceiver()

        await { it.ntrip is NtripState.Streaming }
        configs.single().mountpoint shouldBe "VRS3M"
    }

    @Test
    fun `S007 RF-26 si se detuvo o el perfil ya no existe no se abre sola`() = runBlocking<Unit> {
        preferences.saveNtripSession("borrado", active = true)
        profiles.add("ign", ign, "s3cr3t")
        controller()
        connectReceiver()
        delay(500)
        configs.size shouldBe 0

        session.disconnect()
        preferences.saveNtripSession("ign", active = false)
        connectReceiver()
        delay(500)
        configs.size shouldBe 0
    }

    @Test
    fun `S007 RF-24 si el caster rechaza las credenciales ya no se abre sola la proxima vez`() = runBlocking<Unit> {
        profiles.add("ign", ign, "mala")
        failure = NtripFailure.UNAUTHORIZED
        connectReceiver()
        val controller = controller()

        controller.connect("ign")

        await { it.ntrip == NtripState.Stopped(NtripFailure.UNAUTHORIZED) }
        eventually({ preferences.ntripWasActiveFlow.value }, false)
    }

    @Test
    fun `S007 RF-9 al guardar cambios en el perfil en uso se vuelve a conectar con los datos nuevos`() = runBlocking<Unit> {
        profiles.add("ign", ign, "s3cr3t")
        connectReceiver()
        val controller = controller()
        controller.connect("ign")
        await { it.ntrip is NtripState.Streaming }

        profiles.update("ign", ign.copy(mountpoint = "CERCANA3M"), password = null)

        eventually({ configs.size }, 2)
        configs.last().mountpoint shouldBe "CERCANA3M"
    }

    @Test
    fun `S014 RF-2 y RF-7 se anota a que caster se conecta, sin usuario ni contrasena`() = runBlocking<Unit> {
        val lines = mutableListOf<String>()
        val log = object : AppLog {
            override fun record(level: LogLevel, category: LogCategory, message: String) {
                lines += message
            }
        }
        profiles.add("ign", ign, "s3cr3t")
        connectReceiver()

        CorrectionsController(session, profiles, preferences, controllerScope, log).connect("ign")

        lines.single() shouldBe "Perfil «ERGNSS (IGN): red»: ergnss-tr.ign.es:2101, punto de montaje VRS3M"
        lines.none { "sgarcia" in it || "s3cr3t" in it } shouldBe true
    }

    @Test
    fun `S007 RF-8 al borrar el perfil en uso se cierra la conexion NTRIP`() = runBlocking<Unit> {
        profiles.add("ign", ign, "s3cr3t")
        connectReceiver()
        val controller = controller()
        controller.connect("ign")
        await { it.ntrip is NtripState.Streaming }

        profiles.delete("ign")

        await { it.ntrip == NtripState.Idle }
        controller.status.value.activeProfileId shouldBe null
    }

    @Test
    fun `S007 RF-31 el umbral de la alarma de Ajustes llega a la sesion`() = runBlocking<Unit> {
        preferences.correctionsAlarmFlow.value = 2 // con 10 s, el valor por defecto, no saltaría en los 5 s de espera
        profiles.add("ign", ign, "s3cr3t")
        connectReceiver()
        val controller = controller()

        controller.connect("ign") // el caster falso conecta pero no envía correcciones

        await { it.correctionsAlarm }
    }
}
