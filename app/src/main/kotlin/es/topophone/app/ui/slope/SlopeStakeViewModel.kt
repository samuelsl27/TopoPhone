package es.topophone.app.ui.slope

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.Lazy
import dagger.hilt.android.lifecycle.HiltViewModel
import es.topophone.app.di.IoDispatcher
import es.topophone.app.receiver.CurrentGear
import es.topophone.app.receiver.CurrentTilt
import es.topophone.app.ui.collector.MeasurementInProgress
import es.topophone.app.ui.stakeout.AxisField
import es.topophone.app.ui.stakeout.GuidanceOff
import es.topophone.app.ui.stakeout.GuidanceState
import es.topophone.app.ui.stakeout.StakeContext
import es.topophone.app.ui.stakeout.StakeObjective
import es.topophone.app.ui.stakeout.StakeObjectiveProvider
import es.topophone.app.ui.stakeout.StakeSaveInfo
import es.topophone.app.ui.stakeout.StakeTarget
import es.topophone.app.ui.stakeout.StakeoutController
import es.topophone.core.model.alignment.Alignment
import es.topophone.core.model.alignment.ChainageFormat
import es.topophone.core.model.alignment.slope.SlopeKind
import es.topophone.core.model.alignment.slope.SlopeSide
import es.topophone.core.model.alignment.slope.SlopeStakeInfo
import es.topophone.core.model.log.AppLog
import es.topophone.core.model.log.LogCategory
import es.topophone.core.model.point.AdmittedSolutions
import es.topophone.core.model.point.EntryForm
import es.topophone.core.model.point.HeadingChoice
import es.topophone.core.model.point.HeightKind
import es.topophone.core.model.point.PointCoordinates
import es.topophone.core.model.point.StakeMode
import es.topophone.core.model.point.StakeSource
import es.topophone.core.model.point.StakeoutSettings
import es.topophone.core.model.point.TypedCoordinates
import es.topophone.core.model.project.LinearUnit
import es.topophone.core.model.project.Project
import es.topophone.core.survey.AntennaProfile
import es.topophone.core.survey.AxisChainageSeries
import es.topophone.core.survey.AxisCheck
import es.topophone.core.survey.AxisInputError
import es.topophone.core.survey.AxisPoint
import es.topophone.core.survey.AxisStakeInputs
import es.topophone.core.survey.KeyStations
import es.topophone.core.survey.ManualCoordinates
import es.topophone.core.survey.ManualResult
import es.topophone.core.survey.PoleHeight
import es.topophone.core.survey.ProjectPositionCalculator
import es.topophone.core.survey.SlopeLive
import es.topophone.core.survey.SlopeLiveValue
import es.topophone.core.survey.SlopeStake
import es.topophone.data.alignment.AlignmentRepository
import es.topophone.data.baseshift.ActiveBaseShift
import es.topophone.data.localization.ActiveLocalization
import es.topophone.data.point.CollectorPreferences
import es.topophone.data.point.StakeoutPreferences
import es.topophone.data.project.ProjectRepository
import es.topophone.data.section.SectionRepository
import es.topophone.data.slope.SlopeRepository
import es.topophone.data.surface.SurfaceGuides
import es.topophone.receiver.platform.Alerts
import es.topophone.receiver.session.ConnectionState
import es.topophone.receiver.session.ReceiverSession
import java.util.Locale
import javax.inject.Inject
import kotlin.math.abs
import kotlin.math.roundToLong
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Un PK y un lado de la serie del replanteo de taludes (S051/RF-9). */
data class SlopeStakeItem(val chainageM: Double, val side: SlopeSide)

/**
 * A dónde guía el replanteo en un PK y un lado (S051/RF-9, RF-14): el punto de talud teórico, el punto de estaca desplazado de él o,
 * sin terreno, el borde de explanación.
 *
 * @property kind cabeza o pie; null si el terreno no lo decide (en el borde o sin terreno): lo decide la punta en vivo (D3).
 * @property slopeHeightM la altura de talud del punto de talud; null si no se ha encontrado.
 * @property stake el punto de estaca si hay desplazamiento (RF-14).
 */
data class SlopeObjective(
    val point: AxisPoint,
    val heightM: Double,
    val kind: SlopeKind?,
    val slopeHeightM: Double?,
    val stake: SlopeStake?,
)

