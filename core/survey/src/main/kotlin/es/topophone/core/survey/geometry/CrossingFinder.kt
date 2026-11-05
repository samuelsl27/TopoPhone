package es.topophone.core.survey.geometry

import kotlin.math.max
import kotlin.math.min

/**
 * Detecta si algún segmento de una poligonal corta a otro que no es su vecino (S026/RF-6). Barrido por Este: los segmentos se
 * ordenan por su Este mínimo y solo se comparan los que se solapan en Este y en Norte; en un trazado de campo son pocos.
 */
object CrossingFinder {

    fun crosses(east: DoubleArray, north: DoubleArray, closed: Boolean): Boolean {
        val n = east.size
        val count = if (closed) n else n - 1
        if (count < 3) return false
        val minE = DoubleArray(count)
        val maxE = DoubleArray(count)
        val minN = DoubleArray(count)
        val maxN = DoubleArray(count)
        for (i in 0 until count) {
            val j = (i + 1) % n
            minE[i] = min(east[i], east[j])
            maxE[i] = max(east[i], east[j])
            minN[i] = min(north[i], north[j])
            maxN[i] = max(north[i], north[j])
        }
        val order = (0 until count).sortedBy { minE[it] }
        for (k in order.indices) {
            val a = order[k]
            var m = k + 1
            while (m < order.size && minE[order[m]] <= maxE[a]) {
                val b = order[m]
                m++
                if (neighbours(a, b, count, closed)) continue
                if (minN[b] > maxN[a] || minN[a] > maxN[b]) continue
                if (intersect(east, north, a, b, n)) return true
            }
        }
        return false
    }

    private fun neighbours(a: Int, b: Int, count: Int, closed: Boolean): Boolean {
        val d = if (a > b) a - b else b - a
        return d == 1 || (closed && d == count - 1)
    }

    private fun intersect(e: DoubleArray, nn: DoubleArray, a: Int, b: Int, n: Int): Boolean {
        val a1 = a
        val a2 = (a + 1) % n
        val b1 = b
        val b2 = (b + 1) % n
        val d1 = orient(e, nn, b1, b2, a1)
        val d2 = orient(e, nn, b1, b2, a2)
        val d3 = orient(e, nn, a1, a2, b1)
        val d4 = orient(e, nn, a1, a2, b2)
        if (((d1 > 0 && d2 < 0) || (d1 < 0 && d2 > 0)) && ((d3 > 0 && d4 < 0) || (d3 < 0 && d4 > 0))) return true
        return (d1 == 0.0 && onSegment(e, nn, b1, b2, a1)) ||
            (d2 == 0.0 && onSegment(e, nn, b1, b2, a2)) ||
            (d3 == 0.0 && onSegment(e, nn, a1, a2, b1)) ||
            (d4 == 0.0 && onSegment(e, nn, a1, a2, b2))
    }

    /** El signo del giro de p1 a p2 a r (producto vectorial). */
    private fun orient(e: DoubleArray, n: DoubleArray, p1: Int, p2: Int, r: Int): Double =
        (e[p2] - e[p1]) * (n[r] - n[p1]) - (n[p2] - n[p1]) * (e[r] - e[p1])

    private fun onSegment(e: DoubleArray, n: DoubleArray, p1: Int, p2: Int, r: Int): Boolean =
        e[r] >= min(e[p1], e[p2]) && e[r] <= max(e[p1], e[p2]) && n[r] >= min(n[p1], n[p2]) && n[r] <= max(n[p1], n[p2])
}
