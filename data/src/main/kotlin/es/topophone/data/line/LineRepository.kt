package es.topophone.data.line

import android.database.sqlite.SQLiteConstraintException
import androidx.room.withTransaction
import es.topophone.core.model.codes.AttributeCarryOver
import es.topophone.core.model.codes.AttributeType
import es.topophone.core.model.codes.CodeGeometry
import es.topophone.core.model.codes.PointAttribute
import es.topophone.core.model.codes.PointCode
import es.topophone.core.model.codes.SurveyCode
import es.topophone.core.model.line.LineAction
import es.topophone.core.model.line.LineChange
import es.topophone.core.model.line.LineHistoryEntry
import es.topophone.core.model.line.LineIssue
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
import es.topophone.data.project.ProjectRepository
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
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

/** Cómo acabó guardar, cerrar o crear una línea (S025/RF-11, RF-15, RF-19). */
sealed interface LineSaveResult {
    data class Saved(val id: String) : LineSaveResult

    /** Ya hay una línea del proyecto con ese nombre. */
    data object DuplicateName : LineSaveResult

    /** Menos de 2 vértices (3 en un polígono). */
    data object TooFewVertices : LineSaveResult

    /** Dos vértices iguales seguidos. */
    data object RepeatedVertex : LineSaveResult

    /** La línea o alguno de sus puntos no existe. */
    data object NotFound : LineSaveResult

    /** El código no es de geometría Línea ni Polígono. */
    data object WrongGeometry : LineSaveResult
}

/**
 * Lo que se edita de una línea (S025/RF-16, RF-18): el [name], los vértices ([pointIds], en el orden nuevo) y los valores de los
 * atributos de su código ([attributes]). Lo que es null no cambia.
 */
data class LineUpdate(val name: String? = null, val pointIds: List<String>? = null, val attributes: List<PointAttribute>? = null)

/** Una línea en la papelera con los días que le quedan antes de borrarse del todo (S025/RF-24). */
data class TrashedLine(val line: ProjectLine, val daysLeft: Int)

/**
 * El punto de un vértice con su nombre, sus coordenadas planas, la altura del proyecto y su posición geodésica, para la ficha de la
 * línea y su información geométrica (S025/RF-16, S026/RF-3, RF-5).
 */
data class VertexPoint(
    val pointId: String,
    val name: String,
    val easting: Double?,
    val northing: Double?,
    val height: Double? = null,
    val latitudeDeg: Double? = null,
    val longitudeDeg: Double? = null,
    val ellipsoidalHeightM: Double? = null,
)