/**
 * Lo que muestra el replanteo de taludes (S051/RF-9 a RF-14, RF-17, RF-18).
 *
 * @property sides los lados que se replantean en cada PK, en orden Izquierda y Derecha.
 * @property position el lugar de la serie (desde 1) y [count] cuántos hay (RF-9).
 * @property outcome el punto de talud teórico del PK y el lado de ahora, o por qué no lo hay (RF-8).
 * @property live la punta frente al talud de diseño con la última época (RF-11 a RF-13); null sin guía o sin cota de la punta.
 * @property noProjection la punta no se proyecta sobre el eje: se apaga la parte a lo largo y no hay valores en vivo (RF-17).
 * @property measuring hay una medida en marcha: no se cambia el PK, el lado ni el desplazamiento de estaca (RF-18).
 */
data class SlopeStakeUiState(
    val project: Project? = null,
    val loaded: Boolean = false,
    val alignmentName: String = "",
    val startChainageM: Double = 0.0,
    val endChainageM: Double = 0.0,
    val started: Boolean = false,
    val startText: String = "",
    val endText: String = "",
    val intervalText: String = "",
    val singular: Boolean = false,
    val sides: List<SlopeSide> = listOf(SlopeSide.LEFT, SlopeSide.RIGHT),
    val stakeOffsetText: String = "0",
    val errors: Map<AxisField, AxisInputError> = emptyMap(),
    val position: Int = 0,
    val count: Int = 0,
    val item: SlopeStakeItem? = null,
    val outcome: SlopePointOutcome? = null,
    val objective: SlopeObjective? = null,
    val settings: StakeoutSettings = StakeoutSettings(),
    val guidance: GuidanceState = GuidanceState.Off(GuidanceOff.NO_POSITION),
    val live: SlopeLiveValue? = null,
    val noProjection: Boolean = false,
    val measuring: Boolean = false,
    val admitted: AdmittedSolutions = AdmittedSolutions.FIX_ONLY,
    val stakeTarget: StakeTarget? = null,
    val saveInfo: StakeSaveInfo? = null,
)

