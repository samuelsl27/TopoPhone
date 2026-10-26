package es.topophone.app.ui.stakeout

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.Lazy
import dagger.hilt.android.lifecycle.HiltViewModel
import es.topophone.app.receiver.CurrentTilt
import es.topophone.app.ui.cad.DesignSelection
import es.topophone.app.ui.collector.MeasurementInProgress
import es.topophone.core.model.format.NumberInput
import es.topophone.core.model.log.AppLog
import es.topophone.core.model.log.LogCategory
import es.topophone.core.model.point.EntryForm
import es.topophone.core.model.point.HeadingChoice
import es.topophone.core.model.point.HeightKind
import es.topophone.core.model.point.NaturalOrder
import es.topophone.core.model.point.PointSearch
import es.topophone.core.model.point.PointSummary
import es.topophone.core.model.point.StakeMode
import es.topophone.core.model.point.StakeSource
import es.topophone.core.model.point.StakeoutSettings
import es.topophone.core.model.point.TiltMode
import es.topophone.core.model.point.TypedCoordinates
import es.topophone.core.model.project.LinearUnit
import es.topophone.core.model.project.Project
import es.topophone.core.survey.ManualCoordinates
import es.topophone.core.survey.ManualResult
import es.topophone.core.survey.PoleHeight
import es.topophone.core.survey.ProjectPositionCalculator
import es.topophone.data.point.CollectorPreferences
import es.topophone.data.point.PointRepository
import es.topophone.data.point.StakeoutPreferences
import es.topophone.data.project.ProjectRepository
import es.topophone.data.receiver.ReceiverPreferences
import es.topophone.receiver.platform.Alerts
import es.topophone.receiver.session.ConnectionState
import es.topophone.receiver.session.ReceiverSession
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Lo que se guarda con el punto replanteado de S019 (RF-13): la cota de diseño usada y las dos tolerancias vigentes, en metros.
 * [elevationM] es null si no hay cota de diseño.
 */
data class StakeSaveInfo(val elevationM: Double?, val toleranceHM: Double, val toleranceVM: Double)

/**
 * Lo que muestra el replanteo (S018/RF-1 a RF-8, RF-13 a RF-19, RF-30, RF-35; S019/RF-1 a RF-4, RF-10, RF-13, RF-16).
 *
 * @property notFound el punto que se pidió no existe o está en la papelera.
 * @property measuring hay una medida en marcha: no se cambia de punto ni de ajustes (RF-30).
 * @property designElevationM la cota de diseño que se replantea: la escrita o la del punto de diseño; null si no hay (S019/RF-1).
 * @property elevationOverridden la cota de diseño es la que escribió el usuario y no la del punto (S019/RF-2).
 * @property saveInfo lo que se guardará con el punto replanteado (S019/RF-13).
 */
data class StakeoutUiState(
    val project: Project? = null,
    val target: StakeTarget? = null,
    val hasPrevious: Boolean = false,
    val hasNext: Boolean = false,
    val settings: StakeoutSettings = StakeoutSettings(),
    val guidance: GuidanceState = GuidanceState.Off(GuidanceOff.NO_POSITION),
    val measuring: Boolean = false,
    val notFound: Boolean = false,
    val designElevationM: Double? = null,
    val elevationOverridden: Boolean = false,
    val saveInfo: StakeSaveInfo? = null,
)

