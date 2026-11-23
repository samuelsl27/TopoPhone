package es.topophone.data.crs

import androidx.room.withTransaction
import es.topophone.core.model.localization.LocalizationStatus
import es.topophone.core.model.point.GeodeticPosition
import es.topophone.core.model.project.CrsChangeKind
import es.topophone.core.model.project.HeightSystem
import es.topophone.core.model.project.LibraryCrs
import es.topophone.core.model.project.ProjectCrs
import es.topophone.core.model.project.ProjectCrsChange
import es.topophone.core.model.project.ProjectCrsText
import es.topophone.core.model.project.TerrainPlane
import es.topophone.core.survey.CrsChange
import es.topophone.core.survey.CrsChangePlan
import es.topophone.core.survey.PlanPoint
import es.topophone.core.survey.ProjectPositionCalculator
import es.topophone.core.survey.TerrainFrame
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

    /** El plano de terreno no vale en el SC resultante: es geográfico o su origen cae fuera del ámbito (S058/RF-2, RF-5). */
    data object InvalidTerrain : CrsChangeResult

    /** Hay una localización activa: no se cambia el SC, las alturas ni se pasa a terreno hasta quitarla (S039/RF-22, RF-27). */
    data object LocalizationActive : CrsChangeResult
}

/** Qué pasa con el plano de terreno en un cambio (S058/RF-19): se deja como está o se pone el plano o la cuadrícula (`null`). */
sealed interface TerrainChange {
    data object Keep : TerrainChange

    data class Set(val plane: TerrainPlane?) : TerrainChange
}

/**
 * El historial del proyecto y el cambio de su SC (S035/RF-17 a RF-21). Cambiar el SC reescribe, en **una sola transacción** (RNF-T6), el SC del
 * proyecto, lo derivado de cada punto (Este, Norte y altura, también de la medida) y una fila del historial; las geodésicas y la medida no se tocan.
 */
interface ProjectCrsHistoryRepository {
    /** Los cambios del proyecto, del más reciente al más antiguo. */
    fun observe(projectId: String): Flow<List<ProjectCrsChange>>

    /**
     * ¿Impide una localización activa este cambio? Sí si cambia el SC o el sistema de alturas, o si el proyecto pasa a terreno (S039/RF-22, RF-27).
     * Sin localización activa, nunca.
     */
    suspend fun blocksChange(projectId: String, newCrs: ProjectCrs, newHeightSystem: HeightSystem, newTerrain: TerrainPlane?): Boolean =
        false

    /** Qué puntos no se podrían calcular en [newCrs] (S035/RF-19); `null` si el proyecto no existe. */
    suspend fun preview(projectId: String, newCrs: ProjectCrs, newHeightSystem: HeightSystem): CrsChangePlan?

    /**
     * Cambia el SC del proyecto y lo anota en el historial. Con puntos que no se calculan, quedan sin coordenadas hasta que se vuelva a un SC que los
     * calcule. Con [terrain] cambia también el plano de terreno (S058/RF-19): un cambio solo de plano no toca ningún punto, porque lo guardado sigue en
     * cuadrícula (RF-8, RF-20).
     */
    suspend fun apply(
        projectId: String,
        newCrs: ProjectCrs,
        newHeightSystem: HeightSystem,
        terrain: TerrainChange = TerrainChange.Keep,
    ): CrsChangeResult

    /**
     * «Volver al SC anterior» (S035/RF-21): deshace el cambio más reciente, que queda anotado como un cambio más de tipo [CrsChangeKind.UNDO]. Con otro
     * `undo` encima se vuelve al SC de antes de deshacer. [CrsChangeResult.NothingToUndo] si no hay historial o el SC del proyecto ya no es el del último
     * cambio (se cambió por otro camino).
     */
    suspend fun undo(projectId: String): CrsChangeResult
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

    override suspend fun blocksChange(
        projectId: String,
        newCrs: ProjectCrs,
        newHeightSystem: HeightSystem,
        newTerrain: TerrainPlane?,
    ): Boolean {
        val current = projects.byId(projectId)?.toProject() ?: return false
        return hasActiveLocalization(projectId) && locks(current.crs, current.heightSystem, newCrs, newHeightSystem, newTerrain)
    }

    private suspend fun hasActiveLocalization(projectId: String): Boolean =
        database.localizations().withStatusNow(projectId, LocalizationStatus.ACTIVE.name) != null

    /** El SC o las alturas cambian, o se pasa a terreno (S039/RF-22, RF-27). */
    private fun locks(
        crs: ProjectCrs,
        height: HeightSystem,
        newCrs: ProjectCrs,
        newHeight: HeightSystem,
        newTerrain: TerrainPlane?,
    ): Boolean = crs != newCrs || height != newHeight || newTerrain != null

