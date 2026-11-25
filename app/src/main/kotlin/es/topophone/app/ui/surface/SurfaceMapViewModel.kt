package es.topophone.app.ui.surface

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import es.topophone.app.di.IoDispatcher
import es.topophone.core.geodesy.GeoBox
import es.topophone.core.model.surface.Surface
import es.topophone.data.project.ProjectRepository
import es.topophone.data.surface.SurfaceRepository
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn

/**
 * Lo que el mapa dibuja de las superficies del proyecto (S061/RF-6 a RF-13): con la cámara quieta, las superficies marcadas como
 * visibles en la zona que se ve. Se vuelve a calcular al mover el mapa, al mostrar u ocultar una superficie, al cambiar la activa, al
 * importar o borrar una y al cambiar el SC del proyecto.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class SurfaceMapViewModel @Inject constructor(
    private val surfaces: SurfaceRepository,
    private val projects: ProjectRepository,
    @param:IoDispatcher private val io: CoroutineDispatcher,
) : ViewModel() {
    private val projectId = MutableStateFlow<String?>(null)
    private val view = MutableStateFlow<GeoBox?>(null)
    private val builder = SurfaceSceneBuilder(surfaces)

    /** La escena de ahora; null mientras no hay vista ni superficie visible. */
    val scene: StateFlow<SurfaceScene?> = projectId.filterNotNull()
        .flatMapLatest { id ->
            combine(surfaces.surfaces(id), surfaces.activeSurfaceId(id), projects.observe(id), view.filterNotNull()) {
                    list,
                    active,
                    project,
                    box,
                ->
                Inputs(list.filter { it.visible }, active, project?.let { SurfaceProjectFrame(it.crs, it.heightSystem, it.terrain) }, box)
            }
        }
        .mapLatest { input ->
            val frame = input.frame ?: return@mapLatest null
            builder.build(frame, input.surfaces, input.activeId, input.view)
        }
        .flowOn(io)
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private class Inputs(val surfaces: List<Surface>, val activeId: String?, val frame: SurfaceProjectFrame?, val view: GeoBox)

    /** Las superficies del proyecto [id]. */
    fun start(id: String) {
        projectId.value = id
    }

    /** La zona que se ve con la cámara quieta; null si el mapa aún no se ha dibujado. */
    fun onView(box: GeoBox?) {
        if (box != null) view.value = box
    }
}
