package es.topophone.app.ui.cogo

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import es.topophone.core.model.cogo.CogoOperation
import es.topophone.core.model.cogo.CogoRef
import es.topophone.core.model.cogo.DraftPoint
import es.topophone.core.model.cogo.LegKind
import es.topophone.core.model.cogo.TraverseDraft
import es.topophone.core.model.cogo.TraverseLeg
import es.topophone.core.model.format.AngleText
import es.topophone.core.model.format.DirectionInput
import es.topophone.core.model.format.DirectionKind
import es.topophone.core.model.format.DistanceInput
import es.topophone.core.model.format.LengthText
import es.topophone.core.model.geometry.DistanceMode
import es.topophone.core.model.project.AngularUnit
import es.topophone.core.model.project.LinearUnit
import es.topophone.core.model.project.ProjectCrs
import es.topophone.core.survey.TerrainView
import es.topophone.core.survey.cogo.ClosureError
import es.topophone.core.survey.cogo.CogoScale
import es.topophone.core.survey.cogo.Traverse
import es.topophone.core.survey.cogo.TraverseResult
import es.topophone.core.survey.cogo.TraverseStep
import es.topophone.core.survey.cogo.TraverseStop
import es.topophone.core.survey.geometry.GeometryNotice
import es.topophone.data.cogo.CogoRepository
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
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
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
    /** Marcado para guardarlo como punto del proyecto (S038/RF-19). */
    val marked: Boolean = false,
)

/** Lo que muestra la poligonal (S038/RF-10 a RF-12, RF-31). */
data class TraverseUi(
    val loaded: Boolean = false,
    val mode: DistanceMode = DistanceMode.GRID,
    /** S058/RF-9: el proyecto está en terreno; se calcula en terreno y no hay elección Cuadrícula/Terreno. */
    val terrain: Boolean = false,
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
    /** Hay algo escrito: se guarda como borrador del proyecto y se puede descartar (S038/RF-26, RF-27). */
    val hasDraft: Boolean = false,
    /** Cuántos puntos calculados hay marcados para guardar (S038/RF-19). */
    val markedCount: Int = 0,
)

