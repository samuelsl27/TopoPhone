package es.topophone.core.survey

import es.topophone.core.model.surface.SurfaceTile

/**
 * Une las teselas de una superficie en una sola malla (S061, plan A2): un vértice repetido en varias teselas tiene el mismo `Double`
 * en todas, así que se funde por sus coordenadas exactas, y un triángulo que estaba en varias teselas queda una sola vez. Sirve para
 * calcular el contorno de una superficie importada antes de S061, de la que solo quedan las teselas.
 */
object TileMerger {

    class Merged(val easting: DoubleArray, val northing: DoubleArray, val height: DoubleArray, val triangles: IntArray) {
        val triangleCount: Int get() = triangles.size / 3
    }

    fun merge(tiles: List<SurfaceTile>): Merged {
        val totalVertices = tiles.sumOf { it.vertexCount }
        val index = VertexIndex(totalVertices)
        val keys = LongArray(tiles.sumOf { it.triangleCount })
        var k = 0
        for (tile in tiles) {
            val global = IntArray(tile.vertexCount) { index.idOf(tile.easting[it], tile.northing[it], tile.height[it]) }
            val t = tile.triangles
            for (i in 0 until tile.triangleCount) {
                keys[k++] = packSorted(global[t[3 * i]], global[t[3 * i + 1]], global[t[3 * i + 2]])
            }
        }
        keys.sort()
        val triangles = IntArrayList()
        for (i in keys.indices) {
            if (i > 0 && keys[i] == keys[i - 1]) continue
            triangles.add((keys[i] ushr 42).toInt())
            triangles.add(((keys[i] ushr 21) and MASK).toInt())
            triangles.add((keys[i] and MASK).toInt())
        }
        return Merged(index.easting(), index.northing(), index.height(), triangles.toArray())
    }

    /** Vértices que admite la clave de un triángulo (tres índices de 21 bits). */
    const val MAX_VERTICES = 1 shl 21

    private const val MASK = (1L shl 21) - 1

    private fun packSorted(a: Int, b: Int, c: Int): Long {
        val lo = minOf(a, b, c)
        val hi = maxOf(a, b, c)
        val mid = a + b + c - lo - hi
        return (lo.toLong() shl 42) or (mid.toLong() shl 21) or hi.toLong()
    }

    /** Un índice abierto de (Este, Norte) a identificador de vértice, sin cajas de `Long` ni `Pair`. */
    private class VertexIndex(expected: Int) {
        private var capacity = Integer.highestOneBit(maxOf(16, expected * 2 - 1)) shl 1
        private var keysE = LongArray(capacity)
        private var keysN = LongArray(capacity)
        private var ids = IntArray(capacity) { -1 }
        private val e = DoubleList()
        private val n = DoubleList()
        private val z = DoubleList()

        fun idOf(easting: Double, northing: Double, height: Double): Int {
            val be = easting.toRawBits()
            val bn = northing.toRawBits()
            var slot = slotOf(be, bn)
            while (true) {
                val id = ids[slot]
                if (id < 0) {
                    val newId = e.size
                    require(newId < MAX_VERTICES) { "Demasiados vértices para unir las teselas" }
                    ids[slot] = newId
                    keysE[slot] = be
                    keysN[slot] = bn
                    e.add(easting)
                    n.add(northing)
                    z.add(height)
                    return newId
                }
                if (keysE[slot] == be && keysN[slot] == bn) return id
                slot = (slot + 1) and (capacity - 1)
            }
        }

        private fun slotOf(be: Long, bn: Long): Int {
            val h = (be * -0x61c8864680b583ebL) xor (bn * -0x3d4d51c2d82b14b1L)
            return (h xor (h ushr 29)).toInt() and (capacity - 1)
        }

        fun easting() = e.toArray()

        fun northing() = n.toArray()

        fun height() = z.toArray()
    }

    private class DoubleList {
        private var data = DoubleArray(1024)
        var size = 0
            private set

        fun add(value: Double) {
            if (size == data.size) data = data.copyOf(size * 2)
            data[size++] = value
        }

        fun toArray(): DoubleArray = data.copyOf(size)
    }

    private class IntArrayList {
        private var data = IntArray(1024)
        private var size = 0

        fun add(value: Int) {
            if (size == data.size) data = data.copyOf(size * 2)
            data[size++] = value
        }

        fun toArray(): IntArray = data.copyOf(size)
    }
}
