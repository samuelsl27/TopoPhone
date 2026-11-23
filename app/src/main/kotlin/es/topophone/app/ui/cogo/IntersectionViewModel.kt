package es.topophone.app.ui.cogo

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import es.topophone.core.model.cogo.CogoOperation
import es.topophone.core.model.cogo.CogoRef
import es.topophone.core.model.cogo.CogoValue
import es.topophone.core.model.cogo.IntersectionSide
import es.topophone.core.model.format.DirectionInput
import es.topophone.core.model.format.DirectionKind
import es.topophone.core.model.format.DistanceInput
import es.topophone.core.model.geometry.DistanceMode
import es.topophone.core.model.project.AngularUnit
import es.topophone.core.model.project.LinearUnit
import es.topophone.core.model.project.ProjectCrs
import es.topophone.core.survey.TerrainView
import es.topophone.core.survey.cogo.CogoPoint
import es.topophone.core.survey.cogo.CogoScale
import es.topophone.core.survey.cogo.Intersection
import es.topophone.core.survey.cogo.IntersectionResult
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
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal const val SLOT_FIRST = "first"
internal const val SLOT_SECOND = "second"
private const val STOP_TIMEOUT_MS = 5_000L

/** Las tres intersecciones y la operación que se guarda (S038/RF-13). */
enum class IntersectionKind(val operation: CogoOperation) {
    DISTANCE_DISTANCE(CogoOperation.INTERSECTION_DISTANCE_DISTANCE),
    DIRECTION_DIRECTION(CogoOperation.INTERSECTION_DIRECTION_DIRECTION),
    DISTANCE_DIRECTION(CogoOperation.INTERSECTION_DISTANCE_DIRECTION),
}

/**
 * Una solución de la intersección con lo que el usuario añade: la altura opcional que se escribe (S038/RF-18) y si se marca para
 * guardarla (RF-19). [height] es null si el texto está vacío o no es un número ([heightInvalid] distingue los dos casos).
 */
data class IntersectionSolutionUi(
    val side: IntersectionSide,
    val easting: Double,
    val northing: Double,
    val distance1: Double,
    val distance2: Double,
    val behind: Boolean,
    val heightText: String,
    val height: Double?,
    val heightInvalid: Boolean,
    val marked: Boolean,
)

/** Lo que muestra la intersección (S038/RF-13 a RF-18). */
data class IntersectionUi(
    val loaded: Boolean = false,
    val kind: IntersectionKind = IntersectionKind.DISTANCE_DISTANCE,
    val mode: DistanceMode = DistanceMode.GRID,
    /** S058/RF-9: el proyecto está en terreno; se calcula en terreno y no hay elección Cuadrícula/Terreno. */
    val terrain: Boolean = false,
    val linearUnit: LinearUnit = LinearUnit.METRE,
    val angularUnit: AngularUnit = AngularUnit.DMS,
    val first: CogoSlotValue = CogoSlotValue.Empty,
    val second: CogoSlotValue = CogoSlotValue.Empty,
    val firstSelection: CogoSelection? = null,
    val secondSelection: CogoSelection? = null,
    val picker: CogoPickerUi = CogoPickerUi(),
    val value1Text: String = "",
    val value2Text: String = "",
    val value1Invalid: Boolean = false,
    val value2Invalid: Boolean = false,
    /** Null hasta que están los dos puntos y las dos condiciones válidas. */
    val result: IntersectionResult? = null,
    val solutions: List<IntersectionSolutionUi> = emptyList(),
    val notices: Set<GeometryNotice> = emptySet(),
    /** Cuántas soluciones hay marcadas y si se pueden guardar (alguna marcada y ninguna con la altura mal escrita, S038/RF-18, RF-19). */
    val markedCount: Int = 0,
    val canSave: Boolean = false,
)