/**
 * El replanteo de un punto del proyecto (S018): elige el punto de diseño y recorre los demás con "Siguiente" y "Anterior", lleva
 * los ajustes y da a [StakeoutController] las épocas de la sesión. Medir y guardar lo hace el recolector con el contexto de
 * replanteo (A5); aquí solo se sabe si hay una medida en marcha.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class StakeoutViewModel @Inject constructor(
    private val projects: ProjectRepository,
    private val points: PointRepository,
    private val stakeoutPreferences: StakeoutPreferences,
    private val collectorPreferences: CollectorPreferences,
    private val receiverPreferences: ReceiverPreferences,
    private val session: ReceiverSession,
    alerts: Alerts,
    private val calculator: Lazy<ProjectPositionCalculator>,
    private val inProgress: MeasurementInProgress = MeasurementInProgress(),
    private val log: AppLog = AppLog.NONE,
    private val tilt: CurrentTilt = CurrentTilt.NONE,
) : ViewModel() {
    private val controller = StakeoutController(alerts, log)

    private val projectId = MutableStateFlow<String?>(null)
    private val targetId = MutableStateFlow<String?>(null)
    private val searchText = MutableStateFlow("")
    private val notFound = MutableStateFlow(false)
    private val target = MutableStateFlow<StakeTarget?>(null)

    /** El punto de diseño es un punto o un centro de bloque de un dibujo (S041/RF-1): no sale de la lista de puntos. */
    @Volatile private var fromDrawing = false

    /** La cota de diseño escrita por el usuario, en metros; solo vive aquí y se descarta al cambiar de punto o cerrar (S019/RF-2, RF-3). */
    private val elevationOverride = MutableStateFlow<Double?>(null)

    @Volatile private var project: Project? = null

    @Volatile private var settings = StakeoutSettings()

    @Volatile private var fixOnly = true

    @Volatile private var poleHeightM = PoleHeight.DEFAULT_M

    private val projectFlow = projectId.flatMapLatest { id -> if (id == null) flowOf(null) else projects.observe(id) }

    /** Los puntos del proyecto que se recorren con "Siguiente" y "Anterior": los de la lista con su búsqueda, por nombre en orden natural. */
    private val ordered: StateFlow<List<PointSummary>> = combine(
        projectId.flatMapLatest { id -> if (id == null) flowOf(emptyList()) else points.summaries(id) },
        searchText,
    ) { list, text -> list.filter { PointSearch.matches(it, text) }.sortedWith(compareBy(NaturalOrder) { it.name }) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** Lo que cambia con el panel abierto y no es ni el proyecto, ni el punto, ni la lista (agrupado para el `combine`). */
    private data class Live(
        val settings: StakeoutSettings,
        val guidance: GuidanceState,
        val measuring: Boolean,
        val notFound: Boolean,
        val elevationOverride: Double?,
    )

    val state: StateFlow<StakeoutUiState> = combine(
        projectFlow,
        target,
        ordered,
        combine(stakeoutPreferences.settings, controller.state, inProgress.active, notFound, elevationOverride, ::Live),
    ) { project, target, list, live ->
        val index = target?.let { t -> list.indexOfFirst { it.id == t.id } } ?: -1
        val elevation = live.elevationOverride ?: target?.coordinates?.height
        StakeoutUiState(
            project = project,
            target = target,
            hasPrevious = index > 0,
            hasNext = index in 0 until list.lastIndex,
            settings = live.settings,
            guidance = live.guidance,
            measuring = live.measuring,
            notFound = live.notFound,
            designElevationM = elevation,
            elevationOverridden = live.elevationOverride != null,
            saveInfo = target?.let { StakeSaveInfo(elevation, live.settings.validToleranceM, live.settings.validToleranceVM) },
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, StakeoutUiState())

    init {
        viewModelScope.launch { projectFlow.collect { project = it } }
        viewModelScope.launch { stakeoutPreferences.settings.collect { settings = it } }
        viewModelScope.launch { collectorPreferences.settings.collect { fixOnly = it.precision.fixOnly } }
        viewModelScope.launch { receiverPreferences.poleHeightM.collect { poleHeightM = it } }
        viewModelScope.launch {
            session.epochs.collect { received ->
                val context = context() ?: return@collect
                controller.onEpoch(received.epoch, context)
            }
        }
        // S018/RF-25: sin épocas (sin datos o conexión perdida) la guía se apaga hasta que vuelvan.
        viewModelScope.launch {
            session.state.collect { snapshot ->
                if (snapshot.connection !is ConnectionState.Receiving && target.value != null) controller.onSilence(context())
            }
        }
        viewModelScope.launch {
            targetId.flatMapLatest { id -> if (id == null) flowOf(null) else points.point(id) }.collect { point ->
                if (fromDrawing) return@collect
                if (point == null || point.deletedAtMs != null) {
                    // S018/RF-1: no existe o está en la papelera.
                    target.value = null
                    notFound.value = targetId.value != null
                } else {
                    notFound.value = false
                    target.value = StakeTarget(point.id, point.name, point.description, point.coordinates)
                }
            }
        }
    }

    /** Abre el replanteo con el punto [pointId] como punto de diseño; [query] es la búsqueda de la lista de objetos, si viene de ella (S018/RF-1, RF-3). */
    fun open(projectId: String, pointId: String, query: String = "") {
        this.projectId.value = projectId
        searchText.value = query
        goTo(pointId)
        log.info(LogCategory.POINTS, "Replanteo abierto") // S014/RF-5
    }

    /**
     * Abre el replanteo con un punto o un centro de bloque del dibujo como punto de diseño, con su Este, su Norte y su cota si la
     * tiene (S041/RF-1). No hay "Siguiente" ni "Anterior": no está en la lista de puntos del proyecto.
     */
    fun openElement(projectId: String, selection: DesignSelection) {
        this.projectId.value = projectId
        searchText.value = ""
        controller.reset()
        notFound.value = false
        elevationOverride.value = null
        fromDrawing = true
        targetId.value = null
        target.value = null
        viewModelScope.launch {
            val project = projects.project(projectId) ?: return@launch
            val entity = selection.entity
            val height = entity.height?.get(0)?.takeIf { selection.hasElevation }
            val typed = TypedCoordinates(EntryForm.PROJECT_CRS, entity.easting[0], entity.northing[0], height, HeightKind.PROJECT)
            val computed = ManualCoordinates(calculator.get()).compute(typed, project.crs, project.heightSystem)
            val coordinates = (computed as? ManualResult.Computed)?.coordinates ?: return@launch
            if (!fromDrawing) return@launch // se cerró o se abrió otro punto mientras tanto
            target.value = StakeTarget(
                id = "cad:${selection.ref.layerId}:${selection.ref.chunkId}:${selection.ref.index}",
                name = selection.layerName,
                description = "",
                coordinates = coordinates,
                source = StakeSource.CadElement(selection.drawingName, selection.layerName, entity.kind),
            )
        }
        log.info(LogCategory.POINTS, "Replanteo abierto") // S014/RF-5
    }

    /** Cierra el replanteo y empieza de cero (S018/RF-3). */
    fun close() {
        fromDrawing = false
        targetId.value = null
        target.value = null
        notFound.value = false
        elevationOverride.value = null // S019/RF-3
        controller.reset()
    }

    fun next() = move(+1)

    fun previous() = move(-1)

    /** S018/RF-3: no pasa de los extremos ni cambia con una medida en marcha (RF-30). */
    private fun move(step: Int) {
        if (inProgress.active.value) return
        val current = target.value ?: return
        val list = ordered.value
        val index = list.indexOfFirst { it.id == current.id }
        val destination = list.getOrNull(index + step)?.takeIf { index >= 0 } ?: return
        goTo(destination.id)
    }

    private fun goTo(pointId: String) {
        fromDrawing = false
        controller.reset()
        notFound.value = false
        elevationOverride.value = null // S019/RF-3: la cota escrita no pasa al punto siguiente
        targetId.value = pointId
    }

    fun setMode(mode: StakeMode) = change { it.copy(mode = mode) }

    fun setHeading(choice: HeadingChoice) = change { it.copy(heading = choice) }

    /** La tolerancia escrita en la unidad del proyecto; false si no vale o no se puede cambiar ahora (S018/RF-18, RF-30). */
    fun setTolerance(text: String): Boolean {
        if (inProgress.active.value) return false
        val unit = project?.linearUnit ?: LinearUnit.METRE
        val metres = StakeoutSettings.parseTolerance(text, unit) ?: return false
        change { it.copy(toleranceM = metres) }
        return true
    }

    /** La tolerancia vertical escrita en la unidad del proyecto; false si no vale o no se puede cambiar ahora (S019/RF-10, RF-16). */
    fun setToleranceV(text: String): Boolean {
        if (inProgress.active.value) return false
        val unit = project?.linearUnit ?: LinearUnit.METRE
        val metres = StakeoutSettings.parseTolerance(text, unit) ?: return false
        change { it.copy(toleranceVM = metres) }
        return true
    }

    /**
     * La cota de diseño escrita en la unidad del proyecto, en lugar de la del punto de diseño; false si no es un número o no se
     * puede cambiar ahora, y entonces no cambia nada (S019/RF-2, RF-4, RF-16). No modifica el punto de diseño.
     */
    fun setDesignElevation(text: String): Boolean {
        if (inProgress.active.value) return false
        val unit = project?.linearUnit ?: LinearUnit.METRE
        val metres = NumberInput.parse(text)?.let(unit::toMetres)?.takeIf { it.isFinite() } ?: return false
        elevationOverride.value = metres
        return true
    }

    /** Descarta la cota escrita y vuelve a la del punto de diseño (S019/RF-3); no cambia con una medida en marcha (RF-16). */
    fun useDesignPointElevation() {
        if (inProgress.active.value) return
        elevationOverride.value = null
    }

    private fun change(transform: (StakeoutSettings) -> StakeoutSettings) {
        if (inProgress.active.value) return // S018/RF-30
        viewModelScope.launch { stakeoutPreferences.save(transform(stakeoutPreferences.settings.first())) }
    }

    private fun context(): StakeContext? {
        val project = project ?: return null
        val design = target.value ?: return null
        val mode: TiltMode? = tilt.state.value.settings.mode
        return StakeContext(settings, design, fixOnly, mode, poleHeightM, project, calculator.get(), elevationOverride.value)
    }
}
