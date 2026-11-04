package es.topophone.core.model.line

import es.topophone.core.model.codes.PointCode

/** Un vértice de una línea del proyecto: el punto del proyecto que referencia, sin copiarlo (S025/D1). */
data class LineVertex(val pointId: String)

/**
 * Una línea del proyecto (S025): una sucesión ordenada de [vertices] con nombre, código de geometría Línea o
 * Polígono guardado por valor y los atributos de ese código. Un polígono es una línea [closed]: su último vértice
 * se une con el primero sin repetirlo. [active] es la línea que recibe los puntos que se miden con su código.
 */
data class ProjectLine(
    val id: String,
    val projectId: String,
    val name: String,
    val code: PointCode?,
    val closed: Boolean,
    val hidden: Boolean,
    val active: Boolean,
    val vertices: List<LineVertex>,
    val createdAtMs: Long,
    val deletedAtMs: Long? = null,
) {
    /** Los identificadores de los puntos que son vértices, en orden. */
    val pointIds: List<String> get() = vertices.map { it.pointId }

    fun summary(): LineSummary = LineSummary(id, name, code, closed, hidden, active, vertices.size)
}

/** Lo que muestra la lista de objetos de una línea (S025/RF-21). */
data class LineSummary(
    val id: String,
    val name: String,
    val code: PointCode?,
    val closed: Boolean,
    val hidden: Boolean,
    val active: Boolean,
    val vertexCount: Int,
)