/** Una línea nueva a partir de puntos que ya existen (S025/RF-12 a RF-15). [name] vacío usa el nombre propuesto. */
data class NewLine(
    val projectId: String,
    val name: String?,
    val code: PointCode?,
    val geometry: CodeGeometry,
    val closed: Boolean,
    val pointIds: List<String>,
)

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

    /** Los puntos de los vértices de la línea en orden, solo con lo que enseña la ficha, sin cargar los puntos enteros (RF-16). */
    fun vertexPoints(id: String): Flow<List<VertexPoint>>

    /**
     * Guarda el punto medido y, en la misma transacción, lo añade a la línea activa o crea una línea nueva con él según
     * [LineTarget.decide] (RF-2, RF-3, RF-7, RF-8, RF-8b). [geometry] es la geometría del código con que se midió.
     * Si algo falla no queda ni el punto ni el vértice. [save] guarda el punto dentro de esa transacción (por defecto, `create`); el
     * recolector lo usa para guardar a la vez las fotos de la medida.
     */
    suspend fun saveMeasured(point: Point, geometry: CodeGeometry?, save: (suspend (Point) -> PointSaveResult)? = null): MeasuredResult

    /** Deja de tener línea activa; si el código es de polígono y tiene al menos 3 vértices, la cierra (RF-4). */
    suspend fun finish(id: String): LineActionResult

    /** Deja como línea activa una línea abierta del proyecto (RF-5). */
    suspend fun continueLine(id: String): LineActionResult

    /** Quita el último vértice de la línea sin borrar el punto (RF-6). */
    suspend fun undoVertex(id: String): LineActionResult

    /** Oculta o muestra la línea en el mapa; sus puntos no cambian (RF-23). */
    suspend fun setHidden(id: String, hidden: Boolean)

    /** Mueve la línea a la papelera del proyecto sin tocar sus puntos; deja de ser la activa (RF-24). */
    suspend fun moveToTrash(id: String)

    /** Las líneas de la papelera del proyecto, la más reciente primero (RF-24). */
    fun trash(projectId: String): Flow<List<TrashedLine>>

    /** Restaura una línea con todos sus datos y su historial; con [newName] si el suyo está ocupado (RF-24). */
    suspend fun restore(id: String, newName: String? = null): LineSaveResult

    /** Borra del todo una línea de la papelera, con su historial (RF-24). */
    suspend fun deletePermanently(id: String)

    /** Borra las líneas que llevan 30 días en la papelera; devuelve cuántas (RF-24). */
    suspend fun purgeTrash(): Int

    /** Los nombres de las líneas del proyecto (no en la papelera) que tienen alguno de [pointIds] como vértice (RF-25). */
    suspend fun linesUsing(pointIds: List<String>): List<String>

    /** El nombre que se propone para una línea nueva con el código [code] (RF-2, RF-14). */
    suspend fun suggestName(projectId: String, code: String?): String

    /** Cierra una línea abierta en polígono; con menos de 3 vértices no (RF-10, RF-11). El cambio queda en el historial. */
    suspend fun close(id: String): LineSaveResult

    /** Vuelve a abrir un polígono, con su sentido y sus vértices intactos (RF-10). El cambio queda en el historial. */
    suspend fun open(id: String): LineSaveResult

    /**
     * Guarda una edición de nombre, vértices o valores de atributos con el estado anterior en el historial (RF-16 a RF-19). Un vértice
     * cuyo punto está en la papelera no se conserva al reescribir los vértices. Con menos vértices de los necesarios no se guarda.
     */
    suspend fun edit(id: String, update: LineUpdate): LineSaveResult

    /** Cambia el código de la línea (solo Línea o Polígono) con S024/RF-14 y RF-15 para los atributos, y lo anota (RF-20). */
    suspend fun changeCode(id: String, code: SurveyCode): LineSaveResult

    /** Crea una línea con los puntos de [line], sin modificarlos (RF-14, RF-15). No queda como línea activa. */
    suspend fun createFromPoints(line: NewLine): LineSaveResult
}

