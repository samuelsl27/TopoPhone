package es.topophone.core.survey

import es.topophone.core.model.alignment.slope.SlopeKind
import kotlin.math.abs

/** Lo que sale de buscar el punto de talud en un PK y un lado (S051/RF-5 a RF-8). */
sealed interface SlopeCatchResult {
    /**
     * El punto de talud: cabeza si [kind] es desmonte y pie si es terraplén. [distanceM] es la distancia en planta desde el borde,
     * [offsetM] su desplazamiento con signo, [heightM] su cota y [slopeHeightM] la altura de talud.
     */
    data class Found(
        val kind: SlopeKind,
        val distanceM: Double,
        val offsetM: Double,
        val heightM: Double,
        val slopeHeightM: Double,
        val point: AxisPoint,
    ) : SlopeCatchResult

    /** El terreno está a la cota del borde: el punto de talud es el borde, con altura cero (RF-8). */
    data class AtEdge(val heightM: Double, val point: AxisPoint) : SlopeCatchResult

    /** El terreno llega a 100 m del borde sin cortar al talud (RF-7). */
    data object NoIntersection : SlopeCatchResult

    /** No hay terreno en el borde o se acaba antes de cortar al talud (RF-6). */
    data object NoTerrain : SlopeCatchResult
}

/**
 * El punto donde el talud de diseño corta el terreno (S051/RF-5 a RF-8, plan A2 y A3). Con `t` la distancia en planta desde el borde
 * hacia fuera sobre la perpendicular al eje, el talud es `z0 + t/rc` en desmonte y `z0 - t/rf` en terraplén. El tipo lo decide el
 * terreno en el borde. Se avanza `t` de 5 cm en 5 cm hasta 100 m buscando el primer cambio de signo de «terreno menos talud» y se afina
 * por bisección: el terreno es lineal a trozos, así que sale la raíz exacta dentro de la tolerancia de la bisección.
 */
object SlopeCatch {
    /** Hasta dónde se busca el terreno desde el borde (RF-7). */
    const val MAX_DISTANCE_M = 100.0

    private const val STEP_M = 0.05
    private const val TOLERANCE_M = 1e-9
    private const val SAME_HEIGHT_M = 1e-9

    fun of(edge: SlopeEdgeResult.Edge, terrain: TerrainAt?, geometry: AlignmentGeometry): SlopeCatchResult {
        val sign = edge.rule.side.sign
        val z0 = edge.heightM

        fun ground(t: Double): Double? = terrain?.heightAt(edge.chainageM, edge.offsetM + sign * t)?.heightM

        val g0 = ground(0.0) ?: return SlopeCatchResult.NoTerrain
        if (abs(g0 - z0) <= SAME_HEIGHT_M) return SlopeCatchResult.AtEdge(z0, edge.point)
        val kind = if (g0 > z0) SlopeKind.CUT else SlopeKind.FILL
        val ratio = edge.rule.ratioOf(kind)
        val direction = if (kind == SlopeKind.CUT) 1.0 else -1.0

        fun slope(t: Double) = z0 + direction * t / ratio

        var previousT = 0.0
        var previousF = g0 - z0
        var t = STEP_M
        while (t <= MAX_DISTANCE_M + 1e-9) {
            val g = ground(t) ?: return SlopeCatchResult.NoTerrain
            val f = g - slope(t)
            if (f == 0.0 ||
                (f > 0.0) != (previousF > 0.0)
            ) {
                return found(edge, kind, ratio, bisect(previousT, previousF, t, f, ::ground, ::slope), geometry)
            }
            previousT = t
            previousF = f
            t += STEP_M
        }
        return SlopeCatchResult.NoIntersection
    }

    private fun bisect(t0: Double, f0: Double, t1: Double, f1: Double, ground: (Double) -> Double?, slope: (Double) -> Double): Double {
        if (f1 == 0.0) return t1
        var lo = t0
        var hi = t1
        val loPositive = f0 > 0.0
        while (hi - lo > TOLERANCE_M) {
            val mid = (lo + hi) / 2
            val g = ground(mid) ?: return hi
            val f = g - slope(mid)
            if (f == 0.0) return mid
            if ((f > 0.0) == loPositive) lo = mid else hi = mid
        }
        return (lo + hi) / 2
    }

    private fun found(
        edge: SlopeEdgeResult.Edge,
        kind: SlopeKind,
        ratio: Double,
        t: Double,
        geometry: AlignmentGeometry,
    ): SlopeCatchResult {
        val direction = if (kind == SlopeKind.CUT) 1.0 else -1.0
        val offset = edge.offsetM + edge.rule.side.sign * t
        val point = geometry.pointAt(edge.chainageM, offset) ?: return SlopeCatchResult.NoTerrain
        return SlopeCatchResult.Found(kind, t, offset, edge.heightM + direction * t / ratio, t / ratio, point)
    }
}
