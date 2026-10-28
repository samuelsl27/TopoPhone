package es.topophone.app.ui.alignment

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import es.topophone.app.di.IoDispatcher
import es.topophone.app.ui.cad.DesignSceneBuilder
import es.topophone.app.ui.map.MapCamera
import es.topophone.core.geodesy.WebMercator
import es.topophone.data.alignment.AlignmentRepository
import es.topophone.data.project.ProjectRepository
import java.util.Locale
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
 * Lo que el mapa dibuja de los ejes visibles del proyecto (S049/RF-17, RF-19): con la cámara quieta, los ejes que cortan la vista con
 * sus marcas de PK; se vuelve a calcular al mover el mapa, al mostrar u ocultar un eje, al importar o borrar uno y al cambiar el activo.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class AlignmentMapViewModel @Inject constructor(
    private val alignments: AlignmentRepository,
    private val projects: ProjectRepository,
    @param:IoDispatcher private val io: CoroutineDispatcher,
) : ViewModel() {
    private val projectId = MutableStateFlow<String?>(null)
    private val camera = MutableStateFlow<MapCamera?>(null)

    /** La escena de ahora; null mientras no hay cámara, eje visible ni nada que cargar en la vista. */
    val scene: StateFlow<AlignmentScene?> = projectId.filterNotNull()
        .flatMapLatest { id ->
            val project = projects.project(id) ?: return@flatMapLatest flowOf(null)
            if (project.crs.utmZone == null) return@flatMapLatest flowOf(null)
            combine(alignments.alignments(id), alignments.activeAlignmentId(id), camera.filterNotNull()) { list, active, cam ->
                Triple(list, active, cam)
            }.mapLatest { (list, active, cam) ->
                val metresPerPixel = WebMercator.metresPerPixel(cam.latitudeDeg, cam.zoom)
                val viewport = DesignSceneBuilder.viewport(cam, project.crs) ?: return@mapLatest null
                AlignmentSceneBuilder.build(list, active, project.crs, project.linearUnit, Locale.getDefault(), metresPerPixel, viewport)
            }
        }
        .flowOn(io)
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** Empieza a seguir los ejes del proyecto [id]. */
    fun start(id: String) {
        projectId.value = id
    }

    /** La cámara del mapa ha dejado de moverse. */
    fun onCamera(camera: MapCamera) {
        this.camera.value = camera
    }
}
