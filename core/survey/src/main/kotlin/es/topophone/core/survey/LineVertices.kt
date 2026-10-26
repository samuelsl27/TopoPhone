package es.topophone.core.survey

import es.topophone.core.model.cad.CadEntityKind

/** Un vértice de la lista de "Por segmentos": su número en la lista desde 1, su PK y su punto (S041/RF-8). */
data class SegmentVertex(val vertex: Int, val number: Int, val chainageM: Double, val point: PlanePoint, val heightM: Double?)

/**
 * Los vértices que ofrece "Por segmentos" de una línea (S041/RF-6 a RF-9, RF-33, RF-34): solo los originales del DXF. De un arco o
 * una spline, sus dos extremos; de un círculo, su punto de partida (RF-9).
 *
 * Una curva de un dibujo importado antes de que S040 marcara los vértices originales no sabe cuáles lo son: se ofrecen todos sus
 * vértices y [needsReimport] avisa de que hay que volver a importar el dibujo para replantear solo sus extremos (RF-34).
 */
class LineVertices private constructor(private val line: StakeLine, val entries: List<SegmentVertex>, val needsReimport: Boolean) {
    val size: Int get() = entries.size

    /** La posición en la lista del siguiente vértice: no pasa del último, salvo en una línea cerrada, que vuelve al primero (RF-7). */
    fun next(index: Int): Int = when {
        index < entries.lastIndex -> index + 1
        line.closed -> 0
        else -> index
    }

    /** La posición del vértice anterior: no pasa del primero (RF-7). */
    fun previous(index: Int): Int = if (index > 0) index - 1 else index

    /** La posición en la lista del vértice [vertex] de la línea, o null si no se ofrece. */
    fun indexOfVertex(vertex: Int): Int? = entries.indexOfFirst { it.vertex == vertex }.takeIf { it >= 0 }

    companion object {
        private val CURVES = setOf(CadEntityKind.ARC, CadEntityKind.CIRCLE, CadEntityKind.SPLINE)

        fun of(line: StakeLine, kind: CadEntityKind): LineVertices {
            val curve = kind in CURVES
            val unmarkedCurve = curve && !line.hasOriginalMarks
            val offered = (0 until line.vertexCount).filter { line.isOriginal(it) }
            val entries = offered.mapIndexed { i, v ->
                SegmentVertex(v, i + 1, line.chainageOf(v), PlanePoint(line.easting(v), line.northing(v)), line.height(v))
            }
            return LineVertices(line, entries, needsReimport = unmarkedCurve && entries.size > 2)
        }
    }
}
