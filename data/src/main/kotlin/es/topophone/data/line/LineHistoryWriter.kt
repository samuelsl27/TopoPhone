package es.topophone.data.line

import es.topophone.core.model.line.LineChange

/**
 * Escribe en el historial de una línea su estado anterior a un cambio, con todos sus vértices (también los de puntos en la papelera) y
 * atributos (S025/RF-18, RF-25, plan D6). Debe llamarse dentro de la transacción del cambio.
 */
internal class LineHistoryWriter(private val dao: LineDao, private val clock: () -> Long, private val newId: () -> String) {
    suspend fun record(entity: ProjectLineEntity, change: LineChange) {
        val historyId = newId()
        dao.insertHistory(
            LineHistoryEntity(
                historyId, entity.id, clock(), change.name, entity.name, entity.closed,
                entity.code, entity.codeDescription, entity.codeColor, entity.codeSymbol,
            ),
        )
        dao.insertHistoryAttributes(
            dao.attributesOfNow(entity.id).map { LineHistoryAttributeEntity(historyId, it.ordinal, it.name, it.type, it.value) },
        )
        val vertices = dao.allVerticesOf(entity.id)
        val names = vertices.map { it.pointId }.distinct().chunked(CHUNK).flatMap { dao.pointNames(it) }.associate { it.id to it.name }
        dao.insertHistoryVertices(
            vertices.map { LineHistoryVertexEntity(historyId, it.ordinal, it.pointId, names[it.pointId] ?: it.pointId) },
        )
    }

    /** Anota `VERTEX_REMOVED` en cada línea que usa alguno de [pointIds], antes de borrarlos del todo (RF-25). */
    suspend fun recordPointRemoval(pointIds: List<String>) {
        pointIds.distinct().chunked(CHUNK).flatMap { dao.linesWithPoints(it) }.distinctBy { it.id }.forEach {
            record(it, LineChange.VERTEX_REMOVED)
        }
    }

    private companion object {
        const val CHUNK = 500
    }
}
