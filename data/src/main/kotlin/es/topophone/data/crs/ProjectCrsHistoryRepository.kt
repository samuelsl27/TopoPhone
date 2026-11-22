package es.topophone.data.crs

import androidx.room.withTransaction
import es.topophone.core.model.point.GeodeticPosition
import es.topophone.core.model.project.CrsChangeKind
import es.topophone.core.model.project.HeightSystem
import es.topophone.core.model.project.LibraryCrs
import es.topophone.core.model.project.ProjectCrs
import es.topophone.core.model.project.ProjectCrsChange
import es.topophone.core.model.project.ProjectCrsText
import es.topophone.core.survey.CrsChange
import es.topophone.core.survey.CrsChangePlan
import es.topophone.core.survey.PlanPoint
import es.topophone.core.survey.ProjectPositionCalculator
import es.topophone.data.db.TopoPhoneDatabase
import es.topophone.data.point.PointGroundRow
import es.topophone.data.project.toProject
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Lo que da cambiar el SC de un proyecto o volver al anterior (S035/RF-17 a RF-21). */
sealed interface CrsChangeResult {
    data class Applied(val change: ProjectCrsChange) : CrsChangeResult

    /** El proyecto no existe. */
    data object ProjectNotFound : CrsChangeResult

    /** El SC y el sistema de alturas ya eran esos: no hay cambio que registrar. */
    data object NoChange : CrsChangeResult

    /** No hay un cambio anterior al que volver. */
    data object NothingToUndo : CrsChangeResult

    /** El SC nuevo es de otro datum que el actual (S035/RF-17). */
    data object OtherDatum : CrsChangeResult
}

/**
 * El historial del proyecto y el cambio de su SC (S035/RF-17 a RF-21). Cambiar el SC reescribe, en **una sola transacción** (RNF-T6), el SC del
 * proyecto, lo derivado de cada punto (Este, Norte y altura, también de la medida) y una fila del historial; las geodésicas y la medida no se tocan.
 */
interface ProjectCrsHistoryRepository {
    /** Los cambios del proyecto, del más reciente al más antiguo. */
    fun observe(projectId: String): Flow<List<ProjectCrsChange>>

    /** Qué puntos no se podrían calcular en [newCrs] (S035/RF-19); `null` si el proyecto no existe. */
    suspend fun preview(projectId: String, newCrs: ProjectCrs, newHeightSystem: HeightSystem): CrsChangePlan?

    /** Cambia el SC del proyecto y lo anota en el historial. Con puntos que no se calculan, quedan sin coordenadas hasta que se vuelva a un SC que los calcule. */
    suspend fun apply(projectId: String, newCrs: ProjectCrs, newHeightSystem: HeightSystem): CrsChangeResult
}

class RoomProjectCrsHistoryRepository(
    private val database: TopoPhoneDatabase,
    private val calculator: () -> ProjectPositionCalculator = { ProjectPositionCalculator() },
    private val clock: () -> Long = System::currentTimeMillis,
    private val ids: () -> String = { UUID.randomUUID().toString() },
) : ProjectCrsHistoryRepository {
    private val history = database.crsHistory()
    private val projects = database.projects()
    private val points = database.points()

    override fun observe(projectId: String): Flow<List<ProjectCrsChange>> = history.observe(projectId).map { rows ->
        rows.mapNotNull { it.toModel() }
    }

    override suspend fun preview(projectId: String, newCrs: ProjectCrs, newHeightSystem: HeightSystem): CrsChangePlan? {
        if (projects.byId(projectId) == null) return null
        val grounds = points.groundsOf(projectId).map {
            PlanPoint(it.id, it.name, GeodeticPosition(it.groundLat, it.groundLon, it.groundH))
        }
        return CrsChange.plan(grounds, newCrs, newHeightSystem, calculator())
    }

    override suspend fun apply(projectId: String, newCrs: ProjectCrs, newHeightSystem: HeightSystem): CrsChangeResult =
        database.withTransaction {
            val current = projects.byId(projectId)?.toProject() ?: return@withTransaction CrsChangeResult.ProjectNotFound
            if (!CrsChange.sameDatum(current.crs, newCrs)) return@withTransaction CrsChangeResult.OtherDatum
            switchTo(projectId, current.crs, current.heightSystem, newCrs, newHeightSystem, CrsChangeKind.CHANGE)
        }

    /** El cambio de verdad, dentro de la transacción de quien llama: el SC y lo derivado de los puntos, y la fila del historial. */
    internal suspend fun switchTo(
        projectId: String,
        from: ProjectCrs,
        fromHeight: HeightSystem,
        to: ProjectCrs,
        toHeight: HeightSystem,
        kind: CrsChangeKind,
    ): CrsChangeResult {
        if (from == to && fromHeight == toHeight) return CrsChangeResult.NoChange
        val at = clock()
        val library = to as? LibraryCrs
        projects.setCrs(
            projectId,
            to.name,
            library?.definition?.toText(),
            library?.geoidGrid,
            library?.geoidConstantM,
            toHeight.name,
            at,
        )
        val engine = calculator()
        for (row in points.groundsOf(projectId)) rewrite(row, to, toHeight, engine)
        val entity = ProjectCrsHistoryEntity(
            ids(),
            projectId,
            at,
            ProjectCrsText.encode(from),
            ProjectCrsText.encode(to),
            fromHeight.name,
            toHeight.name,
            kind.name,
        )
        history.insert(entity)
        return CrsChangeResult.Applied(entity.toModel() ?: error("El cambio recién guardado no se puede leer"))
    }

    private suspend fun rewrite(row: PointGroundRow, crs: ProjectCrs, heightSystem: HeightSystem, engine: ProjectPositionCalculator) {
        val now = engine.fromGround(GeodeticPosition(row.groundLat, row.groundLon, row.groundH), crs, heightSystem)
        val measured = row.measuredGroundLat?.let { lat ->
            row.measuredGroundLon?.let { lon -> engine.fromGround(GeodeticPosition(lat, lon, row.measuredGroundH), crs, heightSystem) }
        }
        points.setDerived(
            row.id,
            now.easting,
            now.northing,
            now.height,
            measured?.easting,
            measured?.northing,
            measured?.height,
        )
    }

    private fun ProjectCrsHistoryEntity.toModel(): ProjectCrsChange? {
        val from = ProjectCrsText.decodeOrNull(fromCrs) ?: return null
        val to = ProjectCrsText.decodeOrNull(toCrs) ?: return null
        val fromHeight = HeightSystem.entries.firstOrNull { it.name == fromHeightSystem } ?: return null
        val toHeight = HeightSystem.entries.firstOrNull { it.name == toHeightSystem } ?: return null
        val changeKind = CrsChangeKind.entries.firstOrNull { it.name == kind } ?: return null
        return ProjectCrsChange(id, projectId, atMs, from, fromHeight, to, toHeight, changeKind)
    }
}
