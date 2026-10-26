package es.topophone.core.survey

import es.topophone.core.model.surface.SurfaceExtent
import es.topophone.core.model.surface.SurfaceTile
import es.topophone.core.model.surface.SurfaceTileCodec
import kotlin.math.abs
import kotlin.math.hypot

/** Una tesela con el recuadro de su celda: la zona del plano de la que da la cota (S042, plan A3). */
class TinCell(val region: SurfaceExtent, val tile: SurfaceTile)

/**
 * Reparte los triángulos de una superficie en teselas con un árbol cuaternario sobre su extensión (S042, plan A3): una hoja guarda
 * a lo sumo [MAX_TRIANGLES] triángulos y su bloque no pasa de [SurfaceTileCodec.MAX_TILE_BYTES]. Un triángulo va a todas las hojas
 * que su recuadro toca, así que cada hoja es autosuficiente: la cota de una posición sale de las teselas cuya celda la contiene, sin
 * mirar ninguna otra. Las celdas se tocan en sus bordes y pueden repetirse (una zona muy densa se reparte en varias teselas con la
 * misma celda); una posición en un borde la contienen varias, y cualquiera de ellas da la misma cota.
 */
object TinTiler {
    const val MAX_TRIANGLES = 4_000
    private const val MAX_DEPTH = 14

    /**
     * Las teselas de los triángulos [triangles] (tres posiciones de vértice cada uno) de los vértices dados. Sin triángulos, ninguna.
     * Los vértices que ningún triángulo cita no entran en ninguna tesela.
     */
    fun tile(
        easting: DoubleArray,
        northing: DoubleArray,
        height: DoubleArray,
        triangles: IntArray,
        maxTriangles: Int = MAX_TRIANGLES,
        maxTileBytes: Int = SurfaceTileCodec.MAX_TILE_BYTES,
    ): List<TinCell> {
        val count = triangles.size / 3
        if (count == 0) return emptyList()
        var minE = Double.POSITIVE_INFINITY
        var maxE = Double.NEGATIVE_INFINITY
        var minN = Double.POSITIVE_INFINITY
        var maxN = Double.NEGATIVE_INFINITY
        for (v in triangles) {
            minE = minOf(minE, easting[v])
            maxE = maxOf(maxE, easting[v])
            minN = minOf(minN, northing[v])
            maxN = maxOf(maxN, northing[v])
        }
        val builder = Builder(easting, northing, height, triangles, maxTriangles, maxTileBytes)
        builder.split(SurfaceExtent(minE, maxE, minN, maxN), IntArray(count) { it }, 0)
        return builder.cells
    }

    private class Builder(
        val easting: DoubleArray,
        val northing: DoubleArray,
        val height: DoubleArray,
        val triangles: IntArray,
        val maxTriangles: Int,
        val maxTileBytes: Int,
    ) {
        val cells = ArrayList<TinCell>()
        private val local = IntArray(easting.size) { -1 }

        fun split(region: SurfaceExtent, ids: IntArray, depth: Int) {
            if (ids.size <= maxTriangles) {
                val tile = build(ids)
                if (SurfaceTileCodec.encodedSize(tile) <= maxTileBytes || depth >= MAX_DEPTH) {
                    emit(region, ids, tile)
                    return
                }
            } else if (depth >= MAX_DEPTH) {
                emit(region, ids, null)
                return
            }
            val midE = (region.minEasting + region.maxEasting) / 2
            val midN = (region.minNorthing + region.maxNorthing) / 2
            val children = listOf(
                SurfaceExtent(region.minEasting, midE, region.minNorthing, midN),
                SurfaceExtent(midE, region.maxEasting, region.minNorthing, midN),
                SurfaceExtent(region.minEasting, midE, midN, region.maxNorthing),
                SurfaceExtent(midE, region.maxEasting, midN, region.maxNorthing),
            )
            val parts = children.map { child -> ids.filter { touches(it, child) }.toIntArray() }
            if (parts.all { it.size == ids.size }) {
                emit(region, ids, null)
                return
            }
            for (i in children.indices) if (parts[i].isNotEmpty()) split(children[i], parts[i], depth + 1)
        }

        /** Cierra la hoja: una tesela, o varias con la misma celda si no hay manera de partirla más. */
        private fun emit(region: SurfaceExtent, ids: IntArray, built: SurfaceTile?) {
            if (built != null && SurfaceTileCodec.encodedSize(built) <= maxTileBytes) {
                cells += TinCell(region, built)
                return
            }
            var from = 0
            var piece = minOf(ids.size, maxTriangles)
            while (from < ids.size) {
                val part = ids.copyOfRange(from, minOf(ids.size, from + piece))
                val tile = build(part)
                if (SurfaceTileCodec.encodedSize(tile) > maxTileBytes && piece > 1) {
                    piece = maxOf(1, piece / 2)
                    continue
                }
                cells += TinCell(region, tile)
                from += part.size
            }
        }

        private fun touches(triangle: Int, region: SurfaceExtent): Boolean {
            val a = triangles[3 * triangle]
            val b = triangles[3 * triangle + 1]
            val c = triangles[3 * triangle + 2]
            return maxOf(easting[a], easting[b], easting[c]) >= region.minEasting &&
                minOf(easting[a], easting[b], easting[c]) <= region.maxEasting &&
                maxOf(northing[a], northing[b], northing[c]) >= region.minNorthing &&
                minOf(northing[a], northing[b], northing[c]) <= region.maxNorthing
        }

        /** La tesela de los triángulos [ids] con sus propios vértices, en el orden en que aparecen. */
        private fun build(ids: IntArray): SurfaceTile {
            val used = ArrayList<Int>()
            val indices = IntArray(ids.size * 3)
            for ((k, triangle) in ids.withIndex()) {
                for (corner in 0 until 3) {
                    val global = triangles[3 * triangle + corner]
                    if (local[global] < 0) {
                        local[global] = used.size
                        used += global
                    }
                    indices[3 * k + corner] = local[global]
                }
            }
            val tile = SurfaceTile(
                DoubleArray(used.size) { easting[used[it]] },
                DoubleArray(used.size) { northing[used[it]] },
                DoubleArray(used.size) { height[used[it]] },
                indices,
            )
            for (global in used) local[global] = -1
            return tile
        }
    }
}

