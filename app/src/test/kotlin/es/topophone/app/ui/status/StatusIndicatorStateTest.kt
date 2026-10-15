package es.topophone.app.ui.status

import es.topophone.core.gnss.nmea.GgaFix
import es.topophone.core.gnss.nmea.ReplaySpeed
import es.topophone.core.gnss.nmea.SolutionStatus
import es.topophone.receiver.session.ConnectFailure
import es.topophone.receiver.session.ConnectionState
import es.topophone.receiver.session.ReceiverSnapshot
import es.topophone.receiver.session.ReplayInfo
import io.kotest.matchers.shouldBe
import org.junit.Test

/** Lo que el indicador de estado saca de la sesión (S003/RF-17 a RF-21, S004/RF-23). */
class StatusIndicatorStateTest {

    private val gga = GgaFix("101530.00", 40.4, -3.7, SolutionStatus.FLOAT, 18, 1.1, 650.0, 51.0, 2.0, "")

    @Test
    fun `S003 RF-17 sin conexion, con fallo o con la conexion perdida no hay receptor`() {
        listOf(ConnectionState.Disconnected, ConnectionState.Failed(ConnectFailure.TIMEOUT), ConnectionState.Lost("RS3")).forEach {
            toStatusIndicatorState(ReceiverSnapshot(connection = it, gga = gga)) shouldBe StatusIndicatorState()
        }
    }

    @Test
    fun `S003 RF-18 recibiendo se resumen la solucion y los satelites, sin la edad de las correcciones`() {
        toStatusIndicatorState(ReceiverSnapshot(ConnectionState.Receiving("RS3"), gga = gga, recording = true)) shouldBe
            StatusIndicatorState(source = StatusSource.RECEIVER, solution = SolutionStatus.FLOAT, satellites = 18)
    }

    @Test
    fun `S003 RF-19 con Sin datos, o conectando sin GGA, el indicador dice Sin datos`() {
        toStatusIndicatorState(ReceiverSnapshot(ConnectionState.NoData("RS3"), gga = gga)).stale shouldBe true
        toStatusIndicatorState(ReceiverSnapshot(ConnectionState.Connecting("RS3"))).stale shouldBe true
    }

    @Test
    fun `S003 RF-21 durante una reproduccion la fuente es la simulacion y al terminar no hay receptor`() {
        val replay = ReplayInfo("sesion", ReplaySpeed.X1, loop = false)

        toStatusIndicatorState(ReceiverSnapshot(ConnectionState.Receiving("sesion"), gga = gga, replay = replay)).source shouldBe
            StatusSource.REPLAY
        toStatusIndicatorState(ReceiverSnapshot(ConnectionState.ReplayFinished("sesion"), gga = gga, replay = replay)) shouldBe
            StatusIndicatorState()
    }

    @Test
    fun `S004 RF-23 durante la reconexion el indicador indica Reconectando`() {
        val state = toStatusIndicatorState(ReceiverSnapshot(ConnectionState.Reconnecting("RS3", 2, null, 0), gga = gga))

        state.reconnecting shouldBe true
        state.source shouldBe StatusSource.RECEIVER
    }
}
