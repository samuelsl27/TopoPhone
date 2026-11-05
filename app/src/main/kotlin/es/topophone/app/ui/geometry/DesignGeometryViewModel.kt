package es.topophone.app.ui.geometry

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import es.topophone.app.ui.cad.DesignSelection
import es.topophone.core.model.geometry.DistanceMode
import es.topophone.core.model.project.AngularUnit
import es.topophone.core.model.project.LinearUnit
import es.topophone.core.model.project.ProjectCrs
import es.topophone.core.survey.geometry.GeometryResult
import es.topophone.data.geometry.GeometryPreferences
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Lo que hace falta del proyecto para calcular la información de una línea de un dibujo (S026/RF-1, RF-4). */
data class DesignGeometryInput(
    val selection: DesignSelection,
    val projectName: String,
    val crs: ProjectCrs,
    val linearUnit: LinearUnit,
    val angularUnit: AngularUnit,
)

/**
 * La información geométrica de una línea de un dibujo (S026/RF-1, RF-4): como la de una línea del proyecto, con la misma distancia
 * elegida, pero sin alturas si el dibujo no tiene cota. No guarda nada. El cálculo va fuera del hilo principal (RNF-T13).
 */
@HiltViewModel
class DesignGeometryViewModel @Inject constructor(private val preferences: GeometryPreferences) : ViewModel() {
    private val input = MutableStateFlow<DesignGeometryInput?>(null)

    val state: StateFlow<GeometryInfoUi> = combine(input, preferences.distanceMode) { input, mode -> input to mode }
        .map { (input, mode) -> if (input == null) GeometryInfoUi(mode = mode) else compute(input, mode) }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), GeometryInfoUi())

    fun start(input: DesignGeometryInput) {
        this.input.value = input
    }

    /** Elige cuadrícula o terreno, para todos los proyectos (RF-9). */
    fun setMode(mode: DistanceMode) {
        viewModelScope.launch { preferences.setDistanceMode(mode) }
    }

    private fun compute(input: DesignGeometryInput, mode: DistanceMode): GeometryInfoUi {
        val entity = input.selection.entity
        val withHeight = input.selection.hasElevation && entity.height != null
        val line = GeometryInput.fromCad(entity, input.crs, withHeight, withGround = mode == DistanceMode.GROUND)
        val result = GeometryInput.compute(line.vertices, line.closed, mode, input.crs)
        return GeometryInfoUi(
            loaded = true,
            projectName = input.projectName,
            lineName = entity.name ?: input.selection.layerName,
            closed = line.closed,
            mode = mode,
            linearUnit = input.linearUnit,
            angularUnit = input.angularUnit,
            geographic = !input.crs.isUtm,
            info = (result as? GeometryResult.Info)?.info,
            names = List(line.vertices.size) { (it + 1).toString() },
            problem = when (result) {
                is GeometryResult.Info -> null
                GeometryResult.TooFewVertices -> GeometryProblem.TOO_FEW_VERTICES
                GeometryResult.NoPosition -> GeometryProblem.NO_POSITION
            },
            planOnly = !withHeight,
        )
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
