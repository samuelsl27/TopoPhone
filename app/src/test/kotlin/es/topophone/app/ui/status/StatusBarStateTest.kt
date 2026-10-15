package es.topophone.app.ui.status

import es.topophone.core.gnss.nmea.Epoch
import es.topophone.core.gnss.nmea.GgaFix
import es.topophone.core.gnss.nmea.ReplaySpeed
import es.topophone.core.gnss.nmea.SolutionStatus
import es.topophone.core.gnss.nmea.TiltData
import es.topophone.core.gnss.nmea.TiltNotification
import es.topophone.core.gnss.nmea.TiltState
import es.topophone.receiver.session.ConnectFailure
import es.topophone.receiver.session.ConnectionState
import es.topophone.receiver.session.ReceiverSnapshot
import es.topophone.receiver.session.ReplayInfo
import io.kotest.matchers.shouldBe
import org.junit.Test

/** Lo que la barra de estado saca de la sesión (S003/RF-17 a RF-22). */
class StatusBarStateTest {

    private val gga = GgaFix("101530.00", 40.4, -3.7, SolutionStatus.FLOAT, 18, 1.1, 650.0, 51.0, 2.0, "")
    private val tilt = TiltData(36_930_000, TiltState.ALIGNMENT, TiltNotification.NONE, null, null, null, null)
    private val epoch = Epoch(36_930_000, null, gga, null, null, null, null, null, tilt, emptyMap(), emptyMap(), 0)

    @Test
    fun `S003 RF-17 sin conexion, con fallo o con la conexion perdida no hay receptor`() {
        listOf(ConnectionState.Disconnected, ConnectionState.Failed(ConnectFailure.TIMEOUT), ConnectionState.Lost("RS3")).forEach {
            toStatusBarState(ReceiverSnapshot(connection = it, gga = gga)) shouldBe StatusBarState()
        }
    }

    @Test
    fun `S003 RF-18 recibiendo se resumen la solucion, los satelites y la edad de las correcciones`() {
        toStatusBarState(ReceiverSnapshot(ConnectionState.Receiving("RS3"), gga = gga)) shouldBe
            StatusBarState(source = StatusSource.RECEIVER, solution = SolutionStatus.FLOAT, satellites = 18, correctionAgeS = 2.0)
    }

    @Test
    fun `S003 RF-19 con Sin datos, o conectando sin GGA, la barra dice Sin datos`() {
        toStatusBarState(ReceiverSnapshot(ConnectionState.NoData("RS3"), gga = gga)).stale shouldBe true
        toStatusBarState(ReceiverSnapshot(ConnectionState.Connecting("RS3"))).stale shouldBe true
    }

    @Test
    fun `S003 RF-22 el estado de la inclinacion sale de la ETC de la ultima epoca`() {
        toStatusBarState(ReceiverSnapshot(ConnectionState.Receiving("RS3"), gga = gga, epoch = epoch)).tilt shouldBe TiltState.ALIGNMENT
        toStatusBarState(ReceiverSnapshot(ConnectionState.Receiving("RS3"), gga = gga)).tilt shouldBe null
    }

    @Test
    fun `S003 RF-20 la barra indica la grabacion de la sesion`() {
        toStatusBarState(ReceiverSnapshot(ConnectionState.Receiving("RS3"), gga = gga, recording = true)).recording shouldBe true
        toStatusBarState(ReceiverSnapshot(ConnectionState.Receiving("RS3"), gga = gga)).recording shouldBe false
    }

    @Test
    fun `S003 RF-21 durante una reproduccion la fuente es la simulacion y al terminar no hay receptor`() {
        val replay = ReplayInfo("sesion", ReplaySpeed.X1, loop = false)

        toStatusBarState(ReceiverSnapshot(ConnectionState.Receiving("sesion"), gga = gga, replay = replay)).source shouldBe
            StatusSource.REPLAY
        toStatusBarState(ReceiverSnapshot(ConnectionState.ReplayFinished("sesion"), gga = gga, replay = replay)) shouldBe StatusBarState()
    }

    @Test
    fun `S004 RF-23 durante la reconexion la barra indica Reconectando`() {
        val state = toStatusBarState(ReceiverSnapshot(ConnectionState.Reconnecting("RS3", 2, null, 0), gga = gga))

        state.reconnecting shouldBe true
        state.source shouldBe StatusSource.RECEIVER
    }
}