/**
 * El replanteo de taludes de un eje (S051/RF-9 a RF-14, RF-17, RF-18): los PK y lados que se recorren, el punto de talud de cada uno, y la
 * guía de S018 con un objetivo fijo por PK (el punto de talud, el punto de estaca o el borde); la punta en vivo solo mueve el alzado y las
 * correcciones, nunca el objetivo (D7). Medir y guardar lo hace el recolector.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class SlopeStakeViewModel @Inject constructor(
    private val projects: ProjectRepository,
    private val alignments: AlignmentRepository,
    private val sections: SectionRepository,
    private val slopes: SlopeRepository,
    private val surfaces: SurfaceGuides,
    private val stakeoutPreferences: StakeoutPreferences,
    private val collectorPreferences: CollectorPreferences,
    private val gear: CurrentGear,
    private val session: ReceiverSession,
    private val alerts: Alerts,
    private val calculator: Lazy<ProjectPositionCalculator>,
    @param:IoDispatcher private val io: CoroutineDispatcher,
    private val inProgress: MeasurementInProgress = MeasurementInProgress(),
    private val log: AppLog = AppLog.NONE,
    private val tilt: CurrentTilt = CurrentTilt.NONE,
    private val baseShift: ActiveBaseShift = ActiveBaseShift.NONE,
    private val localization: ActiveLocalization = ActiveLocalization.NONE,
) : ViewModel() {
    private val controller = StakeoutController(alerts, log)

    private val activeShift by lazy { baseShift.track(projectId, viewModelScope) } // S020/RF-7
    private val activeLocalization by lazy { localization.track(projectId, viewModelScope) } // S039/RF-17

    private class Model(
        val projectId: String,
        val alignment: Alignment,
        val solver: SlopeSolver,
        val keyStations: List<Double>,
        val startText: String = "",
        val endText: String = "",
        val intervalText: String = "",
        val singular: Boolean = false,
        val sides: List<SlopeSide> = listOf(SlopeSide.LEFT, SlopeSide.RIGHT),
        val stakeOffsetText: String = "0",
        val stakeOffsetM: Double = 0.0,
        val errors: Map<AxisField, AxisInputError> = emptyMap(),
        val started: Boolean = false,
        val series: AxisChainageSeries? = null,
        val index: Int = 0,
        val item: SlopeStakeItem? = null,
        val outcome: SlopePointOutcome? = null,
    ) {
        fun copy(
            startText: String = this.startText,
            endText: String = this.endText,
            intervalText: String = this.intervalText,
            singular: Boolean = this.singular,
            sides: List<SlopeSide> = this.sides,
            stakeOffsetText: String = this.stakeOffsetText,
            stakeOffsetM: Double = this.stakeOffsetM,
            errors: Map<AxisField, AxisInputError> = this.errors,
            started: Boolean = this.started,
            series: AxisChainageSeries? = this.series,
            index: Int = this.index,
            item: SlopeStakeItem? = this.item,
            outcome: SlopePointOutcome? = this.outcome,
        ) = Model(
            projectId, alignment, solver, keyStations, startText, endText, intervalText, singular, sides, stakeOffsetText, stakeOffsetM,
            errors, started, series, index, item, outcome,
        )

        val count: Int get() = (series?.size ?: 0) * sides.size

        fun itemAt(i: Int): SlopeStakeItem {
            val pk = series!!.chainageOf(i / sides.size)
            return SlopeStakeItem(pk, sides[i % sides.size])
        }
    }

    private val model = MutableStateFlow<Model?>(null)
    private val projectId = MutableStateFlow<String?>(null)
    private val admittedFlow = MutableStateFlow(AdmittedSolutions.FIX_ONLY)
    private var job: Job? = null

    @Volatile private var project: Project? = null

    @Volatile private var settings = StakeoutSettings()

    @Volatile private var poleHeightM = PoleHeight.DEFAULT_M

    @Volatile private var profile = AntennaProfile.RS3

    private val projectFlow = projectId.flatMapLatest { id -> if (id == null) flowOf(null) else projects.observe(id) }

    private data class Live(val settings: StakeoutSettings, val guidance: GuidanceState, val measuring: Boolean)

    private val coreState = combine(
        projectFlow,
        model,
        combine(stakeoutPreferences.settings, controller.state, inProgress.active, ::Live),
    ) { project, model, live ->
        if (model == null) return@combine SlopeStakeUiState(project = project, settings = live.settings)
        val objective = objectiveOf(model)
        val edge = (model.outcome as? SlopePointOutcome.Found)?.edge
            ?: (model.outcome as? SlopePointOutcome.AtEdge)?.edge
            ?: (model.outcome as? SlopePointOutcome.NoIntersection)?.edge
            ?: (model.outcome as? SlopePointOutcome.NoTerrain)?.edge
        val on = live.guidance as? GuidanceState.On
        var liveValue: SlopeLiveValue? = null
        var noProjection = false
        if (objective != null && edge != null && on != null) {
            val e = objective.point.easting + on.ui.tipEastM
            val n = objective.point.northing + on.ui.tipNorthM
            val dz = on.ui.guidance.dHeightM
            val value = if (dz != null) {
                SlopeLive.of(
                    edge,
                    objective.kind,
                    e,
                    n,
                    objective.heightM + dz,
                    model.solver.geometry,
                    live.settings.validToleranceM,
                    live.settings.validToleranceVM,
                )
            } else {
                null
            }
            liveValue = value
            noProjection = model.solver.geometry.locate(e, n) == null
        }
        SlopeStakeUiState(
            project = project,
            loaded = true,
            alignmentName = model.alignment.name,
            startChainageM = model.solver.geometry.startChainage,
            endChainageM = model.solver.geometry.endChainage,
            started = model.started,
            startText = model.startText,
            endText = model.endText,
            intervalText = model.intervalText,
            singular = model.singular,
            sides = model.sides,
            stakeOffsetText = model.stakeOffsetText,
            errors = model.errors,
            position = if (model.started) model.index + 1 else 0,
            count = if (model.started) model.count else 0,
            item = model.item,
            outcome = model.outcome,
            objective = objective,
            settings = live.settings,
            guidance = live.guidance,
            live = liveValue,
            noProjection = noProjection,
            measuring = live.measuring,
            stakeTarget = if (project != null && objective != null) designTarget(project, model, objective) else null,
            saveInfo = objective?.let { StakeSaveInfo(it.heightM, live.settings.validToleranceM, live.settings.validToleranceVM) },
        )
    }

    val state: StateFlow<SlopeStakeUiState> = combine(coreState, admittedFlow) { state, admitted -> state.copy(admitted = admitted) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, SlopeStakeUiState())

    init {
        viewModelScope.launch { projectFlow.collect { project = it } }
        viewModelScope.launch { stakeoutPreferences.settings.collect { settings = it } }
        viewModelScope.launch { collectorPreferences.settings.collect { admittedFlow.value = it.precision.admitted } }
        viewModelScope.launch {
            gear.state.collect { g ->
                g.verticalPoleM?.let { poleHeightM = it }
                profile = g.profile
            }
        }
        viewModelScope.launch {
            session.epochs.collect { received ->
                val context = context() ?: return@collect
                controller.onEpoch(received.epoch, context)
            }
        }
        // S051/RF-13: un aviso al encontrar el punto de talud; otro cuando se vuelve a encontrar tras perderlo o al cambiar de punto.
        viewModelScope.launch {
            state.map { s ->
                s.item.takeIf { s.live?.found == true }
            }.distinctUntilChanged().filterNotNull().collect { alerts.slopePointFound() }
        }
        // S018/RF-25: sin épocas la guía se apaga hasta que vuelvan.
        viewModelScope.launch {
            session.state.collect { snapshot ->
                if (snapshot.connection !is ConnectionState.Receiving && model.value != null) controller.onSilence(context())
            }
        }
    }

    private val unit: LinearUnit get() = project?.linearUnit ?: LinearUnit.METRE

    /** Abre los campos de partida del replanteo de taludes del eje [alignmentId] del proyecto [projectId] (S051/RF-9). */
    fun open(projectId: String, alignmentId: String) {
        this.projectId.value = projectId
        controller.reset()
        viewModelScope.launch(io) {
            val alignment = alignments.alignment(alignmentId) ?: return@launch
            if (alignment.projectId != projectId) return@launch
            project = projects.project(projectId)
            val solver = SlopeSolver.load(projectId, alignment, slopes, sections, surfaces)
            model.value = Model(projectId, alignment, solver, KeyStations.of(alignment.elements, alignment.profile).map { it.chainageM })
            log.info(LogCategory.POINTS, "Replanteo de taludes de un eje abierto") // S014/RF-5
        }
    }

    /** Cierra el replanteo y vuelve a los campos de partida, que conservan lo escrito. */
    fun close() {
        val current = model.value ?: return
        if (inProgress.active.value) return
        job?.cancel()
        controller.reset()
        model.value = current.copy(started = false, series = null, index = 0, item = null, outcome = null, errors = emptyMap())
    }

    // ---- campos de partida

    fun setStart(text: String): Boolean = setup { it.copy(startText = text, errors = it.errors - AxisField.START) }

    fun setEnd(text: String): Boolean = setup { it.copy(endText = text, errors = it.errors - AxisField.END) }

    fun setInterval(text: String): Boolean = setup { it.copy(intervalText = text, errors = it.errors - AxisField.INTERVAL) }

    fun setSingular(value: Boolean): Boolean = setup { it.copy(singular = value) }

    /** Elige o quita un lado; siempre queda al menos uno (RF-9). */
    fun toggleSide(side: SlopeSide): Boolean = setup {
        val next = SlopeSide.entries.filter { s -> (s in it.sides) != (s == side) }
        if (next.isEmpty()) it else it.copy(sides = next)
    }

    private fun setup(transform: (Model) -> Model): Boolean {
        val current = model.value ?: return false
        if (current.started) return false
        model.value = transform(current)
        return true
    }

    /**
     * Abre el replanteo si lo escrito vale (RF-9): el PK inicial dentro del eje, el PK final, el intervalo y el desplazamiento de estaca.
     * Si algo no vale no se abre y [SlopeStakeUiState.errors] dice qué campo y por qué.
     */
    fun start(): Boolean {
        val m = model.value ?: return false
        if (m.started || inProgress.active.value) return false
        val u = unit
        val errors = HashMap<AxisField, AxisInputError>()
        val geometry = m.solver.geometry
        val start = AxisStakeInputs.chainage(m.startText, u, geometry)
        if (start is AxisCheck.Invalid) errors[AxisField.START] = start.error
        val end = AxisStakeInputs.optionalChainage(m.endText, u, geometry)
        if (end is AxisCheck.Invalid) errors[AxisField.END] = end.error
        val interval = AxisStakeInputs.interval(m.intervalText, u)
        if (interval is AxisCheck.Invalid) errors[AxisField.INTERVAL] = interval.error
        val offset = AxisStakeInputs.offset(m.stakeOffsetText, u)
        if (offset is AxisCheck.Invalid) errors[AxisField.OFFSET] = offset.error
        if (start !is AxisCheck.Valid || end !is AxisCheck.Valid || interval !is AxisCheck.Valid || offset !is AxisCheck.Valid) {
            model.value = m.copy(errors = errors)
            return false
        }
        val series = AxisChainageSeries(
            start.value,
            end.value,
            interval.value,
            m.keyStations.takeIf { m.singular },
            geometry.endChainage,
        )
        controller.reset()
        val opened = m.copy(started = true, series = series, index = 0, stakeOffsetM = offset.value, errors = emptyMap())
        model.value = opened
        select(0)
        return true
    }

    // ---- con el replanteo abierto (RF-18: nada cambia con una medida en marcha)

    /** El siguiente PK y lado de la serie; no pasa del último. */
    fun next(): Boolean = move(1)

    /** El anterior PK y lado de la serie; no pasa del primero. */
    fun previous(): Boolean = move(-1)

    private fun move(step: Int): Boolean {
        if (inProgress.active.value) return false
        val m = model.value ?: return false
        if (!m.started) return false
        val target = (m.index + step).coerceIn(0, m.count - 1)
        if (target == m.index) return false
        select(target)
        return true
    }

    /** El desplazamiento de estaca, positivo hacia fuera; si no vale no se aplica y conserva el anterior (RF-14). */
    fun setStakeOffset(text: String): Boolean {
        if (inProgress.active.value) return false
        val m = model.value ?: return false
        val check = AxisStakeInputs.offset(text, unit)
        model.value = when (check) {
            is AxisCheck.Valid -> m.copy(stakeOffsetText = text, stakeOffsetM = check.value, errors = m.errors - AxisField.OFFSET)
            is AxisCheck.Invalid -> m.copy(stakeOffsetText = text, errors = m.errors + (AxisField.OFFSET to check.error))
        }
        if (m.started && check is AxisCheck.Valid) controller.reset()
        return true
    }

    /** Pone el PK y el lado del lugar [index] de la serie y calcula su punto de talud fuera del hilo principal (RF-9). */
    private fun select(index: Int) {
        val m = model.value ?: return
        val item = m.itemAt(index)
        controller.reset()
        model.value = m.copy(index = index, item = item, outcome = null)
        job?.cancel()
        job = viewModelScope.launch(io) {
            val outcome = m.solver.outcome(item.chainageM, item.side)
            val latest = model.value ?: return@launch
            if (latest.index == index) model.value = latest.copy(outcome = outcome)
        }
    }

    // ---- ajustes de la guía, los mismos que en el replanteo de un punto

    fun setGuideMode(mode: StakeMode): Boolean = change { it.copy(mode = mode) }

    fun setHeading(choice: HeadingChoice): Boolean = change { it.copy(heading = choice) }

    fun setTolerance(text: String): Boolean {
        if (inProgress.active.value) return false
        val metres = StakeoutSettings.parseTolerance(text, unit) ?: return false
        return change { it.copy(toleranceM = metres) }
    }

    fun setToleranceV(text: String): Boolean {
        if (inProgress.active.value) return false
        val metres = StakeoutSettings.parseTolerance(text, unit) ?: return false
        return change { it.copy(toleranceVM = metres) }
    }

    private fun change(transform: (StakeoutSettings) -> StakeoutSettings): Boolean {
        if (inProgress.active.value) return false
        viewModelScope.launch { stakeoutPreferences.save(transform(stakeoutPreferences.settings.first())) }
        return true
    }

    // ---- objetivo y contexto de la guía

    /**
     * El objetivo de este PK y lado (D7, RF-14): el punto de talud, o el desplazado de él si hay desplazamiento de estaca (con la cota del
     * terreno ahí si se conoce, que es donde se clava), o el borde si no hay punto de talud. Null si no hay borde.
     */
    private fun objectiveOf(m: Model): SlopeObjective? {
        val item = m.item ?: return null
        return when (val outcome = m.outcome) {
            is SlopePointOutcome.Found -> {
                val found = outcome.point
                if (m.stakeOffsetM == 0.0) {
                    SlopeObjective(found.point, found.heightM, found.kind, found.slopeHeightM, null)
                } else {
                    val stake = SlopeLive.stake(outcome.edge, found, m.stakeOffsetM, m.solver.geometry)
                        ?: return SlopeObjective(found.point, found.heightM, found.kind, found.slopeHeightM, null)
                    val height = m.solver.terrainHeight(item.chainageM, stake.offsetM) ?: found.heightM
                    SlopeObjective(stake.point, height, found.kind, found.slopeHeightM, stake)
                }
            }
            is SlopePointOutcome.AtEdge -> SlopeObjective(outcome.edge.point, outcome.edge.heightM, null, 0.0, null)
            is SlopePointOutcome.NoIntersection -> SlopeObjective(outcome.edge.point, outcome.edge.heightM, null, null, null)
            is SlopePointOutcome.NoTerrain -> SlopeObjective(outcome.edge.point, outcome.edge.heightM, null, null, null)
            is SlopePointOutcome.Unavailable, null -> null
        }
    }

    private data class TargetKey(val alignmentId: String, val easting: Double, val northing: Double, val name: String)

    @Volatile private var cached: Pair<TargetKey, StakeTarget?>? = null

    private fun designTarget(project: Project, m: Model, objective: SlopeObjective): StakeTarget? {
        val item = m.item ?: return null
        val name = "${m.alignment.name} ${ChainageFormat.format(item.chainageM, project.linearUnit, Locale.ROOT)} ${item.side.name}"
        val key = TargetKey(m.alignment.id, objective.point.easting, objective.point.northing, name)
        cached?.let { (k, t) -> if (k == key && t != null) return t }
        val typed = TypedCoordinates(EntryForm.PROJECT_CRS, objective.point.easting, objective.point.northing, null, HeightKind.PROJECT)
        val coordinates = (ManualCoordinates(calculator.get()).compute(typed, project.crs, project.heightSystem) as? ManualResult.Computed)
            ?.coordinates
        val result = coordinates?.let {
            StakeTarget(
                id = "slope:${m.alignment.name}:${(item.chainageM * MILLIMETRES).roundToLong()}:${item.side.name}",
                name = m.alignment.name,
                description = "",
                coordinates = it,
                source = StakeSource.Alignment(m.alignment.name),
            )
        }
        cached = key to result
        return result
    }

    private fun context(): StakeContext? {
        val project = project ?: return null
        val m = model.value ?: return null
        if (!m.started) return null
        if (!gear.state.value.usable) return null // S046/RF-28
        val objective = objectiveOf(m) ?: return null
        val design = designTarget(project, m, objective) ?: return null
        return StakeContext(
            settings,
            design,
            admittedFlow.value,
            tilt.state.value.settings.mode,
            poleHeightM,
            project,
            calculator.get(),
            designElevationM = objective.heightM,
            profile = profile,
            shift = activeShift.value?.vector, // S020/RF-7
            localization = activeLocalization.value?.params, // S039/RF-17
        )
    }

    // ---- el objetivo al guardar el punto (S051/RF-15)

    /** El objetivo de cuando el recolector termina una medida: el punto de diseño, el PK y el desplazamiento y lo propio del talud. */
    val objectiveProvider: StakeObjectiveProvider = StakeObjectiveProvider { currentObjective() }

    private fun currentObjective(): StakeObjective? {
        val project = project ?: return null
        val m = model.value ?: return null
        if (!m.started) return null
        val item = m.item ?: return null
        val objective = objectiveOf(m) ?: return null
        val edge = m.outcome?.edgeOrNull() ?: return null
        val design = designTarget(project, m, objective)?.coordinates ?: return null
        val geometry = m.solver.geometry
        val locate = { measured: PointCoordinates ->
            val e = measured.easting
            val n = measured.northing
            if (e == null || n == null) null else geometry.locate(e, n)
        }
        val tolerance = settings
        return StakeObjective(
            design = design.copy(height = objective.heightM),
            elevationM = objective.heightM,
            mode = null,
            chainageM = item.chainageM,
            lateralOffsetM = objective.point.offset,
            lateralErrorOf = { measured -> locate(measured)?.let { it.offset - objective.point.offset } },
            chainageErrorOf = { measured -> locate(measured)?.let { it.chainage - item.chainageM } },
            slopeOf = { measured ->
                val e = measured.easting
                val n = measured.northing
                val height = measured.height
                val value = if (e != null && n != null && height != null) {
                    SlopeLive.of(edge, objective.kind, e, n, height, geometry, tolerance.validToleranceM, tolerance.validToleranceVM)
                } else {
                    null
                }
                val kind = value?.kind ?: objective.kind ?: SlopeKind.CUT
                SlopeStakeInfo(
                    kind,
                    item.side,
                    edge.rule.ratioOf(kind),
                    objective.slopeHeightM ?: abs((height ?: edge.heightM) - edge.heightM),
                    value?.dHeightM,
                    m.stakeOffsetM,
                )
            },
        )
    }

    private companion object {
        const val MILLIMETRES = 1_000.0
    }
}
