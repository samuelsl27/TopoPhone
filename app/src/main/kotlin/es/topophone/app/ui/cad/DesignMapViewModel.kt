package es.topophone.app.ui.cad

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import es.topophone.app.di.IoDispatcher
import es.topophone.app.ui.map.MapCamera
import es.topophone.app.ui.map.MapTap
import es.topophone.core.geodesy.WebMercator
import es.topophone.core.model.cad.CadEntity
import es.topophone.core.model.cad.CadEntityRef
import es.topophone.core.model.cad.CadExtent
import es.topophone.core.model.cad.DesignLayer
import es.topophone.core.model.map.Basemap
import es.topophone.core.model.point.GeodeticPosition
import es.topophone.core.survey.DesignCandidate
import es.topophone.core.survey.DesignHitTest
import es.topophone.core.survey.ProjectPositionCalculator
import es.topophone.data.cad.CadDrawingWithLayers
import es.topophone.data.cad.CadRepository
import es.topophone.data.project.ProjectRepository
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** El elemento del dibujo que el usuario ha tocado, con lo que dice su ficha (S040/RF-23, RF-29). */
data class DesignSelection(
    val ref: CadEntityRef,
    val entity: CadEntity,
    val layerName: String,
    val drawingName: String,
    /** El dibujo tiene cotas y este elemento trae Z; si no, la ficha dice «Sin cota». */
    val hasElevation: Boolean,
)

/**
 * Lo que el mapa dibuja de los dibujos importados del proyecto (S040/RF-24, RF-25, RF-27): con la cámara quieta, los trozos de las
 * capas visibles que cortan la vista, simplificados según el zoom; se vuelve a calcular al mover el mapa, al ocultar una capa, al
 * importar o borrar un dibujo y al cambiar el mapa base (el blanco y el negro del DXF se pintan de contraste).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class DesignMapViewModel @Inject constructor(
    private val cad: CadRepository,
    private val projects: ProjectRepository,
    @param:IoDispatcher private val io: CoroutineDispatcher,
) : ViewModel() {
    private val projectId = MutableStateFlow<String?>(null)
    private val camera = MutableStateFlow<MapCamera?>(null)
    private val contrast = MutableStateFlow(DARK)

    /** La escena de ahora; null mientras no hay cámara, dibujo visible ni nada que cargar en la vista. */
    val scene: StateFlow<DesignScene?> = projectId.filterNotNull()
        .flatMapLatest { id ->
            val crs = projects.project(id)?.crs ?: return@flatMapLatest flowOf(null)
            combine(cad.drawings(id), camera.filterNotNull(), contrast) { drawings, cam, contrastArgb ->
                Inputs(drawings.flatMap { it.layers }.filter { it.visible }, cam, contrastArgb)
            }.mapLatest { input ->
                if (input.layers.isEmpty()) return@mapLatest null
                val area = DesignSceneBuilder.viewport(input.camera, crs) ?: return@mapLatest null
                val chunks = cad.chunksIn(input.layers.map { it.id }, area)
                val mpp = WebMercator.metresPerPixel(input.camera.latitudeDeg, input.camera.zoom)
                DesignSceneBuilder.build(crs, input.layers, chunks, mpp, input.contrast).takeUnless { it.isEmpty }
            }
        }
        .flowOn(io)
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** Los dibujos terminados del proyecto con sus capas, para el panel «Dibujos» (RF-26, RF-28). */
    val drawings: StateFlow<List<CadDrawingWithLayers>> = projectId.filterNotNull()
        .flatMapLatest { cad.drawings(it) }
        .flowOn(io)
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val selection = MutableStateFlow<DesignSelection?>(null)

    /** El elemento tocado, o null (RF-29). */
    val selected: StateFlow<DesignSelection?> = selection.asStateFlow()

    private class Inputs(val layers: List<DesignLayer>, val camera: MapCamera, val contrast: Int)

    /** Los dibujos del proyecto [id]; solo la primera vez. */
    fun start(id: String) {
        projectId.value = id
    }

    /**
     * Un toque en el mapa sin punto del proyecto debajo: el elemento visible del dibujo más cercano a menos de un dedo, o ninguno
     * (RF-29). Un toque que no acierta cierra la ficha.
     */
    fun onTap(tap: MapTap) {
        viewModelScope.launch(io) { selection.value = find(tap) }
    }

    fun dismissSelection() {
        selection.value = null
    }

    private suspend fun find(tap: MapTap): DesignSelection? {
        val id = projectId.value ?: return null
        val crs = projects.project(id)?.crs ?: return null
        val visible = drawings.value.flatMap { entry -> entry.layers.filter { it.visible }.map { it to entry.drawing } }
        if (visible.isEmpty()) return null
        val geographic = crs.utmZone == null
        val (east, north) = if (geographic) {
            tap.longitudeDeg to tap.latitudeDeg
        } else {
            val position = ProjectPositionCalculator().fromGround(GeodeticPosition(tap.latitudeDeg, tap.longitudeDeg, null), crs)
            (position.easting ?: return null) to (position.northing ?: return null)
        }
        val tolerance = if (geographic) tap.toleranceM / METRES_PER_DEGREE else tap.toleranceM
        val byLayer = visible.associateBy { it.first.id }
        val area = CadExtent(east - tolerance, east + tolerance, north - tolerance, north + tolerance)
        val candidates = cad.chunksIn(byLayer.keys, area).flatMap { chunk ->
            chunk.entities.mapIndexed { index, entity -> DesignCandidate(CadEntityRef(chunk.layerId, chunk.chunkId, index), entity) }
        }
        val hit = DesignHitTest.nearest(east, north, tolerance, candidates) ?: return null
        val (layer, drawing) = byLayer.getValue(hit.ref.layerId)
        return DesignSelection(hit.ref, hit.entity, layer.name, drawing.fileName, drawing.hasElevation(hit.entity.hasZ))
    }

    /** Muestra u oculta una capa de diseño; se recuerda para el proyecto, también al cerrar la app (RF-27). */
    fun setLayerVisible(layerId: String, visible: Boolean) {
        viewModelScope.launch(io) { cad.setLayerVisible(layerId, visible) }
    }

    /** Borra un dibujo entero con sus capas, sin tocar los puntos del proyecto (RF-30). */
    fun deleteDrawing(drawingId: String) {
        viewModelScope.launch(io) { cad.deleteDrawing(drawingId) }
    }

    /** La cámara del mapa ha dejado de moverse (RF-24). */
    fun onCamera(camera: MapCamera) {
        this.camera.value = camera
    }

    /** El mapa base de ahora: sobre la ortofoto el blanco y el negro del DXF se pintan de blanco; si no, de negro (RF-25). */
    fun setBasemap(basemap: Basemap) {
        contrast.value = if (basemap == Basemap.PNOA_ORTHOPHOTO) LIGHT else DARK
    }

    private companion object {
        const val METRES_PER_DEGREE = 111_320.0
        const val DARK = 0xFF000000.toInt()
        const val LIGHT = 0xFFFFFFFF.toInt()
    }
}