    override suspend fun preview(projectId: String, newCrs: ProjectCrs, newHeightSystem: HeightSystem): CrsChangePlan? {
        if (projects.byId(projectId) == null) return null
        val grounds = points.groundsOf(projectId).map {
            PlanPoint(it.id, it.name, GeodeticPosition(it.groundLat, it.groundLon, it.groundH))
        }
        return CrsChange.plan(grounds, newCrs, newHeightSystem, calculator())
    }

    override suspend fun apply(
        projectId: String,
        newCrs: ProjectCrs,
        newHeightSystem: HeightSystem,
        terrain: TerrainChange,
    ): CrsChangeResult = database.withTransaction {
        val current = projects.byId(projectId)?.toProject() ?: return@withTransaction CrsChangeResult.ProjectNotFound
        if (!CrsChange.sameDatum(current.crs, newCrs)) return@withTransaction CrsChangeResult.OtherDatum
        val newTerrain = if (terrain is TerrainChange.Set) terrain.plane else current.terrain
        // S039/RF-22, RF-27: con una localización activa no se cambia el SC, las alturas ni se pasa a terreno.
        if (hasActiveLocalization(projectId) && locks(current.crs, current.heightSystem, newCrs, newHeightSystem, newTerrain)) {
            return@withTransaction CrsChangeResult.LocalizationActive
        }
        // S058/RF-2, RF-5: el plano tiene que valer en el SC resultante.
        if (newTerrain != null &&
            TerrainFrame.of(newTerrain, newCrs, calculator()) == null
        ) {
            return@withTransaction CrsChangeResult.InvalidTerrain
        }
        switchTo(
            projectId,
            Snapshot(current.crs, current.heightSystem, current.terrain),
            Snapshot(newCrs, newHeightSystem, newTerrain),
            CrsChangeKind.CHANGE,
        )
    }

    override suspend fun undo(projectId: String): CrsChangeResult = database.withTransaction {
        val current = projects.byId(projectId)?.toProject() ?: return@withTransaction CrsChangeResult.ProjectNotFound
        val last = history.latest(projectId)?.toModel() ?: return@withTransaction CrsChangeResult.NothingToUndo
        if (last.to != current.crs || last.toHeightSystem != current.heightSystem || last.toTerrain != current.terrain) {
            return@withTransaction CrsChangeResult.NothingToUndo
        }
        if (hasActiveLocalization(projectId) &&
            locks(current.crs, current.heightSystem, last.from, last.fromHeightSystem, last.fromTerrain)
        ) {
            return@withTransaction CrsChangeResult.LocalizationActive
        }
        switchTo(
            projectId,
            Snapshot(current.crs, current.heightSystem, current.terrain),
            Snapshot(last.from, last.fromHeightSystem, last.fromTerrain),
            CrsChangeKind.UNDO,
        )
    }

    /** El SC, las alturas y el plano de terreno de un proyecto en un momento. */
    internal data class Snapshot(val crs: ProjectCrs, val heightSystem: HeightSystem, val terrain: TerrainPlane?)

    /** El cambio de verdad, dentro de la transacción de quien llama: el SC y lo derivado de los puntos, y la fila del historial. */
    internal suspend fun switchTo(projectId: String, from: Snapshot, to: Snapshot, kind: CrsChangeKind): CrsChangeResult {
        if (from == to) return CrsChangeResult.NoChange
        val at = clock()
        val library = to.crs as? LibraryCrs
        projects.setCrs(
            projectId,
            to.crs.name,
            library?.definition?.toText(),
            library?.geoidGrid,
            library?.geoidConstantM,
            to.heightSystem.name,
            at,
        )
        projects.setTerrain(projectId, to.terrain?.originLatitudeDeg, to.terrain?.originLongitudeDeg, to.terrain?.factor)
        // S058/RF-20: un cambio solo de plano no toca ningún punto; lo guardado sigue en cuadrícula.
        if (from.crs != to.crs || from.heightSystem != to.heightSystem) {
            val engine = calculator()
            for (row in points.groundsOf(projectId)) rewrite(row, to.crs, to.heightSystem, engine)
        }
        val entity = ProjectCrsHistoryEntity(
            ids(),
            projectId,
            at,
            ProjectCrsText.encode(from.crs),
            ProjectCrsText.encode(to.crs),
            from.heightSystem.name,
            to.heightSystem.name,
            kind.name,
            from.terrain?.toText(),
            to.terrain?.toText(),
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
        return ProjectCrsChange(
            id,
            projectId,
            atMs,
            from,
            fromHeight,
            to,
            toHeight,
            changeKind,
            TerrainPlane.parseOrNull(fromTerrain),
            TerrainPlane.parseOrNull(toTerrain),
        )
    }
}
