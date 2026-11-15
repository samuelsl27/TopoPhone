package es.topophone.app.receiver

import es.topophone.core.survey.guide.ObservationWindow
import es.topophone.core.survey.guide.WindowSnapshot
import es.topophone.receiver.session.ConnectionState
import es.topophone.receiver.session.NtripState
import es.topophone.receiver.session.ReceiverSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * La ventana de los últimos 10 s de lo que llega del receptor, única para toda la app (S043/RF-5; plan D2, A1): se alimenta de las
 * épocas completas de [ReceiverSession.epochs] y de las estadísticas RTCM del NTRIP en curso, una foto por segundo, y se vacía cuando el
 * receptor se desconecta. Solo lee: nada se escribe en el receptor (RF-11). Funciona igual con un receptor real, con una reproducción
 * y con el receptor virtual, que usan la misma sesión.
 *
 * @param clock el reloj de la sesión (monótono, en ms): el mismo con que la sesión marca cada época.
 */
class ReceiverWindowRecorder(
    private val session: ReceiverSession,
    scope: CoroutineScope,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
    private val photoPeriodMs: Long = ObservationWindow.PHOTO_PERIOD_MS,
) {
    private val window = ObservationWindow()

    init {
        scope.launch { session.epochs.collect { window.onEpoch(it.epoch) } }
        scope.launch {
            session.state.map { it.connection.isOpen() }.distinctUntilChanged().collect { open -> if (!open) window.reset() }
        }
        scope.launch {
            session.state.map { it.ntrip as? NtripState.Streaming != null }.distinctUntilChanged().collect { streaming ->
                if (!streaming) window.clearRtcm() else photo()
            }
        }
        scope.launch {
            while (true) {
                delay(photoPeriodMs)
                photo()
            }
        }
    }

    private fun photo() {
        val streaming = session.state.value.ntrip as? NtripState.Streaming ?: return
        window.onRtcm(streaming.rtcm, clock())
    }

    /** Las épocas y los mensajes RTCM de los últimos 10 s hasta ahora. */
    fun snapshot(): WindowSnapshot = window.snapshot(clock())

    /** Hay correcciones NTRIP en curso (las comprobaciones RTCM solo valen entonces; RF-8). */
    val ntripOpen: Boolean get() = session.state.value.ntrip is NtripState.Streaming

    private fun ConnectionState.isOpen(): Boolean =
        this is ConnectionState.Receiving || this is ConnectionState.NoData || this is ConnectionState.Connecting
}
