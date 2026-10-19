package es.topophone.app.log

import es.topophone.app.log.ReceiverLogRecorder.Companion.entries
import es.topophone.core.gnss.nmea.ReplaySpeed
import es.topophone.core.model.log.AppLog
import es.topophone.core.model.log.LogCategory
import es.topophone.core.model.log.LogLevel
import es.topophone.core.ntrip.NtripFailure
import es.topophone.receiver.session.ConnectFailure
import es.topophone.receiver.session.ConnectionState
import es.topophone.receiver.session.NtripState
import es.topophone.receiver.session.ReceiverSnapshot
import es.topophone.receiver.session.ReplayInfo
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Test

/** Lo que el log anota del receptor y la NTRIP según cambia el estado de la sesión (S014/RF-1, RF-2, RF-5, RF-7). */
class ReceiverLogRecorderTest {

    private val idle = ReceiverSnapshot()

    private fun step(from: ReceiverSnapshot, to: ReceiverSnapshot) = entries(from, to).map { Triple(it.level, it.category, it.message) }

    @Test
    fun `S014 RF-1 una conexion se anota al conectar, al recibir y al desconectar`() {
        val connecting = idle.copy(connection = ConnectionState.Connecting("RS3 Ana"))
        val receiving = idle.copy(connection = ConnectionState.Receiving("RS3 Ana"))

        step(idle, connecting) shouldBe listOf(Triple(LogLevel.INFO, LogCategory.RECEIVER, "Conectando: RS3 Ana"))
        step(connecting, receiving) shouldBe listOf(Triple(LogLevel.INFO, LogCategory.RECEIVER, "Conectado: RS3 Ana"))
        step(receiving, receiving.copy(satellitesInView = 24)) shouldBe emptyList()
        step(receiving, idle) shouldBe listOf(Triple(LogLevel.INFO, LogCategory.RECEIVER, "Desconectado"))
    }

    @Test
    fun `S014 RF-1 y RF-5 los cortes y fallos de conexion se anotan con su causa`() {
        val receiving = idle.copy(connection = ConnectionState.Receiving("RS3 Ana"))
        val reconnecting = idle.copy(connection = ConnectionState.Reconnecting("RS3 Ana", 1, null, 0L, bluetoothOff = true))

        step(receiving, idle.copy(connection = ConnectionState.NoData("RS3 Ana"))).single().first shouldBe LogLevel.WARNING
        step(receiving, reconnecting).single().third shouldBe "Reconectando, intento 1: RS3 Ana (Bluetooth del móvil apagado)"
        step(reconnecting, reconnecting.copy(connection = ConnectionState.Reconnecting("RS3 Ana", 1, 5_000L, 0L, true))) shouldBe
            emptyList()
        step(reconnecting, idle.copy(connection = ConnectionState.Reconnecting("RS3 Ana", 2, null, 0L))).single().third shouldContain
            "intento 2"
        val busy = step(idle, idle.copy(connection = ConnectionState.Failed(ConnectFailure.BUSY))).single()
        busy.first shouldBe LogLevel.ERROR
        busy.third shouldContain "otro dispositivo"
    }

    @Test
    fun `S014 RF-1 las reproducciones se anotan al empezar y al terminar`() {
        val replay = idle.copy(
            connection = ConnectionState.Receiving("demo.nmea"),
            replay = ReplayInfo("demo.nmea", ReplaySpeed.X1, loop = true),
        )

        step(idle, replay).map { it.third } shouldBe listOf("Conectado: demo.nmea", "Reproducción: demo.nmea, velocidad ×1, en bucle")
        step(replay, replay.copy(connection = ConnectionState.ReplayFinished("demo.nmea"))).single().third shouldBe
            "Reproducción terminada: demo.nmea"
        step(replay, idle).map { it.third } shouldBe listOf("Desconectado", "Reproducción detenida")
    }

    @Test
    fun `S014 RF-5 los avisos del receptor se anotan una vez`() {
        val missing = idle.copy(nmeaMissing = true)

        step(idle, missing).single().third shouldContain "10 s sin frases NMEA"
        step(missing, missing) shouldBe emptyList()
        step(idle, idle.copy(recordingStoppedLowSpace = true)).single().third shouldContain "falta de espacio"
    }

    @Test
    fun `S014 RF-2 la NTRIP se anota al conectar, al recibir, al cortarse y al cerrar`() {
        val connecting = idle.copy(ntrip = NtripState.Connecting)
        val streaming = idle.copy(ntrip = NtripState.Streaming(10, 1_000L))

        step(idle, connecting).single() shouldBe Triple(LogLevel.INFO, LogCategory.NTRIP, "Conectando al caster")
        step(connecting, streaming).single().third shouldBe "Recibiendo correcciones"
        step(streaming, idle.copy(ntrip = NtripState.Streaming(2_000, 2_000L))) shouldBe emptyList()
        val cut = idle.copy(ntrip = NtripState.Reconnecting(1, NtripFailure.NO_DATA, 5_000L))
        step(streaming, cut).single() shouldBe
            Triple(LogLevel.WARNING, LogCategory.NTRIP, "Cortada (el caster no envía datos); reintento 1")
        step(cut, cut.copy(ntrip = NtripState.Reconnecting(1, NtripFailure.NO_DATA, 6_000L))) shouldBe emptyList()
        step(streaming, idle).single().third shouldBe "Cerrada"
    }

    @Test
    fun `S014 RF-2 y RF-7 un rechazo del caster se anota sin usuario ni contrasena`() {
        val rejected = step(
            idle.copy(ntrip = NtripState.Connecting),
            idle.copy(ntrip = NtripState.Stopped(NtripFailure.UNAUTHORIZED)),
        ).single()

        rejected.first shouldBe LogLevel.ERROR
        rejected.third shouldBe "Cerrada: el caster rechazó el usuario o la contraseña"
        rejected.third shouldNotContain "sgarcia"
    }

    @Test
    fun `S014 RF-5 la alarma de las correcciones se anota al saltar y al pasar`() {
        val alarm = idle.copy(correctionsAlarm = true)

        step(idle, alarm).single().first shouldBe LogLevel.WARNING
        step(alarm, idle).single().first shouldBe LogLevel.INFO
    }

    @Test
    fun `S014 RF-1 el registrador anota lo que cambia en la sesion`() {
        val lines = mutableListOf<String>()
        val log = object : AppLog {
            override fun record(level: LogLevel, category: LogCategory, message: String) {
                lines += message
            }
        }
        val state = MutableStateFlow(idle)
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val recorder = ReceiverLogRecorder(state, log, scope)
        recorder.start()
        recorder.start() // Una sola vez aunque se pida dos.

        state.value = idle.copy(connection = ConnectionState.Connecting("RS3"))
        state.value = idle.copy(connection = ConnectionState.Receiving("RS3"))

        lines shouldBe listOf("Conectando: RS3", "Conectado: RS3")
        scope.cancel()
    }
}
