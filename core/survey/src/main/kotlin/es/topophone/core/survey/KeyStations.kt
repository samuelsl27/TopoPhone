package es.topophone.core.survey

import es.topophone.core.model.alignment.AlignmentElement
import es.topophone.core.model.alignment.VerticalProfile
import kotlin.math.abs

/** De dónde viene un punto singular: del cambio de clase de tramo de la planta o de un vértice de la rasante (S054/RF-4). */
enum class KeyStationReason { PLAN, PROFILE }

/** Un punto singular del eje: su PK y su motivo; si planta y rasante coinciden, los dos (S054/RF-4). */
data class KeyStation(val chainageM: Double, val reasons: Set<KeyStationReason>)

/**
 * Los puntos singulares de un eje (S054/RF-4, D2): el PK donde cambia la clase de tramo (recta, arco, clotoide) y el PK de cada
 * vértice de la rasante. La unión de dos clotoides no cambia la clase de tramo. Dos PK a menos de 1 mm son uno.
 */
object KeyStations {
    /** Dos PK a menos de esto son el mismo punto. */
    const val SAME_M = 1e-3

    fun of(elements: List<AlignmentElement>, profile: VerticalProfile?): List<KeyStation> {
        val found = ArrayList<Pair<Double, KeyStationReason>>()
        for (i in 1 until elements.size) {
            if (elements[i].kind != elements[i - 1].kind) found += elements[i].startChainage to KeyStationReason.PLAN
        }
        profile?.vertices?.forEach { found += it.chainage to KeyStationReason.PROFILE }
        val out = ArrayList<KeyStation>()
        for ((pk, reason) in found.sortedBy { it.first }) {
            val last = out.lastOrNull()
            if (last != null && abs(pk - last.chainageM) < SAME_M) {
                out[out.lastIndex] = last.copy(reasons = last.reasons + reason)
            } else {
                out += KeyStation(pk, setOf(reason))
            }
        }
        return out
    }
}
