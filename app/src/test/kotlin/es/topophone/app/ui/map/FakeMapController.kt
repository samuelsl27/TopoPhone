package es.topophone.app.ui.map

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.LaunchedEffect
import es.topophone.app.ui.projects.MapSlot
import es.topophone.core.model.map.MapSettings
import es.topophone.core.model.map.MapViewport
import es.topophone.core.model.point.Point
import es.topophone.core.model.point.PointChange
import es.topophone.core.model.point.PointHistoryEntry
import es.topophone.core.model.point.PointSnapshot
import es.topophone.core.model.point.PointSummary
import es.topophone.core.model.project.LinearUnit
import es.topophone.data.map.MapPreferences
import es.topophone.data.map.MapViewportRepository
import es.topophone.data.point.PointRepository
import es.topophone.data.point.PointSaveResult
import es.topophone.data.point.TrashedPoint
import es.topophone.data.project.ProjectRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/** Doble de [MapController] para los tests de la interfaz: MapLibre no funciona en Robolectric (código nativo). */
class FakeMapController : MapController {
    val settings = mutableListOf<MapSettings>()
    var unit: LinearUnit? = null
    override val camera = MutableStateFlow<MapCamera?>(null)
    var rover: RoverMarker? = null
    var roverCalls = 0
    var points: List<MapPoint> = emptyList()
    val moves = mutableListOf<MapViewport>()
    var spainShown = false
    var bottomInsetPx = 0
    private val gestures = MutableSharedFlow<Unit>(extraBufferCapacity = 8)
    override val userGestures: Flow<Unit> = gestures

    /** El usuario mueve el mapa con el dedo. */
    fun gesture() {
        gestures.tryEmit(Unit)
    }

    private val clicks = MutableSharedFlow<String>(extraBufferCapacity = 8)
    override val pointClicks: Flow<String> = clicks

    /** El usuario toca el punto [id] en el mapa. */
    fun clickPoint(id: String) {
        clicks.tryEmit(id)
    }

    override fun setSettings(settings: MapSettings) {
        this.settings += settings
    }

    override fun setLinearUnit(unit: LinearUnit) {
        this.unit = unit
    }

    override fun showRover(marker: RoverMarker?) {
        rover = marker
        roverCalls++
    }

    override fun showPoints(points: List<MapPoint>) {
        this.points = points
    }

    override fun moveTo(viewport: MapViewport) {
        moves += viewport
        camera.value = MapCamera(viewport.latitudeDeg, viewport.longitudeDeg, viewport.zoom)
    }

    override fun showSpain() {
        spainShown = true
    }

    override fun setBottomInset(px: Int) {
        bottomInsetPx = px
    }
}

/** Hueco del mapa con el doble: una caja vacía que entrega [controller] nada más aparecer. */
fun fakeMap(controller: FakeMapController): MapSlot = { modifier, onReady ->
    Box(modifier)
    LaunchedEffect(Unit) { onReady(controller) }
}

/** Preferencias del mapa en memoria (S009/RF-8). */
class FakeMapPreferences(initial: MapSettings = MapSettings()) : MapPreferences {
    override val settings = MutableStateFlow(initial)

    override suspend fun save(settings: MapSettings) {
        this.settings.value = settings
    }
}

/** Encuadres del mapa en memoria (S009/RF-21). */
class FakeMapViewportRepository : MapViewportRepository {
    val saved = mutableMapOf<String, MapViewport>()

    override suspend fun viewport(projectId: String): MapViewport? = saved[projectId]

    override suspend fun save(projectId: String, viewport: MapViewport) {
        saved[projectId] = viewport
    }
}

/** Puntos en memoria (S010, S011), con la papelera y el historial como en Room. */
class FakePointRepository(initial: List<Point> = emptyList(), private val clock: () -> Long = { 0L }) : PointRepository {
    val points = MutableStateFlow(initial)
    val historyEntries = MutableStateFlow<List<PointHistoryEntry>>(emptyList())

    private fun active(projectId: String) = points.value.filter { it.projectId == projectId && it.deletedAtMs == null }

