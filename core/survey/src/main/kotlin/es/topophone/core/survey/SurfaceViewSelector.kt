package es.topophone.core.survey

import es.topophone.core.model.surface.ElevationRamp
import es.topophone.core.model.surface.SurfaceExtent
import es.topophone.core.model.surface.SurfaceTile

/** Una tesela del índice de una superficie: su recuadro y sus triángulos, sin leer su bloque (S042, plan A3). */
data class TileBox(val extent: SurfaceExtent, val triangleCount: Int)

/**
 * Los triángulos de la malla que se dibujan (S061/RF-7, RF-10): tres vértices por triángulo en Este y Norte del sistema del
 * proyecto, y el color de cada triángulo por su cota media.
 */
class MeshTriangles(val easting: DoubleArray, val northing: DoubleArray, val argb: IntArray) {
    init {
        require(easting.size == northing.size && easting.size == argb.size * 3) { "Tres vértices por triángulo" }
    }

    val count: Int get() = argb.size
}

/** Qué se dibuja de una superficie TIN visible (S061/RF-6, RF-7). */
sealed interface TinView {
    /** Demasiados triángulos en la vista: solo el contorno. */
    data object OutlineOnly : TinView

    /** La malla de la vista, con el contorno encima. */
    class Mesh(val triangles: MeshTriangles) : TinView
}

/**
 * Decide qué se dibuja de una superficie según lo que corta la vista del mapa (S061, plan A3).
 *
 * 1. [tilesToRead] suma los triángulos de las teselas del índice que cortan la vista ampliada un 10 %: si pasan de
 *    [MAX_INDEX_TRIANGLES] se dibuja solo el contorno sin leer ningún bloque.
 * 2. [tin] quita los triángulos repetidos entre teselas, cuenta los que cortan la vista sin ampliar y, con más de
 *    [MAX_MESH_TRIANGLES], vuelve al contorno; si no, entrega los que cortan la vista ampliada con el color de su cota media.
 */
object SurfaceViewSelector {
    /** Triángulos en la vista a partir de los cuales se dibuja solo el contorno (S061/RF-6, RF-7). */
    const val MAX_MESH_TRIANGLES = 20_000

    /** La suma del índice a partir de la cual no se leen bloques. */
    const val MAX_INDEX_TRIANGLES = 120_000

    /** Lo que se amplía la vista por cada lado para cargar, en fracción de su ancho y su alto. */
    const val VIEW_MARGIN = 0.1

    fun enlarge(view: SurfaceExtent, margin: Double = VIEW_MARGIN): SurfaceExtent {
        val de = (view.maxEasting - view.minEasting) * margin
        val dn = (view.maxNorthing - view.minNorthing) * margin
        return SurfaceExtent(view.minEasting - de, view.maxEasting + de, view.minNorthing - dn, view.maxNorthing + dn)
    }

    fun intersects(a: SurfaceExtent, b: SurfaceExtent): Boolean =
        a.minEasting <= b.maxEasting && b.minEasting <= a.maxEasting && a.minNorthing <= b.maxNorthing && b.minNorthing <= a.maxNorthing

    /** Las posiciones en [index] de las teselas que hay que leer para la [view]; null si solo se dibuja el contorno (paso 1). */
    fun tilesToRead(index: List<TileBox>, view: SurfaceExtent): List<Int>? {
        val wide = enlarge(view)
        val hits = index.indices.filter { intersects(index[it].extent, wide) }
        val sum = hits.sumOf { index[it].triangleCount.toLong() }
        return if (sum > MAX_INDEX_TRIANGLES) null else hits
    }

    /** Lo que se dibuja de las [tiles] leídas para la [view], con los colores de [ramp] (paso 2). */
    fun tin(tiles: List<SurfaceTile>, view: SurfaceExtent, ramp: ElevationRamp): TinView {
        val wide = enlarge(view)
        val seen = HashSet<TriangleKey>()
        val e = DoubleList()
        val n = DoubleList()
        val colors = IntList()
        var inView = 0
        for (tile in tiles) {
            val t = tile.triangles
            for (k in 0 until tile.triangleCount) {
                val a = t[3 * k]
                val b = t[3 * k + 1]
                val c = t[3 * k + 2]
                val box = SurfaceExtent(
                    minOf(tile.easting[a], tile.easting[b], tile.easting[c]),
                    maxOf(tile.easting[a], tile.easting[b], tile.easting[c]),
                    minOf(tile.northing[a], tile.northing[b], tile.northing[c]),
                    maxOf(tile.northing[a], tile.northing[b], tile.northing[c]),
                )
                if (!intersects(box, wide)) continue
                if (!seen.add(TriangleKey.of(tile, a, b, c))) continue
                if (intersects(box, view) && ++inView > MAX_MESH_TRIANGLES) return TinView.OutlineOnly
                for (v in intArrayOf(a, b, c)) {
                    e.add(tile.easting[v])
                    n.add(tile.northing[v])
                }
                colors.add(ramp.argb((tile.height[a] + tile.height[b] + tile.height[c]) / 3))
            }
        }
        return TinView.Mesh(MeshTriangles(e.toArray(), n.toArray(), colors.toArray()))
    }

    /** Un triángulo por sus tres vértices en orden, para reconocerlo en varias teselas (un vértice tiene el mismo `Double` en todas). */
    private data class TriangleKey(val e0: Long, val n0: Long, val e1: Long, val n1: Long, val e2: Long, val n2: Long) {
        companion object {
            fun of(tile: SurfaceTile, a: Int, b: Int, c: Int): TriangleKey {
                val v = listOf(a, b, c)
                    .map { tile.easting[it].toRawBits() to tile.northing[it].toRawBits() }
                    .sortedWith(compareBy({ it.first }, { it.second }))
                return TriangleKey(v[0].first, v[0].second, v[1].first, v[1].second, v[2].first, v[2].second)
            }
        }
    }

    private class DoubleList {
        private var data = DoubleArray(1024)
        private var size = 0

        fun add(value: Double) {
            if (size == data.size) data = data.copyOf(size * 2)
            data[size++] = value
        }

        fun toArray(): DoubleArray = data.copyOf(size)
    }

    private class IntList {
        private var data = IntArray(256)
        private var size = 0

        fun add(value: Int) {
            if (size == data.size) data = data.copyOf(size * 2)
            data[size++] = value
        }

        fun toArray(): IntArray = data.copyOf(size)
    }
}
