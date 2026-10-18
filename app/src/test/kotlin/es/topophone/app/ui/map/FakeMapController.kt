package es.topophone.app.ui.map

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.LaunchedEffect
import es.topophone.app.ui.projects.MapSlot
import es.topophone.core.model.map.MapSettings
import es.topophone.core.model.map.MapViewport
import es.topophone.core.model.point.CollectedPoint
import es.topophone.core.model.project.LinearUnit
import es.topophone.data.map.MapPreferences
import es.topophone.data.map.MapViewportRepository
import es.topophone.data.point.PointRepository
import es.topophone.data.point.PointSaveResult
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
    private val gestures = MutableSharedFlow<Unit>(extraBufferCapacity = 8)
    override val userGestures: Flow<Unit> = gestures

    /** El usuario mueve el mapa con el dedo. */
    fun gesture() {
        gestures.tryEmit(Unit)
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

/** Puntos en memoria (S010). */
class FakePointRepository(initial: List<CollectedPoint> = emptyList()) : PointRepository {
    val points = MutableStateFlow(initial)

    override suspend fun create(point: CollectedPoint): PointSaveResult {
        points.value = points.value + point
        return PointSaveResult.Saved
    }

    override fun points(projectId: String): Flow<List<CollectedPoint>> = points.map { list -> list.filter { it.projectId == projectId } }

    override fun count(projectId: String): Flow<Int> = points(projectId).map { it.size }

    override suspend fun lastName(projectId: String): String? = points.value.lastOrNull { it.projectId == projectId }?.name

    override suspend fun nameExists(projectId: String, name: String): Boolean = points.value.any {
        it.projectId == projectId &&
            it.name == name
    }
}
