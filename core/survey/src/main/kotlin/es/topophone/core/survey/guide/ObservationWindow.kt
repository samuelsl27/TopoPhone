package es.topophone.core.survey.guide

import es.topophone.core.gnss.nmea.Epoch
import es.topophone.core.gnss.rtcm.RtcmStats

/**
 * Lo que se ve de la ventana en un instante: las épocas de los últimos segundos, de la más antigua a la más reciente, y los mensajes
 * RTCM que llegaron en ellos (S043/RF-5).
 *
 * @property rtcmDelta cuántos mensajes de cada tipo llegaron entre la primera y la última foto de la ventana; `null` si hay menos de dos
 *   fotos, es decir, si no hay correcciones NTRIP que mirar.
 */
data class WindowSnapshot(val epochs: List<Epoch>, val rtcmDelta: Map<Int, Long>?)

/**
 * Las épocas del receptor y las estadísticas RTCM de los últimos [windowMs] ms, para las comprobaciones previas (S043/RF-5, plan D1 y
 * A1). No lee nada del receptor: quien la usa le va dando lo que ya llega. El reloj es el monótono de la sesión (el
 * [Epoch.completedAtMs]). Es seguro entre hilos.
 */
class ObservationWindow(private val windowMs: Long = DEFAULT_WINDOW_MS) {

    private class Photo(val atMs: Long, val counts: Map<Int, Long>)

    private val epochs = ArrayDeque<Epoch>()
    private val photos = ArrayDeque<Photo>()

    /** Añade una época completa. */
    @Synchronized
    fun onEpoch(epoch: Epoch) {
        epochs.addLast(epoch)
        prune(epoch.completedAtMs)
    }

    /**
     * Anota una foto de las estadísticas RTCM del NTRIP en curso (como mucho una por segundo). Si el total de algún tipo baja, se
     * abrió otra conexión NTRIP y se empieza de nuevo (S043, plan A1).
     */
    @Synchronized
    fun onRtcm(stats: RtcmStats, nowMs: Long) {
        val last = photos.lastOrNull()
        if (last != null && last.counts.any { (type, count) -> (stats.counts[type] ?: 0L) < count }) photos.clear()
        if (last != null && photos.isNotEmpty() && nowMs - last.atMs < PHOTO_PERIOD_MS) return
        photos.addLast(Photo(nowMs, stats.counts))
        prune(nowMs)
    }

    /** Sin correcciones NTRIP: se borran las fotos RTCM y las épocas siguen. */
    @Synchronized
    fun clearRtcm() {
        photos.clear()
    }

    /** Empieza de cero: una conexión nueva o el receptor desconectado. */
    @Synchronized
    fun reset() {
        epochs.clear()
        photos.clear()
    }

    /** Las épocas y los mensajes RTCM de los últimos [windowMs] ms hasta [nowMs]. */
    @Synchronized
    fun snapshot(nowMs: Long): WindowSnapshot {
        prune(nowMs)
        val first = photos.firstOrNull()
        val last = photos.lastOrNull()
        val delta = if (first == null || last == null || photos.size < 2) {
            null
        } else {
            last.counts.mapValues { (type, count) -> count - (first.counts[type] ?: 0L) }.filterValues { it > 0 }
        }
        return WindowSnapshot(epochs.toList(), delta)
    }

    private fun prune(nowMs: Long) {
        val from = nowMs - windowMs
        while (epochs.isNotEmpty() && epochs.first().completedAtMs < from) epochs.removeFirst()
        while (photos.isNotEmpty() && photos.first().atMs < from) photos.removeFirst()
    }

    companion object {
        /** Diez segundos (S043/RF-5). */
        const val DEFAULT_WINDOW_MS = 10_000L

        /** Una foto de las RTCM por segundo como mucho. */
        const val PHOTO_PERIOD_MS = 1_000L
    }
}