/** El directorio de las teselas de una superficie: de una posición a las teselas cuya celda la contiene (S042, plan A4). */
class TinDirectory(private val regions: List<SurfaceExtent>) {
    /** Las posiciones, en la lista dada, de las teselas cuya celda contiene la posición (borde incluido). */
    fun candidates(easting: Double, northing: Double): IntArray {
        var out = IntArray(2)
        var n = 0
        for (i in regions.indices) {
            if (regions[i].contains(easting, northing)) {
                if (n == out.size) out = out.copyOf(n * 2)
                out[n++] = i
            }
        }
        return out.copyOf(n)
    }

    /** Las teselas que hay que tener a mano para ir hacia donde se mueve la punta: las de las celdas vecinas a [radiusM] de la posición. */
    fun near(easting: Double, northing: Double, radiusM: Double): IntArray {
        val box = SurfaceExtent(easting - radiusM, easting + radiusM, northing - radiusM, northing + radiusM)
        val out = ArrayList<Int>()
        for (i in regions.indices) {
            val r = regions[i]
            if (r.maxEasting >= box.minEasting &&
                r.minEasting <= box.maxEasting &&
                r.maxNorthing >= box.minNorthing &&
                r.minNorthing <= box.maxNorthing
            ) {
                out += i
            }
        }
        return out.toIntArray()
    }
}

/** La cota de una tesela en una posición, por interpolación lineal en el triángulo que la contiene (S042/RF-22). */
object TinElevation {
    /**
     * Un punto a menos de esta distancia del borde de un triángulo cuenta como dentro: una arista o un vértice compartido no caen en un
     * hueco ni un vértice del contorno queda fuera por unas micras de redondeo al convertir. 1 mm, como las tolerancias del proyecto.
     */
    private const val EDGE_TOLERANCE_M = 1e-3

    /** La cota de la superficie en la posición, o null si ningún triángulo de la tesela la contiene. */
    fun at(tile: SurfaceTile, easting: Double, northing: Double): Double? {
        val t = tile.triangles
        var i = 0
        while (i < t.size) {
            val z = inTriangle(tile, t[i], t[i + 1], t[i + 2], easting, northing)
            if (z != null) return z
            i += 3
        }
        return null
    }

    private fun inTriangle(tile: SurfaceTile, a: Int, b: Int, c: Int, e: Double, n: Double): Double? {
        val ea = tile.easting[a]
        val na = tile.northing[a]
        val eb = tile.easting[b]
        val nb = tile.northing[b]
        val ec = tile.easting[c]
        val nc = tile.northing[c]
        if (e < minOf(ea, eb, ec) - EDGE_BOX || e > maxOf(ea, eb, ec) + EDGE_BOX) return null
        if (n < minOf(na, nb, nc) - EDGE_BOX || n > maxOf(na, nb, nc) + EDGE_BOX) return null
        val det = (nb - nc) * (ea - ec) + (ec - eb) * (na - nc)
        if (det == 0.0) return null
        val w1 = ((nb - nc) * (e - ec) + (ec - eb) * (n - nc)) / det
        val w2 = ((nc - na) * (e - ec) + (ea - ec) * (n - nc)) / det
        val w3 = 1.0 - w1 - w2
        val area2 = abs(det)
        if (w1 < 0 && -w1 * area2 > EDGE_TOLERANCE_M * hypot(eb - ec, nb - nc)) return null
        if (w2 < 0 && -w2 * area2 > EDGE_TOLERANCE_M * hypot(ec - ea, nc - na)) return null
        if (w3 < 0 && -w3 * area2 > EDGE_TOLERANCE_M * hypot(ea - eb, na - nb)) return null
        return w1 * tile.height[a] + w2 * tile.height[b] + w3 * tile.height[c]
    }

    /** El margen del recuadro del triángulo, para no descartar de antemano un punto del borde. */
    private const val EDGE_BOX = EDGE_TOLERANCE_M
}
