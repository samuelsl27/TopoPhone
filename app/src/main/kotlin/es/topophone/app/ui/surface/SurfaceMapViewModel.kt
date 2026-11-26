package es.topophone.app.ui.surface

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import es.topophone.app.di.IoDispatcher
import es.topophone.app.ui.map.MapTap
import es.topophone.core.geodesy.GeoBox
import es.topophone.core.model.point.GeodeticPosition
import es.topophone.core.model.surface.Surface
import es.topophone.core.survey.ProjectPositionCalculator
import es.topophone.data.project.ProjectRepository
import es.topophone.data.surface.RasterProjectContext
import es.topophone.data.surface.SurfaceProbe
import es.topophone.data.surface.SurfaceRepository
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

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

    private val tapResolver = SurfaceTapResolver(SurfaceProbe(surfaces))
    private val tap = MutableStateFlow<SurfaceTapUi?>(null)

    /** La tarjeta del último toque sobre superficies visibles, o null (S061/RF-16 a RF-18). */
    val tapped: StateFlow<SurfaceTapUi?> = tap.asStateFlow()

    /** Un toque del mapa sin punto ni línea debajo: las superficies visibles que hay allí y su cota; un toque fuera cierra la tarjeta. */
    fun onTap(mapTap: MapTap) {
        viewModelScope.launch(io) { tap.value = resolve(mapTap) }
    }

    fun dismissTap() {
        tap.value = null
    }

    private suspend fun resolve(mapTap: MapTap): SurfaceTapUi? {
        val id = projectId.value ?: return null
        val project = projects.project(id) ?: return null
        val (east, north) = if (!project.crs.isProjected) {
            mapTap.longitudeDeg to mapTap.latitudeDeg
        } else {
            val position = ProjectPositionCalculator().fromGround(
                GeodeticPosition(mapTap.latitudeDeg, mapTap.longitudeDeg, null),
                project.crs,
            )
            (position.easting ?: return null) to (position.northing ?: return null)
        }
        return tapResolver.resolve(
            surfaces.surfaces(id).first(),
            surfaces.activeSurfaceId(id).first(),
            east,
            north,
            RasterProjectContext(project.crs, project.heightSystem, project.terrain),
        )
    }

    /** La zona que se ve con la cámara quieta; null si el mapa aún no se ha dibujado. */
    fun onView(box: GeoBox?) {
        if (box != null) view.value = box
    }
}
