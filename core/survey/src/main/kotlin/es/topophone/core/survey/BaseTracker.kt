package es.topophone.core.survey

import es.topophone.core.geodesy.Ecef
import es.topophone.core.gnss.nmea.BasePosition
import es.topophone.core.gnss.rtcm.MsmCnr
import es.topophone.core.gnss.rtcm.ReferenceStation
import es.topophone.core.gnss.rtcm.RtcmSystem
import es.topophone.core.gnss.signal.Band
import es.topophone.core.gnss.signal.SatelliteId
import es.topophone.core.gnss.signal.SatelliteNumber
import es.topophone.core.gnss.signal.SignalKey
import es.topophone.core.model.point.BaseSource

/**
 * Posición de la base tal como la da su fuente (S015/RF-13): geográficas y geocéntricas, con el identificador de la
 * estación y la altura de antena si vienen de un 1005/1006.
 */
data class BaseFix(
    val latitudeDeg: Double,
    val longitudeDeg: Double,
    val ellipsoidalHeightM: Double,
    val ecef: Ecef,
    val source: BaseSource,
    val stationId: Int?,
    val antennaHeightM: Double?,
    val receivedAtMs: Long,
)

/** El último cambio de base: lo que se ha movido y su número, que no se repite mientras viva la app (S015/RF-17, RF-18). */
data class BaseChange(val distanceM: Double, val count: Int)

/**
 * La base en uso (S015/RF-10 a RF-13, RF-16, RF-17). [serial] sube con la primera base de cada conexión y con cada
 * cambio, y no vuelve a empezar: identifica la base para los avisos que el usuario ha cerrado.
 */
data class BaseState(
    val position: BaseFix?,
    val serial: Int,
    val change: BaseChange?,
    val snr: Map<SignalKey, Double>,
    val snrAvailable: Boolean,
) {
    companion object {
        val NONE = BaseState(null, 0, null, emptyMap(), false)
    }
}

/**
 * Sigue la base con las frases EBP del receptor y los mensajes 1005/1006 y MSM de las correcciones (plan de S015, A6).
 * Las funciones devuelven `true` si cambia [state]. No es seguro entre hilos.
 */
class BaseTracker {
    private var ebp: BaseFix? = null
    private var station: BaseFix? = null
    private val lastBySource = mutableMapOf<BaseSource, Ecef>()
    private var hasBase = false
    private var serial = 0
    private var changes = 0
    private var change: BaseChange? = null
    private val snrBySystem = mutableMapOf<RtcmSystem, Pair<Long, Map<SignalKey, Double>>>()

    var state: BaseState = BaseState.NONE
        private set

    fun onEbp(base: BasePosition, nowMs: Long): Boolean {
        val ecef = Ecef.fromGeodetic(base.latitudeDeg, base.longitudeDeg, base.ellipsoidalHeightM)
        ebp = BaseFix(base.latitudeDeg, base.longitudeDeg, base.ellipsoidalHeightM, ecef, BaseSource.RECEIVER, null, null, nowMs)
        return arrived(BaseSource.RECEIVER, ecef, nowMs)
    }

    fun onReferenceStation(reference: ReferenceStation, nowMs: Long): Boolean {
        val ecef = Ecef(reference.x, reference.y, reference.z)
        val point = ecef.toGeodetic()
        station = BaseFix(
            point.latitudeDeg,
            point.longitudeDeg,
            point.ellipsoidalHeightM,
            ecef,
            BaseSource.CORRECTIONS,
            reference.stationId,
            reference.antennaHeightM,
            nowMs,
        )
        return arrived(BaseSource.CORRECTIONS, ecef, nowMs)
    }

    /** La CNR de la base de un sistema; las bandas que no se conocen y SBAS se ignoran (plan, A4). */
    fun onMsm(msm: MsmCnr, nowMs: Long): Boolean {
        val constellation = SatelliteNumber.constellation(msm.system) ?: return false
        val values = LinkedHashMap<SignalKey, Double>()
        for (cell in msm.cells) {
            val band = Band.fromRtcm(msm.system, cell.signal) ?: continue
            val key = SignalKey(SatelliteId(constellation, cell.satellite), band)
            values[key] = maxOf(values[key] ?: cell.cnrDbHz, cell.cnrDbHz)
        }
        if (values.isEmpty()) return false
        snrBySystem[msm.system] = nowMs to values
        return publish(nowMs)
    }

    /** Aplica las caducidades: 60 s para la posición (RF-16) y 10 s para la SNR de la base (RF-10). */
    fun tick(nowMs: Long): Boolean {
        var expired = false
        if (ebp?.let { nowMs - it.receivedAtMs > BASE_TIMEOUT_MS } == true) {
            ebp = null
            expired = true
        }
        if (station?.let { nowMs - it.receivedAtMs > BASE_TIMEOUT_MS } == true) {
            station = null
            expired = true
        }
        if (snrBySystem.values.removeAll { nowMs - it.first > SNR_TIMEOUT_MS }) expired = true
        return expired && publish(nowMs)
    }

    /**
     * Conexión nueva con el receptor: se olvidan las bases, también la última de cada fuente (RF-17). Los números de base y
     * de cambio siguen: un aviso cerrado en otra conexión no tapa los de esta.
     */
    fun reset() {
        ebp = null
        station = null
        lastBySource.clear()
        hasBase = false
        change = null
        snrBySystem.clear()
        state = BaseState.NONE
    }

    private fun arrived(source: BaseSource, ecef: Ecef, nowMs: Long): Boolean {
        val previous = lastBySource[source]
        lastBySource[source] = ecef
        val selected = selected()?.source
        when {
            !hasBase -> {
                hasBase = true
                serial++
            }
            // Solo cuenta el cambio de la fuente que se usa: el de la otra llegaría dos veces (plan, A6).
            previous != null && source == selected && previous.distanceTo(ecef) > CHANGE_THRESHOLD_M -> {
                serial++
                changes++
                change = BaseChange(previous.distanceTo(ecef), changes)
            }
        }
        return publish(nowMs)
    }

    private fun selected(): BaseFix? = ebp ?: station

    private fun publish(nowMs: Long): Boolean {
        val snr = LinkedHashMap<SignalKey, Double>()
        snrBySystem.values.forEach { (atMs, values) -> if (nowMs - atMs <= SNR_TIMEOUT_MS) snr.putAll(values) }
        val next = BaseState(selected(), serial, change, snr, snr.isNotEmpty())
        if (next == state) return false
        state = next
        return true
    }

    companion object {
        const val BASE_TIMEOUT_MS = 60_000L
        const val SNR_TIMEOUT_MS = 10_000L
        const val CHANGE_THRESHOLD_M = 0.1
    }
}