/**
 * La poligonal (S038/RF-10 a RF-12, RF-31): un punto de inicio, uno de referencia y uno de cierre opcionales, y los tramos que se
 * añaden, editan y quitan; cada punto se calcula en vivo. Guardar y el borrador son de otras partes (RF-19, RF-26).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class TraverseViewModel @Inject constructor(
    private val points: PointRepository,
    private val projects: ProjectRepository,
    private val preferences: GeometryPreferences,
    private val cogo: CogoRepository,
) : ViewModel() {
    private val projectId = MutableStateFlow<String?>(null)
    val inputs = CogoInputs(points, viewModelScope)

    private val legsState = MutableStateFlow<List<LegText>>(emptyList())
    private val marked = MutableStateFlow<Set<Int>>(emptySet())

    /** Los tramos escritos, para guardarlos en el borrador. */
    val legTexts: StateFlow<List<LegText>> = legsState

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

    private val inputsState = combine(
        inputs.selections,
        inputs.values(
            context.map { it?.linearUnit ?: LinearUnit.METRE }.distinctUntilChanged(),
            context.map { it?.terrain ?: TerrainView.GRID },
        ),
        inputs.picker(projectId),
    ) { selections, values, picker -> Triple(selections, values, picker) }

    val state: StateFlow<TraverseUi> = combine(context, preferences.distanceMode, legsState, inputsState, marked) {
            context,
            chosenMode,
            texts,
            inputs,
            marks,
        ->
        if (context == null) return@combine TraverseUi(mode = chosenMode)
        val mode = if (context.terrain.isTerrain) DistanceMode.GRID else chosenMode // S058/RF-9
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
            terrain = context.terrain.isTerrain,
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
                    marked = step != null && (i + 1) in marks,
                )
            },
            needsReference = texts.any { it.kind != LegKind.AZIMUTH },
            stoppedAt = result?.stoppedAt,
            stopReason = result?.stopReason,
            closure = result?.closure,
            totalGridLength = result?.totalGridLength ?: 0.0,
            notices = result?.notices.orEmpty(),
            hasDraft = texts.isNotEmpty() || selections.isNotEmpty(),
            markedCount = marks.count { it in steps },
        )
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), TraverseUi())

    private class Parsed(val leg: TraverseLeg)

    private fun parse(text: LegText, context: Context): Parsed = parse(text, context.linearUnit, context.angularUnit)

    private fun parse(text: LegText, linearUnit: LinearUnit, angularUnit: AngularUnit): Parsed {
        val kind = when (text.kind) {
            LegKind.AZIMUTH -> DirectionKind.AZIMUTH
            LegKind.ANGLE_RIGHT, LegKind.ANGLE_LEFT -> DirectionKind.ANGLE
            LegKind.DEFLECTION_RIGHT, LegKind.DEFLECTION_LEFT -> DirectionKind.DEFLECTION
        }
        return Parsed(
            TraverseLeg(
                text.kind,
                DirectionInput.parse(text.value, angularUnit, kind),
                DistanceInput.parse(text.distance, linearUnit),
                DistanceInput.parseSigned(text.dHeight, linearUnit),
            ),
        )
    }

    /** Mientras no se ha recuperado el borrador no se guarda nada: si no, lo escrito antes se pisaría con una poligonal vacía. */
    private val restored = MutableStateFlow(false)

    init {
        // Cada cambio de lo escrito se guarda al instante (S038/RF-26): la cola se conflata, de modo que siempre acaba guardado lo último.
        viewModelScope.launch {
            combine(legsState, inputs.selections, context.filterNotNull(), restored) { legs, selections, context, ok ->
                if (ok) snapshot(legs, selections, context) else null
            }.filterNotNull().distinctUntilChanged().conflate().collect { cogo.saveDraft(it) }
        }
    }

    fun start(id: String) {
        if (projectId.value == id) return
        projectId.value = id
        viewModelScope.launch {
            val context = context.filterNotNull().first()
            cogo.draft(id).first()?.let { restore(it, context) }
            restored.value = true
        }
    }

    /** Lo escrito, en SI, como borrador del proyecto (S038/RF-26): lo que no es un número queda en null. */
    private fun snapshot(legs: List<LegText>, selections: Map<String, CogoSelection>, context: Context): TraverseDraft {
        fun point(slot: String): DraftPoint? = when (val selection = selections[slot]) {
            null -> null
            is CogoSelection.OfProject -> DraftPoint.OfProject(selection.pointId, selection.name)
            is CogoSelection.Typed -> {
                val east = DistanceInput.parseSigned(selection.easting, context.linearUnit)
                val north = DistanceInput.parseSigned(selection.northing, context.linearUnit)
                if (east == null || north == null) null else DraftPoint.Typed(east, north)
            }
        }
        return TraverseDraft(
            projectId = checkNotNull(projectId.value),
            start = point(SLOT_START),
            reference = point(SLOT_REFERENCE),
            closure = point(SLOT_CLOSURE),
            legs = legs.map { parse(it, context).leg },
            updatedAtMs = 0L,
        )
    }

    /** Vuelve a poner en pantalla lo guardado: los puntos del proyecto se leen con su estado de ahora (S038/RF-27, RF-28). */
    private fun restore(draft: TraverseDraft, context: Context) {
        fun selection(point: DraftPoint?): CogoSelection? = when (point) {
            null -> null
            is DraftPoint.OfProject -> CogoSelection.OfProject(point.pointId, point.name)
            is DraftPoint.Typed ->
                CogoSelection.Typed(LengthText.of(point.easting, context.linearUnit), LengthText.of(point.northing, context.linearUnit))
        }
        val selections = buildMap {
            selection(draft.start)?.let { put(SLOT_START, it) }
            selection(draft.reference)?.let { put(SLOT_REFERENCE, it) }
            selection(draft.closure)?.let { put(SLOT_CLOSURE, it) }
        }
        inputs.restore(selections)
        legsState.value = draft.legs.map { leg ->
            LegText(
                kind = leg.kind,
                value = leg.angleRad?.let { AngleText.of(it, context.angularUnit) }.orEmpty(),
                distance = leg.distanceM?.let { LengthText.of(it, context.linearUnit) }.orEmpty(),
                dHeight = leg.dHeightM?.let { LengthText.of(it, context.linearUnit) }.orEmpty(),
            )
        }
    }

    /** Descarta el borrador: vacía lo escrito y lo borra del proyecto (S038/RF-27). */
    fun discardDraft() {
        legsState.value = emptyList()
        marked.value = emptySet()
        inputs.restore(emptyMap())
        val id = projectId.value ?: return
        viewModelScope.launch { cogo.clearDraft(id) }
    }

    // --- Tramos (S038/RF-10) --------------------------------------------------------------------------------------------------------

    fun addLeg(kind: LegKind = LegKind.AZIMUTH) = legsState.update { it + LegText(kind) }

    fun removeLeg(index: Int) {
        legsState.update { if (index in it.indices) it.filterIndexed { i, _ -> i != index } else it }
        // Las marcas siguen a sus tramos: el quitado pierde la suya y los de después bajan un número.
        marked.update { set -> set.filter { it != index + 1 }.map { if (it > index + 1) it - 1 else it }.toSet() }
    }

    /** Marca o desmarca el punto del tramo [number] (desde 1) para guardarlo (S038/RF-19). */
    fun toggleMarked(number: Int) = marked.update { if (number in it) it - number else it + number }

    /** Guardar los puntos marcados; al guardar bien se desmarcan y, si eran todos, el borrador se vacía y la pantalla también (S038/RF-30). */
    val save = CogoSaveController(
        points,
        cogo,
        projects,
        viewModelScope,
        onSaved = { cleared ->
            marked.value = emptySet()
            if (cleared) {
                legsState.value = emptyList()
                inputs.restore(emptyMap())
            }
        },
    )

    /** Abre la hoja de guardado con los puntos marcados, cada uno con los tramos que llevan a él y sus puntos de entrada (S038/RF-19, RF-22). */
    fun openSave() {
        val ui = state.value
        val id = projectId.value ?: return
        val start = ui.start as? CogoSlotValue.Found ?: return
        val chosen = ui.legs.filter { it.marked && it.step != null }
        if (chosen.isEmpty()) return
        val legs = legsState.value.map { parse(it, ui.linearUnit, ui.angularUnit).leg }
        val entries = listOfNotNull(
            start.toEntry("start"),
            (ui.reference as? CogoSlotValue.Found)?.toEntry("reference"),
            (ui.closurePoint as? CogoSlotValue.Found)?.toEntry("closure"),
        )
        val results = chosen.map { leg ->
            val step = checkNotNull(leg.step)
            CogoResult(
                "leg${leg.number}",
                step.easting,
                step.northing,
                leg.height,
                CogoRef(CogoOperation.TRAVERSE, entries, legs = legs.take(leg.number)),
            )
        }
        // El borrador se vacía solo si se guardan todos los tramos y todos están calculados: si queda alguno por completar, se conserva.
        val clearDraft = chosen.size == ui.legs.size
        save.open(id, results, CogoOperation.TRAVERSE, clearDraft)
    }

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
        marked.value = emptySet()
    }

    /** Quita el punto de referencia o el de cierre. */
    fun clear(slot: String) = inputs.select(slot, null)

    /** Elige cuadrícula o terreno, para todos los proyectos (S038/RF-5). */
    fun setMode(mode: DistanceMode) {
        viewModelScope.launch { preferences.setDistanceMode(mode) }
    }
}
