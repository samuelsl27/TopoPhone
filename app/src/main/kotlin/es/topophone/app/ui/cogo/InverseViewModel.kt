package es.topophone.app.ui.cogo

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import es.topophone.core.model.geometry.DistanceMode
import es.topophone.core.model.project.AngularUnit
import es.topophone.core.model.project.LinearUnit
import es.topophone.core.model.project.ProjectCrs
import es.topophone.core.survey.TerrainView
import es.topophone.core.survey.cogo.CogoScale
import es.topophone.core.survey.cogo.Inverse
import es.topophone.core.survey.cogo.InverseResult
import es.topophone.data.geometry.GeometryPreferences
import es.topophone.data.point.PointRepository
import es.topophone.data.project.ProjectRepository
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

internal const val SLOT_A = "a"
internal const val SLOT_B = "b"
private const val STOP_TIMEOUT_MS = 5_000L

/** Lo que muestra la inversa (S038/RF-5 a RF-9). */
data class InverseUi(
    val loaded: Boolean = false,
    val mode: DistanceMode = DistanceMode.GRID,
    /** S058/RF-9: el proyecto está en terreno; se calcula en terreno y no hay elección Cuadrícula/Terreno. */
    val terrain: Boolean = false,
    val linearUnit: LinearUnit = LinearUnit.METRE,
    val angularUnit: AngularUnit = AngularUnit.DMS,
    val a: CogoSlotValue = CogoSlotValue.Empty,
    val b: CogoSlotValue = CogoSlotValue.Empty,
    val aSelection: CogoSelection? = null,
    val bSelection: CogoSelection? = null,
    val picker: CogoPickerUi = CogoPickerUi(),
    /** Null hasta que están elegidos los dos puntos. */
    val result: InverseResult? = null,
)

/**
 * La inversa entre dos puntos del proyecto o escritos a mano (S038/RF-6 a RF-9). La distancia activa es la elección Cuadrícula/Terreno
 * de S026, común a toda la app (RF-5). No guarda nada.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class InverseViewModel @Inject constructor(
    points: PointRepository,
    private val projects: ProjectRepository,
    private val preferences: GeometryPreferences,
) : ViewModel() {
    private val projectId = MutableStateFlow<String?>(null)
    val inputs = CogoInputs(points, viewModelScope)

    private data class Context(val crs: ProjectCrs, val linearUnit: LinearUnit, val angularUnit: AngularUnit, val terrain: TerrainView)

    private val context = projectId.flatMapLatest { id ->
        if (id ==
            null
        ) {
            flowOf(null)
        } else {
            projects.observe(id).map {
                it?.let { p ->
                    Context(
                        p.crs,
                        p.linearUnit,
                        p.angularUnit,
                        TerrainView.of(p.terrain, p.crs) ?: TerrainView.GRID,
                    )
                }
            }
        }
    }

    val state: StateFlow<InverseUi> = combine(
        context,
        preferences.distanceMode,
        inputs.selections,
        inputs.values(
            context.map { it?.linearUnit ?: LinearUnit.METRE }.distinctUntilChanged(),
            context.map { it?.terrain ?: TerrainView.GRID },
        ),
        inputs.picker(projectId),
    ) { context, chosenMode, selections, values, picker ->
        if (context == null) return@combine InverseUi(mode = chosenMode)
        // S058/RF-9: en terreno los puntos ya están en terreno: se calcula en el plano, sin volver a escalar.
        val mode = if (context.terrain.isTerrain) DistanceMode.GRID else chosenMode
        val a = values[SLOT_A] ?: CogoSlotValue.Empty
        val b = values[SLOT_B] ?: CogoSlotValue.Empty
        val result = if (a is CogoSlotValue.Found && b is CogoSlotValue.Found) {
            Inverse.of(a.point, b.point, CogoScale.forFirst(a.point, context.crs, mode))
        } else {
            null
        }
        InverseUi(
            true,
            mode,
            context.terrain.isTerrain,
            context.linearUnit,
            context.angularUnit,
            a,
            b,
            selections[SLOT_A],
            selections[SLOT_B],
            picker,
            result,
        )
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), InverseUi())

    fun start(id: String) {
        projectId.value = id
    }

    /** Elige cuadrícula o terreno, para todos los proyectos (S038/RF-5). */
    fun setMode(mode: DistanceMode) {
        viewModelScope.launch { preferences.setDistanceMode(mode) }
    }

    fun swap() = inputs.swap(SLOT_A, SLOT_B)
}
