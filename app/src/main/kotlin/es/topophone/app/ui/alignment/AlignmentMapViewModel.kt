package es.topophone.app.ui.alignment

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import es.topophone.app.di.IoDispatcher
import es.topophone.app.ui.cad.DesignSceneBuilder
import es.topophone.app.ui.map.MapCamera
import es.topophone.app.ui.map.MapTap
import es.topophone.core.geodesy.WebMercator
import es.topophone.core.model.point.GeodeticPosition
import es.topophone.core.survey.ProjectPositionCalculator
import es.topophone.data.alignment.AlignmentRepository
import es.topophone.data.project.ProjectRepository
import java.util.Locale
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
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Lo que el mapa dibuja de los ejes visibles del proyecto y lo que pasa al tocarlos (S049/RF-17, RF-18, RF-19): con la cámara quieta, los
 * ejes que cortan la vista con sus marcas de PK, que se vuelven a calcular al mover el mapa, al mostrar u ocultar un eje, al importar o
 * borrar uno y al cambiar el activo; y, al tocar uno, su ficha con el PK del punto tocado.
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

    private val touch = MutableStateFlow<AlignmentTouch?>(null)

    /** El eje tocado, o null (RF-18). */
    val touched: StateFlow<AlignmentTouch?> = touch.asStateFlow()

    /** Empieza a seguir los ejes del proyecto [id]. */
    fun start(id: String) {
        projectId.value = id
    }

    /** La cámara del mapa ha dejado de moverse. */
    fun onCamera(camera: MapCamera) {
        this.camera.value = camera
    }

    /**
     * Un toque en el mapa: el eje visible más cercano a menos de un dedo, con el PK de su pie de la perpendicular y la cota de su rasante
     * ahí, o ninguno (RF-18). Un toque que no acierta cierra la ficha.
     */
    fun onTap(tap: MapTap) {
        viewModelScope.launch(io) { touch.value = find(tap) }
    }

    fun dismissTouch() {
        touch.value = null
    }

    private suspend fun find(tap: MapTap): AlignmentTouch? {
        val id = projectId.value ?: return null
        val project = projects.project(id) ?: return null
        if (project.crs.utmZone == null) return null
        val position = ProjectPositionCalculator().fromGround(GeodeticPosition(tap.latitudeDeg, tap.longitudeDeg, null), project.crs)
        val east = position.easting ?: return null
        val north = position.northing ?: return null
        val list = alignments.alignments(id).first()
        return AlignmentHitTest.nearest(list, alignments.activeAlignmentId(id).first(), east, north, tap.toleranceM)
    }
}
