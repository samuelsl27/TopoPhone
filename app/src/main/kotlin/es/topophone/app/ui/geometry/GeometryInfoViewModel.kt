package es.topophone.app.ui.geometry

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import es.topophone.core.model.geometry.DistanceMode
import es.topophone.core.model.project.AngularUnit
import es.topophone.core.model.project.LinearUnit
import es.topophone.core.model.project.ProjectCrs
import es.topophone.core.survey.geometry.GeometryInfo
import es.topophone.core.survey.geometry.GeometryResult
import es.topophone.data.geometry.GeometryPreferences
import es.topophone.data.line.LineRepository
import es.topophone.data.line.VertexPoint
import es.topophone.data.project.ProjectRepository
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Por qué no hay información que mostrar (S026/RF-7, RF-11). */
enum class GeometryProblem { TOO_FEW_VERTICES, NO_POSITION }

/** Lo que muestra la información geométrica de una línea o un polígono (S026/RF-1 a RF-3, RF-6, RF-9, RF-11, RF-12). */
data class GeometryInfoUi(
    val loaded: Boolean = false,
    val lineName: String = "",
    val closed: Boolean = false,
    val mode: DistanceMode = DistanceMode.GRID,
    val linearUnit: LinearUnit = LinearUnit.METRE,
    val angularUnit: AngularUnit = AngularUnit.DMS,
    /** El SC del proyecto es geográfico (RF-11). */
    val geographic: Boolean = false,
    val info: GeometryInfo? = null,
    /** El nombre de cada vértice, en el orden de la línea. */
    val names: List<String> = emptyList(),
    val problem: GeometryProblem? = null,
)

/**
 * La información geométrica de una línea del proyecto (S026): lee la línea, sus vértices y el modo elegido, y recalcula cada vez que
 * cambia alguno, también al editar un punto (RF-5). No escribe nada. El cálculo va fuera del hilo principal (RNF-T13).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class GeometryInfoViewModel @Inject constructor(
    private val lines: LineRepository,
    projects: ProjectRepository,
    private val preferences: GeometryPreferences,
) : ViewModel() {
    private val ids = MutableStateFlow<Pair<String, String>?>(null)

    private data class Source(
        val name: String,
        val closed: Boolean,
        val vertices: List<VertexPoint>,
        val crs: ProjectCrs,
        val linearUnit: LinearUnit,
        val angularUnit: AngularUnit,
    )

    private val source = ids.flatMapLatest { pair ->
        if (pair == null) {
            flowOf(null)
        } else {
            combine(lines.line(pair.second), lines.vertexPoints(pair.second), projects.observe(pair.first)) { line, vertices, project ->
                if (line == null || project == null) {
                    null
                } else {
                    Source(line.name, line.closed, vertices, project.crs, project.linearUnit, project.angularUnit)
                }
            }
        }
    }

    val state: StateFlow<GeometryInfoUi> = combine(source, preferences.distanceMode) { source, mode -> source to mode }
        .map { (source, mode) -> if (source == null) GeometryInfoUi(loaded = true, mode = mode) else source.toUi(mode) }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), GeometryInfoUi(mode = DistanceMode.GRID))

    fun start(projectId: String, lineId: String) {
        ids.value = projectId to lineId
    }

    /** Elige cuadrícula o terreno, para todos los proyectos (RF-9). */
    fun setMode(mode: DistanceMode) {
        viewModelScope.launch { preferences.setDistanceMode(mode) }
    }

    private fun Source.toUi(mode: DistanceMode): GeometryInfoUi {
        val result = GeometryInput.compute(GeometryInput.fromVertexPoints(vertices), closed, mode, crs)
        return GeometryInfoUi(
            loaded = true,
            lineName = name,
            closed = closed,
            mode = mode,
            linearUnit = linearUnit,
            angularUnit = angularUnit,
            geographic = !crs.isUtm,
            info = (result as? GeometryResult.Info)?.info,
            names = vertices.map { it.name },
            problem = when (result) {
                is GeometryResult.Info -> null
                GeometryResult.TooFewVertices -> GeometryProblem.TOO_FEW_VERTICES
                GeometryResult.NoPosition -> GeometryProblem.NO_POSITION
            },
        )
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
