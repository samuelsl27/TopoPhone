package es.topophone.core.survey

import es.topophone.core.model.cad.CadEntity
import es.topophone.core.model.cad.CadEntityRef
import kotlin.math.hypot

/** Un elemento del dibujo candidato a un toque, con la referencia que lo identifica (S040/RF-29). */
class DesignCandidate(val ref: CadEntityRef, val entity: CadEntity)

/** El elemento tocado y a qué distancia, en las unidades de las coordenadas (S040/RF-29). */
class DesignHit(val ref: CadEntityRef, val entity: CadEntity, val distance: Double)

/**
 * El elemento del dibujo más cercano a un toque (S040/RF-29): el de la menor distancia a un vértice o a un segmento, en el plano de
 * las coordenadas del proyecto, siempre que quede dentro de la tolerancia. Un elemento de un solo vértice cuenta como un punto. En un
 * empate gana el primero de la lista.
 */
object DesignHitTest {
    /**
     * El más cercano a (x, y) de [candidates] a no más de [tolerance]; null si ninguno. Las coordenadas y la tolerancia van en las
     * mismas unidades (metros en un proyecto UTM).
     */
    fun nearest(x: Double, y: Double, tolerance: Double, candidates: Iterable<DesignCandidate>): DesignHit? {
        var best: DesignCandidate? = null
        var bestDistance = Double.MAX_VALUE
        for (candidate in candidates) {
            val d = distance(x, y, candidate.entity, minOf(tolerance, bestDistance)) ?: continue
            if (d <= tolerance && d < bestDistance) {
                best = candidate
                bestDistance = d
            }
        }
        return best?.let { DesignHit(it.ref, it.entity, bestDistance) }
    }

    /** La distancia del punto al elemento, o null si el recuadro del elemento ya queda a más de [limit]. */
    private fun distance(x: Double, y: Double, e: CadEntity, limit: Double): Double? {
        val n = e.vertexCount
        var minE = Double.MAX_VALUE
        var maxE = -Double.MAX_VALUE
        var minN = Double.MAX_VALUE
        var maxN = -Double.MAX_VALUE
        for (i in 0 until n) {
            val ve = e.easting[i]
            val vn = e.northing[i]
            if (ve < minE) minE = ve
            if (ve > maxE) maxE = ve
            if (vn < minN) minN = vn
            if (vn > maxN) maxN = vn
        }
        if (x < minE - limit || x > maxE + limit || y < minN - limit || y > maxN + limit) return null
        if (n == 1) return hypot(x - e.easting[0], y - e.northing[0])
        var best = Double.MAX_VALUE
        for (i in 0 until n - 1) {
            val d = distanceToSegment(x, y, e.easting[i], e.northing[i], e.easting[i + 1], e.northing[i + 1])
            if (d < best) best = d
        }
        return best
    }

    private fun distanceToSegment(px: Double, py: Double, ax: Double, ay: Double, bx: Double, by: Double): Double {
        val dx = bx - ax
        val dy = by - ay
        val length2 = dx * dx + dy * dy
        val t = if (length2 == 0.0) 0.0 else (((px - ax) * dx + (py - ay) * dy) / length2).coerceIn(0.0, 1.0)
        return hypot(px - (ax + t * dx), py - (ay + t * dy))
    }
}
