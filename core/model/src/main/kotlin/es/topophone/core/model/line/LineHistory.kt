package es.topophone.core.model.line

import es.topophone.core.model.codes.PointCode
import es.topophone.core.model.point.AttributeChange
import es.topophone.core.model.point.PointSearch

/** Qué se guardó en una versión del historial de una línea (S025/RF-10, RF-18, RF-25). */
enum class LineChange { NAME, CODE, ATTRIBUTES, VERTICES, CLOSED, VERTEX_REMOVED }

/** Un vértice tal como estaba: el punto y su nombre entonces, porque el punto puede haber desaparecido (S025/D6). */
data class HistoryVertex(val pointId: String, val pointName: String)

/** El estado de una línea en una versión del historial. */
data class LineSnapshot(val name: String, val code: PointCode?, val closed: Boolean, val vertices: List<HistoryVertex>) {
    companion object {
        /** El estado de [line] con los nombres de sus puntos en [pointNames] (el id si falta). */
        fun of(line: ProjectLine, pointNames: Map<String, String>): LineSnapshot = LineSnapshot(
            line.name,
            line.code,
            line.closed,
            line.vertices.map { HistoryVertex(it.pointId, pointNames[it.pointId] ?: it.pointId) },
        )
    }
}

/** Una versión anterior de una línea con la fecha del cambio (S025/RF-18). */
data class LineHistoryEntry(val changedAtMs: Long, val change: LineChange, val previous: LineSnapshot)

/** Una cosa que cambió en una versión de la línea. */
sealed interface LineHistoryLine {
    data class Name(val from: String, val to: String) : LineHistoryLine

    /** El código; null es sin código. */
    data class Code(val from: String?, val to: String?) : LineHistoryLine

    data class Attributes(val changes: List<AttributeChange>) : LineHistoryLine

    /** Los vértices cambiaron: de [fromCount] a [toCount], con los nombres de los puntos quitados y añadidos. */
    data class Vertices(val fromCount: Int, val toCount: Int, val removed: List<String>, val added: List<String>) : LineHistoryLine

    data class Closed(val from: Boolean, val to: Boolean) : LineHistoryLine
}

/** Un cambio del historial de una línea con su fecha y lo que cambió. */
data class LineHistoryItem(val changedAtMs: Long, val change: LineChange, val lines: List<LineHistoryLine>)

/** El historial de una línea en palabras: cada versión anterior frente a la que vino después (S025/RF-18). */
object LineHistory {
    /** [entries] van del más reciente al más antiguo; el más reciente se compara con [current]. */
    fun items(entries: List<LineHistoryEntry>, current: LineSnapshot): List<LineHistoryItem> {
        var after = current
        return entries.map { entry ->
            val before = entry.previous
            val lines = buildList {
                if (before.name != after.name) add(LineHistoryLine.Name(before.name, after.name))
                if (before.code?.code != after.code?.code) {
                    add(LineHistoryLine.Code(before.code?.code, after.code?.code))
                } else {
                    attributeChanges(before.code, after.code).takeIf { it.isNotEmpty() }?.let { add(LineHistoryLine.Attributes(it)) }
                }
                if (before.closed != after.closed) add(LineHistoryLine.Closed(before.closed, after.closed))
                val beforeIds = before.vertices.map { it.pointId }
                val afterIds = after.vertices.map { it.pointId }
                if (beforeIds != afterIds) {
                    add(
                        LineHistoryLine.Vertices(
                            before.vertices.size,
                            after.vertices.size,
                            removed = before.vertices.filter { it.pointId !in afterIds }.map { it.pointName },
                            added = after.vertices.filter { it.pointId !in beforeIds }.map { it.pointName },
                        ),
                    )
                }
            }
            after = before
            LineHistoryItem(entry.changedAtMs, entry.change, lines)
        }
    }

    private fun attributeChanges(before: PointCode?, after: PointCode?): List<AttributeChange> {
        val old = before?.attributes.orEmpty().associateBy { it.name to it.type }
        return after?.attributes.orEmpty().mapNotNull { new ->
            val previous = old[new.name to new.type]?.value.orEmpty()
            if (previous != new.value) AttributeChange(new.name, previous, new.value) else null
        }
    }
}

/** Búsqueda de líneas por nombre o código, sin distinguir mayúsculas ni acentos (S025/RF-21, S011/RF-2). */
object LineSearch {
    fun matches(summary: LineSummary, query: String): Boolean {
        val needle = PointSearch.normalize(query.trim())
        return needle.isEmpty() ||
            needle in PointSearch.normalize(summary.name) ||
            (summary.code != null && needle in PointSearch.normalize(summary.code.code))
    }
}
