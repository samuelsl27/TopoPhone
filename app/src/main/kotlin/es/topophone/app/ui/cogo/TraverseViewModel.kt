package es.topophone.app.ui.cogo

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import es.topophone.core.model.cogo.LegKind
import es.topophone.core.model.cogo.TraverseLeg
import es.topophone.core.model.format.DirectionInput
import es.topophone.core.model.format.DirectionKind
import es.topophone.core.model.format.DistanceInput
import es.topophone.core.model.geometry.DistanceMode
import es.topophone.core.model.project.AngularUnit
import es.topophone.core.model.project.LinearUnit
import es.topophone.core.model.project.ProjectCrs
import es.topophone.core.survey.cogo.ClosureError
import es.topophone.core.survey.cogo.CogoScale
import es.topophone.core.survey.cogo.Traverse
import es.topophone.core.survey.cogo.TraverseResult
import es.topophone.core.survey.cogo.TraverseStep
import es.topophone.core.survey.cogo.TraverseStop
import es.topophone.core.survey.geometry.GeometryNotice
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
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal const val SLOT_START = "start"
internal const val SLOT_REFERENCE = "reference"
internal const val SLOT_CLOSURE = "closure"
private const val STOP_TIMEOUT_MS = 5_000L

/** Lo que se escribe en un tramo, tal cual (S038/RF-10). */
data class LegText(val kind: LegKind = LegKind.AZIMUTH, val value: String = "", val distance: String = "", val dHeight: String = "")

/**
 * Un tramo con sus textos, lo que tiene de no válido (un campo con texto que no es un número, RF-4) y el punto que da, si ya se calculó
 * (RF-11). [blocked] es true si el cálculo se paró en este tramo o antes (RF-31).
 */
data class TraverseLegUi(
    val number: Int,
    val kind: LegKind,
    val valueText: String,
    val distanceText: String,
    val dHeightText: String,
    val valueInvalid: Boolean,
    val distanceInvalid: Boolean,
    val dHeightInvalid: Boolean,
    val step: TraverseStep?,
    val height: Double?,
)

/** Lo que muestra la poligonal (S038/RF-10 a RF-12, RF-31). */
data class TraverseUi(
    val loaded: Boolean = false,
    val mode: DistanceMode = DistanceMode.GRID,
    val linearUnit: LinearUnit = LinearUnit.METRE,
    val angularUnit: AngularUnit = AngularUnit.DMS,
    val start: CogoSlotValue = CogoSlotValue.Empty,
    val reference: CogoSlotValue = CogoSlotValue.Empty,
    val closurePoint: CogoSlotValue = CogoSlotValue.Empty,
    val startSelection: CogoSelection? = null,
    val referenceSelection: CogoSelection? = null,
    val closureSelection: CogoSelection? = null,
    val picker: CogoPickerUi = CogoPickerUi(),
    val legs: List<TraverseLegUi> = emptyList(),
    /** Algún tramo es por ángulo o por deflexión: hace falta el punto de referencia (S038/RF-10). */
    val needsReference: Boolean = false,
    /** El tramo (desde 1) en el que se paró el cálculo, y por qué (S038/RF-31). */
    val stoppedAt: Int? = null,
    val stopReason: TraverseStop? = null,
    val closure: ClosureError? = null,
    val totalGridLength: Double = 0.0,
    val notices: Set<GeometryNotice> = emptySet(),
)