/**
 * Intersección de distancia–distancia, dirección–dirección o distancia–dirección entre dos puntos (S038/RF-13 a RF-18). Calcula en vivo;
 * guardar los puntos es de [CogoSaveController] (S038/RF-19).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class IntersectionViewModel @Inject constructor(
    points: PointRepository,
    private val projects: ProjectRepository,
    private val preferences: GeometryPreferences,
    cogo: CogoRepository,
) : ViewModel() {
    private val projectId = MutableStateFlow<String?>(null)
    val inputs = CogoInputs(points, viewModelScope)

    private data class Own(
        val kind: IntersectionKind = IntersectionKind.DISTANCE_DISTANCE,
        val value1: String = "",
        val value2: String = "",
        val heights: Map<IntersectionSide, String> = emptyMap(),
        val marked: Set<IntersectionSide> = emptySet(),
    )

    private val own = MutableStateFlow(Own())

    /** Guardar las soluciones marcadas como puntos del proyecto (S038/RF-19 a RF-21). */
    val save = CogoSaveController(points, cogo, projects, viewModelScope, onSaved = { own.update { it.copy(marked = emptySet()) } })

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

    val state: StateFlow<IntersectionUi> = combine(context, preferences.distanceMode, own, inputsState) {
            context,
            chosenMode,
            own,
            inputs,
        ->
        if (context == null) return@combine IntersectionUi(mode = chosenMode)
        val mode = if (context.terrain.isTerrain) DistanceMode.GRID else chosenMode // S058/RF-9
        val (selections, values, picker) = inputs
        val first = values[SLOT_FIRST] ?: CogoSlotValue.Empty
        val second = values[SLOT_SECOND] ?: CogoSlotValue.Empty
        val (value1, value2) = parse(own, context)
        val calculated = if (first is CogoSlotValue.Found && second is CogoSlotValue.Found && value1 != null && value2 != null) {
            calculate(own.kind, first.point, second.point, value1, value2, CogoScale.forFirst(first.point, context.crs, mode))
        } else {
            null
        }
        val solutionsUi = solutionsOf(calculated?.first, own, context.linearUnit)
        IntersectionUi(
            loaded = true,
            kind = own.kind,
            mode = mode,
            terrain = context.terrain.isTerrain,
            linearUnit = context.linearUnit,
            angularUnit = context.angularUnit,
            first = first,
            second = second,
            firstSelection = selections[SLOT_FIRST],
            secondSelection = selections[SLOT_SECOND],
            picker = picker,
            value1Text = own.value1,
            value2Text = own.value2,
            value1Invalid = own.value1.isNotBlank() && value1 == null,
            value2Invalid = own.value2.isNotBlank() && value2 == null,
            result = calculated?.first,
            solutions = solutionsUi,
            notices = calculated?.second.orEmpty(),
            markedCount = solutionsUi.count { it.marked },
            canSave = solutionsUi.any { it.marked } && solutionsUi.none { it.marked && it.heightInvalid },
        )
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), IntersectionUi())

    private fun parse(own: Own, context: Context): Pair<Double?, Double?> =
        parseValues(own.kind, own.value1, own.value2, context.linearUnit, context.angularUnit)

    private fun parseValues(
        kind: IntersectionKind,
        text1: String,
        text2: String,
        linear: LinearUnit,
        angular: AngularUnit,
    ): Pair<Double?, Double?> = when (kind) {
        IntersectionKind.DISTANCE_DISTANCE -> DistanceInput.parse(text1, linear) to DistanceInput.parse(text2, linear)
        IntersectionKind.DIRECTION_DIRECTION ->
            DirectionInput.parse(text1, angular, DirectionKind.AZIMUTH) to DirectionInput.parse(text2, angular, DirectionKind.AZIMUTH)
        IntersectionKind.DISTANCE_DIRECTION ->
            DistanceInput.parse(text1, linear) to DirectionInput.parse(text2, angular, DirectionKind.AZIMUTH)
    }

    /** Abre la hoja de guardado con las soluciones marcadas, cada una con lo escrito y sus puntos de entrada (S038/RF-19, RF-22). */
    fun openSave() {
        val ui = state.value
        val id = projectId.value ?: return
        val first = ui.first as? CogoSlotValue.Found ?: return
        val second = ui.second as? CogoSlotValue.Found ?: return
        if (!ui.canSave) return
        val (v1, v2) = parseValues(ui.kind, ui.value1Text, ui.value2Text, ui.linearUnit, ui.angularUnit)
        if (v1 == null || v2 == null) return
        val written = when (ui.kind) {
            IntersectionKind.DISTANCE_DISTANCE -> listOf(CogoValue("distance1", v1), CogoValue("distance2", v2))
            IntersectionKind.DIRECTION_DIRECTION -> listOf(CogoValue("azimuth1", v1), CogoValue("azimuth2", v2))
            IntersectionKind.DISTANCE_DIRECTION -> listOf(CogoValue("distance1", v1), CogoValue("azimuth2", v2))
        } + CogoValue("ground", if (ui.mode == DistanceMode.GROUND) 1.0 else 0.0)
        val entries = listOf(first.toEntry("first"), second.toEntry("second"))
        val results = ui.solutions.filter { it.marked }.map {
            CogoResult(
                it.side.name,
                it.easting,
                it.northing,
                it.height,
                CogoRef(ui.kind.operation, entries, values = written, solution = it.side),
            )
        }
        save.open(id, results, ui.kind.operation, clearDraft = false)
    }

    private fun calculate(
        kind: IntersectionKind,
        first: CogoPoint,
        second: CogoPoint,
        value1: Double,
        value2: Double,
        scale: CogoScale,
    ): Pair<IntersectionResult, Set<GeometryNotice>> {
        val result = when (kind) {
            IntersectionKind.DISTANCE_DISTANCE -> Intersection.distanceDistance(first, second, value1, value2, scale)
            IntersectionKind.DIRECTION_DIRECTION -> Intersection.directionDirection(first, second, value1, value2, scale)
            IntersectionKind.DISTANCE_DIRECTION -> Intersection.distanceDirection(first, second, value1, value2, scale)
        }
        return result to scale.notices
    }

    private fun solutionsOf(result: IntersectionResult?, own: Own, unit: LinearUnit): List<IntersectionSolutionUi> {
        if (result !is IntersectionResult.Solutions) return emptyList()
        return result.list.map { solution ->
            val text = own.heights[solution.side].orEmpty()
            val height = DistanceInput.parseSigned(text, unit)
            IntersectionSolutionUi(
                side = solution.side,
                easting = solution.easting,
                northing = solution.northing,
                distance1 = solution.distance1,
                distance2 = solution.distance2,
                behind = solution.behind,
                heightText = text,
                height = height,
                heightInvalid = text.isNotBlank() && height == null,
                marked = solution.side in own.marked,
            )
        }
    }

    fun start(id: String) {
        projectId.value = id
    }

    /** Elige una de las tres intersecciones; las condiciones escritas se borran porque cambian de clase. */
    fun setKind(kind: IntersectionKind) = own.update { if (it.kind == kind) it else Own(kind = kind) }

    fun setValue1(text: String) = own.update { it.copy(value1 = text) }

    fun setValue2(text: String) = own.update { it.copy(value2 = text) }

    /** La altura opcional de una solución, en la unidad del proyecto (S038/RF-18). */
    fun setHeight(side: IntersectionSide, text: String) = own.update { it.copy(heights = it.heights + (side to text)) }

    /** Marca o desmarca una solución para guardarla (S038/RF-19). */
    fun toggleMarked(side: IntersectionSide) = own.update {
        it.copy(marked = if (side in it.marked) it.marked - side else it.marked + side)
    }

    /** Elige cuadrícula o terreno, para todos los proyectos (S038/RF-5). */
    fun setMode(mode: DistanceMode) {
        viewModelScope.launch { preferences.setDistanceMode(mode) }
    }
}
