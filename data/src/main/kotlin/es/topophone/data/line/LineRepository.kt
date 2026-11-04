package es.topophone.data.line

import es.topophone.core.model.line.LineHistoryEntry
import es.topophone.core.model.line.LineSummary
import es.topophone.core.model.line.ProjectLine
import es.topophone.data.db.TopoPhoneDatabase
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map

/**
 * Las líneas del proyecto (S025). Cada alta, edición, cierre, borrado o restauración es una transacción única con durabilidad completa
 * (RNF-T6). Una línea nunca escribe en los puntos que referencia (RF-17).
 */
interface LineRepository {
    /** Las líneas del proyecto que no están en la papelera, de la más antigua a la más reciente, con sus vértices visibles. */
    fun lines(projectId: String): Flow<List<ProjectLine>>

    /** Lo que muestra la lista de objetos de cada línea (RF-21). */
    fun summaries(projectId: String): Flow<List<LineSummary>>

    /** Una línea con todos sus datos; null si no existe. */
    fun line(id: String): Flow<ProjectLine?>

    /** La línea activa del proyecto, si la hay (RF-3, RF-9). */
    fun active(projectId: String): Flow<ProjectLine?>

    /** El historial de la línea, del cambio más reciente al más antiguo (RF-18). */
    fun history(id: String): Flow<List<LineHistoryEntry>>
}

class RoomLineRepository(
    private val database: TopoPhoneDatabase,
    private val clock: () -> Long = System::currentTimeMillis,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) : LineRepository {
    private val dao = database.lines()

    override fun lines(projectId: String): Flow<List<ProjectLine>> =
        combine(dao.lines(projectId), dao.visibleVertices(projectId), dao.attributesOfProject(projectId)) { lines, vertices, attributes ->
            val verticesByLine = vertices.groupBy { it.lineId }
            val attributesByLine = attributes.groupBy { it.lineId }
            lines.map { it.toLine(verticesByLine[it.id].orEmpty(), attributesByLine[it.id].orEmpty()) }
        }

    override fun summaries(projectId: String): Flow<List<LineSummary>> = combine(
        dao.summaries(projectId),
        dao.attributesOfProject(projectId),
    ) { rows, attributes ->
        val attributesByLine = attributes.groupBy { it.lineId }
        rows.map { row ->
            val line = row.line
            LineSummary(
                id = line.id,
                name = line.name,
                code = lineCode(line.code, line.codeDescription, line.codeColor, line.codeSymbol, attributesByLine[line.id].orEmpty()),
                closed = line.closed,
                hidden = line.hidden,
                active = line.active,
                vertexCount = row.vertexCount,
            )
        }
    }

    override fun line(id: String): Flow<ProjectLine?> = combine(dao.observe(id), dao.visibleVerticesOf(id), dao.attributesOf(id)) {
            entity,
            vertices,
            attributes,
        ->
        entity?.toLine(vertices, attributes)
    }

    override fun active(projectId: String): Flow<ProjectLine?> = dao.active(projectId).let { active ->
        combine(active, dao.visibleVertices(projectId), dao.attributesOfProject(projectId)) { entity, vertices, attributes ->
            entity?.toLine(vertices.filter { it.lineId == entity.id }, attributes.filter { it.lineId == entity.id })
        }
    }

    override fun history(id: String): Flow<List<LineHistoryEntry>> =
        combine(dao.history(id), dao.historyAttributes(id), dao.historyVertices(id)) { entries, attributes, vertices ->
            val attributesByEntry = attributes.groupBy { it.historyId }
            val verticesByEntry = vertices.groupBy { it.historyId }
            entries.map { it.toEntry(attributesByEntry[it.id].orEmpty(), verticesByEntry[it.id].orEmpty()) }
        }.map { it }

    internal val clockForWrites: () -> Long get() = clock
    internal val idForWrites: () -> String get() = newId
}
