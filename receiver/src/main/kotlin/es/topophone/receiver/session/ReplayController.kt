package es.topophone.receiver.session

import es.topophone.core.gnss.nmea.ReplaySpeed
import es.topophone.receiver.transport.ReplayTransport
import java.io.File

/**
 * Arranca la reproducción de una sesión grabada o importada en la sesión del receptor y recuerda la
 * última para volver a reproducirla (S003/RF-32, S003/RF-40). Reproducir cierra antes la conexión
 * con el receptor y la NTRIP (S003/RF-42), porque la sesión del receptor es una sola (ADR-11).
 */
class ReplayController(private val session: ReceiverSession, private val sessions: () -> List<RecordedSession>) {

    @Volatile private var last: Pair<String, List<File>>? = null

    /** Hay un receptor real conectado: reproducir lo desconectará (S003/RF-42). */
    val receiverConnected: Boolean
        get() {
            val snapshot = session.state.value
            val connection = snapshot.connection
            val connected =
                connection is ConnectionState.Connecting || connection is ConnectionState.Receiving || connection is ConnectionState.NoData
            return connected && snapshot.replay == null
        }

    /** Reproduce la sesión [id] sin bucle, a ×1 salvo que se pida otra velocidad; `false` si ya no existe o no tiene datos. */
    fun play(id: String, speed: ReplaySpeed = ReplaySpeed.X1): Boolean {
        val recorded = sessions().firstOrNull { it.id == id } ?: return false
        if (recorded.receiverFiles.isEmpty()) return false
        val name = if (recorded.source == SessionSource.IMPORTED) recorded.receiver else recorded.id
        last = name to recorded.receiverFiles
        session.connect(ReplayTransport(name, recorded.receiverFiles, speed, loop = false))
        return true
    }

    /** Vuelve a reproducir la última sesión, con la velocidad y el bucle que tenía (S003/RF-40). */
    fun playAgain(): Boolean {
        val (name, files) = last ?: return false
        val previous = session.state.value.replay
        session.connect(ReplayTransport(name, files, previous?.speed ?: ReplaySpeed.X1, previous?.loop ?: false))
        return true
    }
}