class RoomLineRepository(
    private val database: TopoPhoneDatabase,
    private val points: PointRepository,
    private val clock: () -> Long = System::currentTimeMillis,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) : LineRepository {
    private val dao = database.lines()
    private val history = LineHistoryWriter(dao, clock, newId)

    /**
     * Una lectura coherente: cada vez que cambia alguna de las [tables] se vuelve a leer todo dentro de una transacción, así nunca se ve una
     * línea con los vértices o el historial de antes del cambio.
     */
    private fun <T> snapshot(vararg tables: String, read: suspend () -> T): Flow<T> =
        database.invalidationTracker.createFlow(*tables).map { database.withTransaction { read() } }.distinctUntilChanged()

    override fun lines(projectId: String): Flow<List<ProjectLine>> =
        snapshot("project_lines", "line_vertices", "line_attributes", "points") {
            val verticesByLine = dao.visibleVerticesNow(projectId).groupBy { it.lineId }
            val attributesByLine = dao.attributesOfProjectNow(projectId).groupBy { it.lineId }
            dao.linesNow(projectId).map { it.toLine(verticesByLine[it.id].orEmpty(), attributesByLine[it.id].orEmpty()) }
        }

    override fun summaries(projectId: String): Flow<List<LineSummary>> =
        snapshot("project_lines", "line_vertices", "line_attributes", "points") {
            val attributesByLine = dao.attributesOfProjectNow(projectId).groupBy { it.lineId }
            dao.summariesNow(projectId).map { row ->
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

    override fun line(id: String): Flow<ProjectLine?> = snapshot("project_lines", "line_vertices", "line_attributes", "points") {
        dao.byId(id)?.toLine(dao.visibleVerticesOfNow(id), dao.attributesOfNow(id))
    }

    override fun active(projectId: String): Flow<ProjectLine?> = snapshot("project_lines", "line_vertices", "line_attributes", "points") {
        dao.activeNow(projectId)?.let { it.toLine(dao.visibleVerticesOfNow(it.id), dao.attributesOfNow(it.id)) }
    }

    override fun vertexPoints(id: String): Flow<List<VertexPoint>> = snapshot("line_vertices", "points") {
        dao.vertexPointsNow(id).map {
            VertexPoint(it.pointId, it.name, it.easting, it.northing, it.height, it.latitudeDeg, it.longitudeDeg, it.ellipsoidalHeightM)
        }
    }

    override fun history(id: String): Flow<List<LineHistoryEntry>> =
        snapshot("line_history", "line_history_attributes", "line_history_vertices") {
            val attributesByEntry = dao.historyAttributesNow(id).groupBy { it.historyId }
            val verticesByEntry = dao.historyVerticesNow(id).groupBy { it.historyId }
            dao.historyNow(id).map { it.toEntry(attributesByEntry[it.id].orEmpty(), verticesByEntry[it.id].orEmpty()) }
        }

    override suspend fun saveMeasured(point: Point, geometry: CodeGeometry?, save: (suspend (Point) -> PointSaveResult)?): MeasuredResult =
        database.withTransaction {
            val saved = if (save != null) save(point) else points.create(point)
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

    override suspend fun setHidden(id: String, hidden: Boolean) = dao.setHidden(id, hidden)

    override suspend fun moveToTrash(id: String) {
        dao.moveToTrash(id, clock())
    }

    override fun trash(projectId: String): Flow<List<TrashedLine>> =
        snapshot("project_lines", "line_vertices", "line_attributes", "points") {
            val verticesByLine = dao.trashVerticesNow(projectId).groupBy { it.lineId }
            val attributesByLine = dao.trashAttributesNow(projectId).groupBy { it.lineId }
            dao.trashNow(projectId).map { entity ->
                val daysIn = ((clock() - (entity.deletedAt ?: clock())) / DAY_MS).toInt()
                TrashedLine(
                    entity.toLine(verticesByLine[entity.id].orEmpty(), attributesByLine[entity.id].orEmpty()),
                    (ProjectRepository.TRASH_DAYS - daysIn).coerceAtLeast(0),
                )
            }
        }

    override suspend fun restore(id: String, newName: String?): LineSaveResult {
        val current = dao.byId(id)?.takeIf { it.deletedAt != null } ?: return LineSaveResult.NotFound
        return try {
            dao.restore(id, newName ?: current.name)
            LineSaveResult.Saved(id)
        } catch (_: SQLiteConstraintException) {
            LineSaveResult.DuplicateName
        }
    }

    override suspend fun deletePermanently(id: String) = dao.deleteFromTrash(id)

    override suspend fun purgeTrash(): Int = dao.purge(limit = clock() - ProjectRepository.TRASH_DAYS * DAY_MS)

    override suspend fun linesUsing(pointIds: List<String>): List<String> =
        pointIds.distinct().chunked(SQL_CHUNK).flatMap { dao.activeLineNamesUsing(it) }.distinct().sorted()

    override suspend fun suggestName(projectId: String, code: String?): String = LineNaming.next(code, dao.names(projectId))

    override suspend fun close(id: String): LineSaveResult = setClosed(id, closed = true)

    override suspend fun open(id: String): LineSaveResult = setClosed(id, closed = false)

    private suspend fun setClosed(id: String, closed: Boolean): LineSaveResult = database.withTransaction {
        val entity = dao.byId(id)?.takeIf { it.deletedAt == null } ?: return@withTransaction LineSaveResult.NotFound
        if (entity.closed == closed) return@withTransaction LineSaveResult.Saved(id)
        val visible = dao.visibleVerticesOfNow(id)
        if (closed) {
            LineRules.validate(visible.map { it.pointId }, closed = true)?.let { return@withTransaction it.toResult() }
        }
        history.record(entity, LineChange.CLOSED)
        dao.update(entity.copy(closed = closed))
        LineSaveResult.Saved(id)
    }

    override suspend fun createFromPoints(line: NewLine): LineSaveResult = try {
        database.withTransaction {
            LineRules.validate(line.pointIds, line.closed)?.let { return@withTransaction it.toResult() }
            val live = line.pointIds.distinct().chunked(SQL_CHUNK).flatMap { dao.livePointIds(line.projectId, it) }.toSet()
            if (line.pointIds.any { it !in live }) return@withTransaction LineSaveResult.NotFound
            val created = ProjectLine(
                id = newId(),
                projectId = line.projectId,
                name = line.name?.takeIf { it.isNotBlank() } ?: LineNaming.next(line.code?.code, dao.names(line.projectId)),
                code = line.code,
                geometry = line.geometry,
                closed = line.closed,
                hidden = false,
                active = false,
                vertices = line.pointIds.map { LineVertex(it) },
                createdAtMs = clock(),
            )
            dao.insert(created.toEntity())
            dao.insertVertices(created.vertexEntities())
            dao.insertAttributes(created.attributeEntities())
            LineSaveResult.Saved(created.id)
        }
    } catch (_: SQLiteConstraintException) {
        LineSaveResult.DuplicateName
    }

    override suspend fun edit(id: String, update: LineUpdate): LineSaveResult = try {
        database.withTransaction {
            val entity = dao.byId(id)?.takeIf { it.deletedAt == null } ?: return@withTransaction LineSaveResult.NotFound
            val newIds = update.pointIds
            if (newIds != null) {
                LineRules.validate(newIds, entity.closed)?.let { return@withTransaction it.toResult() }
                val live = newIds.distinct().chunked(SQL_CHUNK).flatMap { dao.livePointIds(entity.projectId, it) }.toSet()
                if (newIds.any { it !in live }) return@withTransaction LineSaveResult.NotFound
            }
            val change = when {
                newIds != null -> LineChange.VERTICES
                update.name != null -> LineChange.NAME
                else -> LineChange.ATTRIBUTES
            }
            history.record(entity, change)
            update.name?.let { dao.update(entity.copy(name = it)) }
            if (newIds != null) {
                dao.deleteVertices(id)
                dao.insertVertices(newIds.mapIndexed { i, p -> LineVertexEntity(id, i, p) })
            }
            update.attributes?.let { values ->
                dao.deleteAttributes(id)
                dao.insertAttributes(values.mapIndexed { i, a -> LineAttributeEntity(id, i, a.name, a.type.name, a.value) })
            }
            LineSaveResult.Saved(id)
        }
    } catch (_: SQLiteConstraintException) {
        LineSaveResult.DuplicateName
    }

    override suspend fun changeCode(id: String, code: SurveyCode): LineSaveResult = database.withTransaction {
        if (!LineTarget.isLineGeometry(code.geometry)) return@withTransaction LineSaveResult.WrongGeometry
        val entity = dao.byId(id)?.takeIf { it.deletedAt == null } ?: return@withTransaction LineSaveResult.NotFound
        val old = dao.attributesOfNow(id).map { PointAttribute(it.name, enumValueOf<AttributeType>(it.type), it.value) }
        history.record(entity, LineChange.CODE)
        val values = AttributeCarryOver.apply(old, code.attributes).values
        dao.update(
            entity.copy(
                code = code.code,
                codeDescription = code.description,
                codeColor = code.color,
                codeSymbol = code.symbol.name,
                geometry = code.geometry.name,
            ),
        )
        dao.deleteAttributes(id)
        dao.insertAttributes(values.mapIndexed { i, a -> LineAttributeEntity(id, i, a.name, a.type.name, a.value) })
        LineSaveResult.Saved(id)
    }

    private fun LineIssue.toResult(): LineSaveResult = when (this) {
        LineIssue.TOO_FEW_VERTICES -> LineSaveResult.TooFewVertices
        LineIssue.REPEATED_VERTEX -> LineSaveResult.RepeatedVertex
    }

    private companion object {
        /** Menos que el límite de variables de una consulta de SQLite. */
        const val SQL_CHUNK = 500
        const val DAY_MS = 24L * 60 * 60 * 1000
    }
}
