package es.topophone.core.survey

import es.topophone.core.model.alignment.slope.SlopeKind
import kotlin.math.abs

/**
 * La punta del jalón frente al talud de diseño de un borde (S051/RF-11 a RF-13).
 *
 * @property kind el tipo con que se compara: el conocido por el terreno o, sin él, el que dice la cota de la punta.
 * @property slopeHeightM la cota del talud de diseño en el desplazamiento de la punta.
 * @property dHeightM la cota de la punta menos la del talud en su desplazamiento (positiva si está por encima).
 * @property moveM lo que hay que moverse sobre la perpendicular al eje para que las dos cotas coincidan; positivo hacia fuera.
 * @property dChainageM el PK de la punta menos el PK de diseño: el error en PK (RF-12).
 * @property onLine la punta está en la línea de la sección dentro de la tolerancia horizontal (RF-12).
 * @property found punto de talud encontrado: la diferencia de cota y el error en PK caben en las tolerancias (RF-13).
 */
data class SlopeLiveValue(
    val kind: SlopeKind,
    val slopeHeightM: Double,
    val dHeightM: Double,
    val moveM: Double,
    val dChainageM: Double,
    val onLine: Boolean,
    val found: Boolean,
)

/** El punto donde se clava la estaca, a su distancia fuera del punto de talud sobre la misma sección (S051/RF-14). */
data class SlopeStake(val offsetM: Double, val point: AxisPoint, val distanceToCatchM: Double, val slopeHeightM: Double)

/** Las cuentas en vivo de un replanteo de talud (S051, plan A4). */
object SlopeLive {
    /**
     * Compara la punta (Este, Norte y cota) con el talud del borde [edge], que es el del PK de diseño. [kind] es el tipo conocido; null lo
     * decide la cota de la punta respecto a la del borde. null si la punta no se proyecta sobre el eje (RF-8 de S054).
     */
    fun of(
        edge: SlopeEdgeResult.Edge,
        kind: SlopeKind?,
        tipEasting: Double,
        tipNorthing: Double,
        tipHeightM: Double,
        geometry: AlignmentGeometry,
        toleranceHM: Double,
        toleranceVM: Double,
    ): SlopeLiveValue? {
        val location = geometry.locate(tipEasting, tipNorthing) ?: return null
        val type = kind ?: if (tipHeightM >= edge.heightM) SlopeKind.CUT else SlopeKind.FILL
        val ratio = edge.rule.ratioOf(type)
        val direction = if (type == SlopeKind.CUT) 1.0 else -1.0
        val t = edge.rule.side.sign * (location.offset - edge.offsetM)
        val slope = edge.heightM + direction * t / ratio
        val dz = tipHeightM - slope
        val dChainage = location.chainage - edge.chainageM
        val onLine = abs(dChainage) <= toleranceHM
        return SlopeLiveValue(type, slope, dz, direction * ratio * dz, dChainage, onLine, onLine && abs(dz) <= toleranceVM)
    }

    /** El punto de estaca a [stakeOffsetM] metros hacia fuera del punto de talud [found], sobre la misma sección (RF-14). */
    fun stake(edge: SlopeEdgeResult.Edge, found: SlopeCatchResult.Found, stakeOffsetM: Double, geometry: AlignmentGeometry): SlopeStake? {
        val offset = found.offsetM + edge.rule.side.sign * stakeOffsetM
        val point = geometry.pointAt(edge.chainageM, offset) ?: return null
        return SlopeStake(offset, point, stakeOffsetM, found.slopeHeightM)
    }
}
