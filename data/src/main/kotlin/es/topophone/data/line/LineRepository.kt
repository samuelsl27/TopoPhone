package es.topophone.data.line

import androidx.room.withTransaction
import es.topophone.core.model.codes.CodeGeometry
import es.topophone.core.model.line.LineAction
import es.topophone.core.model.line.LineHistoryEntry
import es.topophone.core.model.line.LineNaming
import es.topophone.core.model.line.LineRules
import es.topophone.core.model.line.LineSummary
import es.topophone.core.model.line.LineTarget
import es.topophone.core.model.line.LineVertex
import es.topophone.core.model.line.ProjectLine
import es.topophone.core.model.point.Point
import es.topophone.data.db.TopoPhoneDatabase
import es.topophone.data.point.PointRepository
import es.topophone.data.point.PointSaveResult
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map

/** Qué pasó con las líneas al guardar un punto medido (S025/RF-2, RF-3, RF-7, RF-8). */
sealed interface MeasuredResult {
    /** El punto no se guardó (p. ej. el nombre ya existe); las líneas no cambian. */
    data class PointNotSaved(val result: PointSaveResult) : MeasuredResult

    /** El punto se guardó y no es vértice de ninguna línea. */
    data object PointOnly : MeasuredResult

    /** El punto se guardó como vértice de la línea [lineId], que tiene ahora [vertexCount]; [created] si la línea es nueva. */
    data class Added(val lineId: String, val name: String, val vertexCount: Int, val created: Boolean) : MeasuredResult
}

/** Cómo acabó una operación sobre una línea que ya existe. */
enum class LineActionResult { DONE, NOT_FOUND, NOT_OPEN, NOTHING_TO_UNDO }

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

    /**
     * Guarda el punto medido y, en la misma transacción, lo añade a la línea activa o crea una línea nueva con él según
     * [LineTarget.decide] (RF-2, RF-3, RF-7, RF-8, RF-8b). [geometry] es la geometría del código con que se midió.
     * Si algo falla no queda ni el punto ni el vértice.
     */
    suspend fun saveMeasured(point: Point, geometry: CodeGeometry?): MeasuredResult

    /** Deja de tener línea activa; si el código es de polígono y tiene al menos 3 vértices, la cierra (RF-4). */
    suspend fun finish(id: String): LineActionResult

    /** Deja como línea activa una línea abierta del proyecto (RF-5). */
    suspend fun continueLine(id: String): LineActionResult

    /** Quita el último vértice de la línea sin borrar el punto (RF-6). */
    suspend fun undoVertex(id: String): LineActionResult
}

class RoomLineRepository(
    private val database: TopoPhoneDatabase,
    private val points: PointRepository,
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

    override suspend fun saveMeasured(point: Point, geometry: CodeGeometry?): MeasuredResult = database.withTransaction {
        val saved = points.create(point)
        if (saved != PointSaveResult.Saved) return@withTransaction MeasuredResult.PointNotSaved(saved)
        val activeEntity = dao.activeNow(point.projectId)
        val active = activeEntity?.toLine(dao.allVerticesOf(activeEntity.id), dao.attributesOfNow(activeEntity.id))
        when (val action = LineTarget.decide(active, point.code, geometry, point.id)) {
            LineAction.Nothing -> MeasuredResult.PointOnly
            is LineAction.Append -> {
                val ordinal = (dao.allVerticesOf(action.lineId).lastOrNull()?.ordinal ?: -1) + 1
                dao.insertVertices(listOf(LineVertexEntity(action.lineId, ordinal, point.id)))
                MeasuredResult.Added(action.lineId, active!!.name, active.vertices.size + 1, created = false)
            }
            is LineAction.Create -> {
                val code = point.code!!
                val line = ProjectLine(
                    id = newId(),
                    projectId = point.projectId,
                    name = LineNaming.next(code.code, dao.names(point.projectId)),
                    code = code,
                    geometry = action.geometry,
                    closed = false,
                    hidden = false,
                    active = true,
                    vertices = listOf(LineVertex(point.id)),
                    createdAtMs = clock(),
                )
                dao.clearActive(point.projectId)
                dao.insert(line.toEntity())
                dao.insertVertices(line.vertexEntities())
                dao.insertAttributes(line.attributeEntities())
                MeasuredResult.Added(line.id, line.name, 1, created = true)
            }
        }
    }

    override suspend fun finish(id: String): LineActionResult = database.withTransaction {
        val entity = dao.byId(id)?.takeIf { it.deletedAt == null } ?: return@withTransaction LineActionResult.NOT_FOUND
        val count = dao.visibleVerticesOfNow(id).size
        val geometry = CodeGeometry.entries.firstOrNull { it.name == entity.geometry } ?: CodeGeometry.LINE
        dao.update(entity.copy(active = false, closed = LineRules.closedOnFinish(geometry, count, entity.closed)))
        LineActionResult.DONE
    }

    override suspend fun continueLine(id: String): LineActionResult = database.withTransaction {
        val entity = dao.byId(id)?.takeIf { it.deletedAt == null } ?: return@withTransaction LineActionResult.NOT_FOUND
        if (entity.closed) return@withTransaction LineActionResult.NOT_OPEN
        dao.clearActive(entity.projectId)
        dao.update(entity.copy(active = true))
        LineActionResult.DONE
    }

    override suspend fun undoVertex(id: String): LineActionResult = database.withTransaction {
        dao.byId(id)?.takeIf { it.deletedAt == null } ?: return@withTransaction LineActionResult.NOT_FOUND
        val last = dao.visibleVerticesOfNow(id).lastOrNull() ?: return@withTransaction LineActionResult.NOTHING_TO_UNDO
        dao.deleteVertex(id, last.ordinal)
        LineActionResult.DONE
    }
}
