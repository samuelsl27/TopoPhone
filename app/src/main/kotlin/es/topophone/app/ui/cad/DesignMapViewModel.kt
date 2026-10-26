package es.topophone.app.ui.cad

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import es.topophone.app.di.IoDispatcher
import es.topophone.app.ui.map.MapCamera
import es.topophone.core.geodesy.WebMercator
import es.topophone.core.model.cad.DesignLayer
import es.topophone.core.model.map.Basemap
import es.topophone.data.cad.CadRepository
import es.topophone.data.project.ProjectRepository
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn

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

    private class Inputs(val layers: List<DesignLayer>, val camera: MapCamera, val contrast: Int)

    /** Los dibujos del proyecto [id]; solo la primera vez. */
    fun start(id: String) {
        projectId.value = id
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
        const val DARK = 0xFF000000.toInt()
        const val LIGHT = 0xFFFFFFFF.toInt()
    }
}
