package es.topophone.core.survey

import es.topophone.core.model.surface.SurfaceOutline
import es.topophone.core.model.surface.SurfaceRing
import kotlin.math.PI
import kotlin.math.atan2

/**
 * El contorno de una malla de triángulos: sus anillos exteriores y sus huecos (S061/RF-6, plan A2).
 *
 * 1. Cada triángulo se orienta en sentido antihorario y aporta sus tres aristas dirigidas, con el interior a la izquierda.
 * 2. Una arista que solo aparece en un triángulo es de borde; las que comparten dos (o más) son interiores. Las aristas se ordenan en
 *    un `LongArray` en lugar de un mapa: con un millón de triángulos son unos 24 MB y no cien.
 * 3. Las aristas de borde se encadenan en anillos; en un vértice con varias salidas (dos anillos que se tocan) se toma la que gira
 *    más a la izquierda respecto de la llegada, para que cada anillo rodee solo su interior.
 * 4. Un anillo con área positiva (antihorario) es exterior; con área negativa (horario), un hueco.
 */
object TinOutline {

    /** Los vértices que admite la codificación de una arista en un `Long` (dos índices de 30 bits y el sentido). */
    const val MAX_VERTICES = 1 shl 30

    fun rings(easting: DoubleArray, northing: DoubleArray, triangles: IntArray): SurfaceOutline {
        require(easting.size == northing.size) { "Un Norte por cada Este" }
        require(easting.size <= MAX_VERTICES) { "Demasiados vértices para el contorno" }
        val count = triangles.size / 3
        val keys = LongArray(count * 3)
        var k = 0
        for (t in 0 until count) {
            val a = triangles[3 * t]
            var b = triangles[3 * t + 1]
            var c = triangles[3 * t + 2]
            val cross = (easting[b] - easting[a]) * (northing[c] - northing[a]) - (northing[b] - northing[a]) * (easting[c] - easting[a])
            if (cross < 0) {
                val swap = b
                b = c
                c = swap
            }
            keys[k++] = edgeKey(a, b)
            keys[k++] = edgeKey(b, c)
            keys[k++] = edgeKey(c, a)
        }
        keys.sort()

        // Aristas de borde dirigidas: las que no se repiten.
        val from = IntArrayList()
        val to = IntArrayList()
        var i = 0
        while (i < keys.size) {
            val undirected = keys[i] ushr 1
            var j = i + 1
            while (j < keys.size && keys[j] ushr 1 == undirected) j++
            if (j - i == 1) {
                val low = (undirected ushr 30).toInt()
                val high = (undirected and LOW_MASK).toInt()
                if (keys[i] and 1L == 0L) {
                    from.add(low)
                    to.add(high)
                } else {
                    from.add(high)
                    to.add(low)
                }
            }
            i = j
        }
        return SurfaceOutline(chain(easting, northing, from.toArray(), to.toArray()))
    }

    /** Clave de una arista dirigida u → v: los dos índices ordenados y un bit que dice si iba del mayor al menor. */
    private fun edgeKey(u: Int, v: Int): Long {
        val low = minOf(u, v).toLong()
        val high = maxOf(u, v).toLong()
        val reversed = if (u > v) 1L else 0L
        return (((low shl 30) or high) shl 1) or reversed
    }

    private fun chain(easting: DoubleArray, northing: DoubleArray, from: IntArray, to: IntArray): List<SurfaceRing> {
        val n = from.size
        if (n == 0) return emptyList()
        // Las aristas ordenadas por su vértice de salida, para encontrar las salidas de un vértice con una búsqueda binaria.
        val order = (0 until n).sortedWith(compareBy({ from[it] }, { to[it] })).toIntArray()
        val starts = IntArray(n) { from[order[it]] }
        val used = BooleanArray(n)
        val rings = ArrayList<SurfaceRing>()
        for (first in 0 until n) {
            if (used[first]) continue
            used[first] = true
            val ringE = DoubleList()
            val ringN = DoubleList()
            val origin = from[order[first]]
            var previous = origin
            var current = to[order[first]]
            ringE.add(easting[origin])
            ringN.add(northing[origin])
            while (current != origin) {
                ringE.add(easting[current])
                ringN.add(northing[current])
                val next = nextEdge(easting, northing, starts, order, to, used, previous, current) ?: break
                used[next] = true
                previous = current
                current = to[order[next]]
            }
            val ring = ringE.toArray() to ringN.toArray()
            rings += SurfaceRing(signedArea(ring.first, ring.second) > 0, ring.first, ring.second)
        }
        return rings
    }

    /** La salida sin usar de [here] que gira más a la izquierda viniendo de [previous]; null si no queda ninguna (malla rota). */
    private fun nextEdge(
        easting: DoubleArray,
        northing: DoubleArray,
        starts: IntArray,
        order: IntArray,
        to: IntArray,
        used: BooleanArray,
        previous: Int,
        here: Int,
    ): Int? {
        var lo = starts.binarySearch(here).let { if (it < 0) return null else it }
        while (lo > 0 && starts[lo - 1] == here) lo--
        var best: Int? = null
        var bestTurn = Double.NEGATIVE_INFINITY
        val heading = atan2(northing[here] - northing[previous], easting[here] - easting[previous])
        var s = lo
        while (s < starts.size && starts[s] == here) {
            if (!used[s]) {
                val target = to[order[s]]
                var turn = atan2(northing[target] - northing[here], easting[target] - easting[here]) - heading
                while (turn <= -PI) turn += 2 * PI
                while (turn > PI) turn -= 2 * PI
                if (turn > bestTurn) {
                    bestTurn = turn
                    best = s
                }
            }
            s++
        }
        return best
    }

    private fun signedArea(e: DoubleArray, n: DoubleArray): Double {
        // Respecto del primer vértice, para no perder precisión con coordenadas UTM de millones de metros.
        var sum = 0.0
        for (i in 1 until e.size - 1) {
            sum += (e[i] - e[0]) * (n[i + 1] - n[0]) - (e[i + 1] - e[0]) * (n[i] - n[0])
        }
        return sum / 2
    }

    private const val LOW_MASK = (1L shl 30) - 1

    private class IntArrayList {
        private var data = IntArray(256)
        private var size = 0

        fun add(value: Int) {
            if (size == data.size) data = data.copyOf(size * 2)
            data[size++] = value
        }

        fun toArray(): IntArray = data.copyOf(size)
    }

    private class DoubleList {
        private var data = DoubleArray(64)
        private var size = 0

        fun add(value: Double) {
            if (size == data.size) data = data.copyOf(size * 2)
            data[size++] = value
        }

        fun toArray(): DoubleArray = data.copyOf(size)
    }
}
