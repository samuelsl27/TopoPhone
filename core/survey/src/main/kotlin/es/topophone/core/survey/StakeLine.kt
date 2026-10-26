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

    val hasHeight: Boolean get() = height != null

    fun easting(vertex: Int): Double = east[vertex]

    fun northing(vertex: Int): Double = north[vertex]

    /** La cota del vértice, o null si la línea no tiene cota. */
    fun height(vertex: Int): Double? = height?.get(vertex)

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

    /** La cota interpolada linealmente entre los vértices del tramo en el PK dado, o null sin cota (RF-23). */
    fun heightAt(chainageM: Double): Double? {
        val h = height ?: return null
        val k = segmentAt(chainageM)
        val a = h[segmentStart(k)]
        val b = h[segmentEnd(k)]
        return a + (b - a) * ((chainageM - chainage[k]) / (chainage[k + 1] - chainage[k]))
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
        ): StakeLine? {
            require(easting.size == northing.size && (height == null || height.size == easting.size))
            require(original == null || original.size == easting.size)
            val keep = ArrayList<Int>(easting.size)
            val marks = if (original == null) null else ArrayList<Boolean>(easting.size)
            for (i in easting.indices) {
                val last = keep.lastOrNull()
                if (last != null && easting[last] == easting[i] && northing[last] == northing[i]) {
                    if (marks != null && original!![i]) marks[marks.lastIndex] = true
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
            val h = height?.let { source -> DoubleArray(keep.size) { source[keep[it]] } }
            val segments = if (isClosed) keep.size else keep.size - 1
            val chainage = DoubleArray(segments + 1)
            for (k in 0 until segments) {
                val b = if (k + 1 < keep.size) k + 1 else 0
                chainage[k + 1] = chainage[k] + hypot(e[b] - e[k], n[b] - n[k])
            }
            return StakeLine(e, n, h, isClosed, marks?.toBooleanArray(), chainage)
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