    override suspend fun create(point: Point): PointSaveResult {
        if (active(point.projectId).any { it.name == point.name }) return PointSaveResult.DuplicateName
        points.value = points.value + point
        return PointSaveResult.Saved
    }

    override fun points(projectId: String): Flow<List<Point>> =
        points.map { list -> list.filter { it.projectId == projectId && it.deletedAtMs == null } }

    override fun summaries(projectId: String): Flow<List<PointSummary>> =
        points(projectId).map { list -> list.sortedByDescending { it.createdAtMs }.map { it.summary() } }

    override fun point(id: String): Flow<Point?> = points.map { list -> list.firstOrNull { it.id == id } }

    override fun history(id: String): Flow<List<PointHistoryEntry>> =
        historyEntries.map { list -> list.filter { it.pointId == id }.sortedByDescending { it.changedAtMs } }

    override fun count(projectId: String): Flow<Int> = points(projectId).map { it.size }

    override suspend fun lastName(projectId: String): String? = active(projectId).maxByOrNull { it.createdAtMs }?.name

    override suspend fun nameExists(projectId: String, name: String): Boolean = active(projectId).any { it.name == name }

    override suspend fun names(projectId: String): Set<String> = active(projectId).map { it.name }.toSet()

    override suspend fun update(point: Point, change: PointChange): PointSaveResult = updateAll(listOf(point), change)

    override suspend fun updateAll(points: List<Point>, change: PointChange): PointSaveResult {
        val current = this.points.value
        for (point in points) {
            if (current.none { it.id == point.id }) return PointSaveResult.NotFound
            val clash = current.any {
                it.id != point.id &&
                    it.projectId == point.projectId &&
                    it.deletedAtMs == null &&
                    it.name == point.name
            }
            if (clash) return PointSaveResult.DuplicateName
        }
        val byId = points.associateBy { it.id }
        historyEntries.value = historyEntries.value + points.map { point ->
            val previous = current.first { it.id == point.id }
            PointHistoryEntry("h-${historyEntries.value.size}-${point.id}", point.id, clock(), change, PointSnapshot.of(previous))
        }
        this.points.value = current.map { byId[it.id] ?: it }
        return PointSaveResult.Saved
    }

    override suspend fun setHidden(ids: List<String>, hidden: Boolean) {
        points.value = points.value.map { if (it.id in ids) it.copy(hidden = hidden) else it }
    }

    override suspend fun moveToTrash(ids: List<String>) {
        points.value = points.value.map { if (it.id in ids && it.deletedAtMs == null) it.copy(deletedAtMs = clock()) else it }
    }

    override fun trash(projectId: String): Flow<List<TrashedPoint>> = points.map { list ->
        list.mapNotNull { point ->
            val deletedAt = point.deletedAtMs ?: return@mapNotNull null
            if (point.projectId != projectId) return@mapNotNull null
            TrashedPoint(point, ProjectRepository.TRASH_DAYS - ((clock() - deletedAt) / DAY_MS).toInt())
        }.sortedByDescending { it.point.deletedAtMs }
    }

    override suspend fun restore(id: String, newName: String?): PointSaveResult {
        val point = points.value.firstOrNull { it.id == id } ?: return PointSaveResult.NotFound
        val name = newName ?: point.name
        if (active(point.projectId).any { it.name == name }) return PointSaveResult.DuplicateName
        points.value = points.value.map { if (it.id == id) it.copy(name = name, deletedAtMs = null) else it }
        return PointSaveResult.Saved
    }

    override suspend fun deletePermanently(id: String) {
        points.value = points.value.filterNot { it.id == id && it.deletedAtMs != null }
    }

    override suspend fun purgeTrash(): Int {
        val limit = clock() - ProjectRepository.TRASH_DAYS * DAY_MS
        val expired = points.value.filter { point -> point.deletedAtMs?.let { it < limit } == true }
        points.value = points.value - expired.toSet()
        return expired.size
    }

    private companion object {
        const val DAY_MS = 24L * 60 * 60 * 1000
    }
}