/**
 * La poligonal (S038/RF-10 a RF-12, RF-31): un punto de inicio, uno de referencia y uno de cierre opcionales, y los tramos que se
 * añaden, editan y quitan; cada punto se calcula en vivo. Guardar y el borrador son de otras partes (RF-19, RF-26).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class TraverseViewModel @Inject constructor(
    points: PointRepository,
    private val projects: ProjectRepository,
    private val preferences: GeometryPreferences,
) : ViewModel() {
    private val projectId = MutableStateFlow<String?>(null)
    val inputs = CogoInputs(points, viewModelScope)

    private val legsState = MutableStateFlow<List<LegText>>(emptyList())

    /** Los tramos escritos, para guardarlos en el borrador. */
    val legTexts: StateFlow<List<LegText>> = legsState

    private data class Context(val crs: ProjectCrs, val linearUnit: LinearUnit, val angularUnit: AngularUnit)

    private val context = projectId.flatMapLatest { id ->
        if (id == null) flowOf(null) else projects.observe(id).map { it?.let { p -> Context(p.crs, p.linearUnit, p.angularUnit) } }
    }

    private val inputsState = combine(
        inputs.selections,
        inputs.values(context.map { it?.linearUnit ?: LinearUnit.METRE }.distinctUntilChanged()),
        inputs.picker(projectId),
    ) { selections, values, picker -> Triple(selections, values, picker) }

    val state: StateFlow<TraverseUi> = combine(context, preferences.distanceMode, legsState, inputsState) { context, mode, texts, inputs ->
        if (context == null) return@combine TraverseUi(mode = mode)
        val (selections, values, picker) = inputs
        val start = values[SLOT_START] ?: CogoSlotValue.Empty
        val reference = values[SLOT_REFERENCE] ?: CogoSlotValue.Empty
        val closurePoint = values[SLOT_CLOSURE] ?: CogoSlotValue.Empty
        val parsed = texts.map { parse(it, context) }
        var result: TraverseResult? = null
        if (start is CogoSlotValue.Found && texts.isNotEmpty()) {
            val scale = CogoScale.forFirst(start.point, context.crs, mode)
            result = Traverse.compute(
                start.point,
                (reference as? CogoSlotValue.Found)?.point,
                parsed.map { it.leg },
                scale,
                (closurePoint as? CogoSlotValue.Found)?.point,
            )
        }
        val steps = result?.steps.orEmpty().associateBy { it.leg }
        TraverseUi(
            loaded = true,
            mode = mode,
            linearUnit = context.linearUnit,
            angularUnit = context.angularUnit,
            start = start,
            reference = reference,
            closurePoint = closurePoint,
            startSelection = selections[SLOT_START],
            referenceSelection = selections[SLOT_REFERENCE],
            closureSelection = selections[SLOT_CLOSURE],
            picker = picker,
            legs = texts.mapIndexed { i, text ->
                val p = parsed[i]
                val step = steps[i + 1]
                TraverseLegUi(
                    number = i + 1,
                    kind = text.kind,
                    valueText = text.value,
                    distanceText = text.distance,
                    dHeightText = text.dHeight,
                    valueInvalid = text.value.isNotBlank() && p.leg.angleRad == null,
                    distanceInvalid = text.distance.isNotBlank() && p.leg.distanceM == null,
                    dHeightInvalid = text.dHeight.isNotBlank() && p.leg.dHeightM == null,
                    step = step,
                    height = step?.height,
                )
            },
            needsReference = texts.any { it.kind != LegKind.AZIMUTH },
            stoppedAt = result?.stoppedAt,
            stopReason = result?.stopReason,
            closure = result?.closure,
            totalGridLength = result?.totalGridLength ?: 0.0,
            notices = result?.notices.orEmpty(),
        )
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), TraverseUi())

    private class Parsed(val leg: TraverseLeg)

    private fun parse(text: LegText, context: Context): Parsed {
        val kind = when (text.kind) {
            LegKind.AZIMUTH -> DirectionKind.AZIMUTH
            LegKind.ANGLE_RIGHT, LegKind.ANGLE_LEFT -> DirectionKind.ANGLE
            LegKind.DEFLECTION_RIGHT, LegKind.DEFLECTION_LEFT -> DirectionKind.DEFLECTION
        }
        return Parsed(
            TraverseLeg(
                text.kind,
                DirectionInput.parse(text.value, context.angularUnit, kind),
                DistanceInput.parse(text.distance, context.linearUnit),
                DistanceInput.parseSigned(text.dHeight, context.linearUnit),
            ),
        )
    }

    fun start(id: String) {
        projectId.value = id
    }

    // --- Tramos (S038/RF-10) --------------------------------------------------------------------------------------------------------

    fun addLeg(kind: LegKind = LegKind.AZIMUTH) = legsState.update { it + LegText(kind) }

    fun removeLeg(index: Int) = legsState.update { if (index in it.indices) it.filterIndexed { i, _ -> i != index } else it }

    fun setKind(index: Int, kind: LegKind) = edit(index) { it.copy(kind = kind) }

    fun setValue(index: Int, text: String) = edit(index) { it.copy(value = text) }

    fun setDistance(index: Int, text: String) = edit(index) { it.copy(distance = text) }

    fun setDHeight(index: Int, text: String) = edit(index) { it.copy(dHeight = text) }

    private fun edit(index: Int, change: (LegText) -> LegText) = legsState.update { list ->
        if (index in list.indices) list.mapIndexed { i, leg -> if (i == index) change(leg) else leg } else list
    }

    /** Sustituye todos los tramos, al recuperar un borrador (S038/RF-27). */
    fun restoreLegs(legs: List<LegText>) {
        legsState.value = legs
    }

    /** Quita el punto de referencia o el de cierre. */
    fun clear(slot: String) = inputs.select(slot, null)

    /** Elige cuadrícula o terreno, para todos los proyectos (S038/RF-5). */
    fun setMode(mode: DistanceMode) {
        viewModelScope.launch { preferences.setDistanceMode(mode) }
    }
}
