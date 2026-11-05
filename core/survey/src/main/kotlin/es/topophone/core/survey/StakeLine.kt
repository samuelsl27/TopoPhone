package es.topophone.core.survey

import es.topophone.core.model.cad.CadEntity
import es.topophone.core.model.cad.CadEntityKind
import kotlin.math.hypot

/** Un punto en el plano del sistema del proyecto, en metros de cuadrícula. */
data class PlanePoint(val easting: Double, val northing: Double)

/**
 * Una línea lista para consultar en el replanteo (S041/RF-15, RF-23, RF-33): sus vértices en el plano del SC del proyecto, el PK
 * acumulado de cada uno (distancia de cuadrícula desde el primer vértice), su cota si la tiene y qué vértices están escritos en el
 * DXF (RF-33). Una línea cerrada no repite el primer vértice al final: su último tramo, de vuelta al primero, está implícito y su
 * longitud ([lengthM]) es el perímetro.
 *
 * No sabe de dibujos ni de pantallas: la construyen [of] desde un elemento del dibujo o, en S027, [create] desde una línea del
 * proyecto.
 */
class StakeLine private constructor(
    private val east: DoubleArray,
    private val north: DoubleArray,
    private val height: DoubleArray?,
    val closed: Boolean,
    private val original: BooleanArray?,
    private val chainage: DoubleArray,
) {
    val vertexCount: Int get() = east.size

    /** Los tramos: uno menos que vértices, o tantos como vértices en una cerrada. */
    val segmentCount: Int get() = if (closed) east.size else east.size - 1

    /** La longitud de una línea abierta o el perímetro de una cerrada, en metros de cuadrícula. */
    val lengthM: Double get() = chainage[segmentCount]

    /** Algún vértice tiene altura (S027/RF-8). Una línea de un dibujo la tiene en todos o en ninguno. */
    val hasHeight: Boolean get() = heightVertices.isNotEmpty()

    /** Todos los vértices tienen altura: la cota se interpola en cualquier tramo, como en S041. */
    val hasCompleteHeight: Boolean get() = height != null && heightVertices.size == east.size

    /** Los vértices con altura, en orden, para interpolar entre ellos saltando los que no la tienen (S027/RF-8). */
    private val heightVertices: IntArray = height?.indices?.filter { !height[it].isNaN() }?.toIntArray() ?: IntArray(0)

    /** La línea sabe qué vértices son originales y cuáles de la aproximación de una curva (S040/RF-34). */
    val hasOriginalMarks: Boolean get() = original != null

    fun easting(vertex: Int): Double = east[vertex]

    fun northing(vertex: Int): Double = north[vertex]

    /** La cota del vértice, o null si no la tiene (la línea no tiene cota, o falta en este vértice, S027/RF-8). */
    fun height(vertex: Int): Double? = height?.get(vertex)?.takeUnless { it.isNaN() }

    /** El PK del vértice [vertex]. */
    fun chainageOf(vertex: Int): Double = chainage[vertex]

    /** El vértice está escrito en el DXF y no es de la aproximación de una curva (RF-33). */
    fun isOriginal(vertex: Int): Boolean = original?.get(vertex) ?: true

    /** El vértice donde empieza el tramo [segment]. */
    fun segmentStart(segment: Int): Int = segment

    /** El vértice donde acaba el tramo [segment]: el primero, en el tramo de cierre de una línea cerrada. */
    fun segmentEnd(segment: Int): Int = if (segment + 1 < east.size) segment + 1 else 0

    fun segmentLengthM(segment: Int): Double = chainage[segment + 1] - chainage[segment]

    /**
     * El tramo que contiene el PK: el que empieza en un vértice, si el PK cae justo en él, salvo el final de la línea, que usa el
     * último. Un PK fuera de la línea usa el primero o el último.
     */
    fun segmentAt(chainageM: Double): Int {
        var low = 0
        var high = segmentCount - 1
        while (low < high) {
            val mid = (low + high + 1) ushr 1
            if (chainage[mid] <= chainageM) low = mid else high = mid - 1
        }
        return low
    }

    /** El punto del PK dado, desplazado [offsetM] hacia la derecha del sentido del tramo (RF-11, RF-18). */
    fun pointAt(chainageM: Double, offsetM: Double = 0.0): PlanePoint {
        val k = segmentAt(chainageM)
        val a = segmentStart(k)
        val b = segmentEnd(k)
        val dx = east[b] - east[a]
        val dy = north[b] - north[a]
        val length = chainage[k + 1] - chainage[k]
        val t = (chainageM - chainage[k]) / length
        return PlanePoint(east[a] + t * dx + offsetM * dy / length, north[a] + t * dy - offsetM * dx / length)
    }

    /**
     * La cota interpolada linealmente entre los vértices del tramo en el PK dado, o null sin cota (RF-23). Con altura en todos los
     * vértices es la de siempre. Si faltan algunas (S027/RF-8, RF-9) se interpola en el PK entre el vértice con altura anterior y el
     * siguiente, saltando los que no la tienen; antes del primero y después del último con altura no hay cota, salvo en un polígono,
     * donde el tramo de cierre también interpola y el PK da la vuelta al perímetro.
     */
    fun heightAt(chainageM: Double): Double? {
        val h = height ?: return null
        if (heightVertices.size == east.size) {
            val k = segmentAt(chainageM)
            val a = h[segmentStart(k)]
            val b = h[segmentEnd(k)]
            return a + (b - a) * ((chainageM - chainage[k]) / (chainage[k + 1] - chainage[k]))
        }
        return partialHeightAt(h, chainageM)
    }

    private fun partialHeightAt(h: DoubleArray, chainageM: Double): Double? {
        if (heightVertices.isEmpty()) return null
        val length = chainage[segmentCount]
        val pk = if (closed) ((chainageM % length) + length) % length else chainageM
        // El último vértice con altura de PK <= pk y, después de él, el primero de PK > pk.
        var low = -1
        var lo = 0
        var hi = heightVertices.size - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (chainage[heightVertices[mid]] <= pk) {
                low = mid
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        if (low >= 0 && chainage[heightVertices[low]] == pk) return h[heightVertices[low]]
        val high = low + 1
        var before: Double? = null
        var beforePk = 0.0
        var after: Double? = null
        var afterPk = 0.0
        if (low >= 0) {
            before = h[heightVertices[low]]
            beforePk = chainage[heightVertices[low]]
        } else if (closed) {
            before = h[heightVertices.last()]
            beforePk = chainage[heightVertices.last()] - length
        }
        if (high < heightVertices.size) {
            after = h[heightVertices[high]]
            afterPk = chainage[heightVertices[high]]
        } else if (closed) {
            after = h[heightVertices.first()]
            afterPk = chainage[heightVertices.first()] + length
        }
        if (before == null || after == null) return null
        return before + (after - before) * ((pk - beforePk) / (afterPk - beforePk))
    }

    companion object {
        /**
         * Una línea desde sus vértices. Quita los vértices repetidos seguidos (conservan la marca de original de cualquiera de los
         * dos) y, si el último vértice repite el primero, la trata como cerrada y quita el repetido. Null si le quedan menos de dos
         * vértices.
         */
        fun create(
            easting: DoubleArray,
            northing: DoubleArray,
            height: DoubleArray?,
            closed: Boolean = false,
            original: BooleanArray? = null,
        ): StakeLine? = createWithSource(easting, northing, height, closed, original)?.line

        /** Una línea y, de cada uno de sus vértices, el índice del vértice de la entrada del que sale (S027/RF-5). */
        class Built(val line: StakeLine, val sourceIndex: IntArray)

        /**
         * Lo mismo que [create], diciendo de qué vértice de la entrada sale cada vértice que queda (se quitan los repetidos seguidos
         * y el último si repite el primero). Una altura que falta (`NaN`) en un vértice se completa con la del repetido que se quita.
         */
        fun createWithSource(
            easting: DoubleArray,
            northing: DoubleArray,
            height: DoubleArray?,
            closed: Boolean = false,
            original: BooleanArray? = null,
        ): Built? {
            require(easting.size == northing.size && (height == null || height.size == easting.size))
            require(original == null || original.size == easting.size)
            val heights = height?.copyOf()
            val keep = ArrayList<Int>(easting.size)
            val marks = if (original == null) null else ArrayList<Boolean>(easting.size)
            for (i in easting.indices) {
                val last = keep.lastOrNull()
                if (last != null && easting[last] == easting[i] && northing[last] == northing[i]) {
                    if (marks != null && original!![i]) marks[marks.lastIndex] = true
                    if (heights != null && heights[last].isNaN() && !heights[i].isNaN()) heights[last] = heights[i]
                    continue
                }
                keep += i
                marks?.add(original!![i])
            }
            var isClosed = closed
            if (keep.size > 2 && easting[keep.first()] == easting[keep.last()] && northing[keep.first()] == northing[keep.last()]) {
                isClosed = true
                keep.removeAt(keep.lastIndex)
                if (marks != null) {
                    if (marks.last()) marks[0] = true
                    marks.removeAt(marks.lastIndex)
                }
            }
            if (keep.size < 2) return null
            val e = DoubleArray(keep.size) { easting[keep[it]] }
            val n = DoubleArray(keep.size) { northing[keep[it]] }
            val h = heights?.let { source -> DoubleArray(keep.size) { source[keep[it]] } }
            val segments = if (isClosed) keep.size else keep.size - 1
            val chainage = DoubleArray(segments + 1)
            for (k in 0 until segments) {
                val b = if (k + 1 < keep.size) k + 1 else 0
                chainage[k + 1] = chainage[k] + hypot(e[b] - e[k], n[b] - n[k])
            }
            return Built(StakeLine(e, n, h, isClosed, marks?.toBooleanArray(), chainage), keep.toIntArray())
        }

        /**
         * La línea de un elemento del dibujo (RF-2). Null si es un punto o un centro de bloque, o si se queda sin dos vértices
         * distintos. [withHeight] es `hasElevations` del dibujo: sin él la línea no tiene cota aunque el elemento traiga Z (RF-24).
         */
        fun of(entity: CadEntity, withHeight: Boolean = true): StakeLine? {
            if (entity.kind == CadEntityKind.POINT || entity.kind == CadEntityKind.BLOCK_INSERT) return null
            val marks = if (entity.hasOriginalMarks) BooleanArray(entity.vertexCount) { entity.isOriginal(it) } else null
            return create(entity.easting, entity.northing, if (withHeight) entity.height else null, closed = false, original = marks)
        }
    }
}
