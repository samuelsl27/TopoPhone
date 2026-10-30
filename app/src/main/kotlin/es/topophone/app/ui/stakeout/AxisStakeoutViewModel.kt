package es.topophone.app.ui.stakeout

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.Lazy
import dagger.hilt.android.lifecycle.HiltViewModel
import es.topophone.app.receiver.CurrentGear
import es.topophone.app.receiver.CurrentTilt
import es.topophone.app.ui.collector.MeasurementInProgress
import es.topophone.core.model.alignment.Alignment
import es.topophone.core.model.alignment.ChainageFormat
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
import es.topophone.core.model.point.TiltMode
import es.topophone.core.model.point.TypedCoordinates
import es.topophone.core.model.project.LinearUnit
import es.topophone.core.model.project.Project
import es.topophone.core.survey.AlignmentGeometry
import es.topophone.core.survey.AntennaProfile
import es.topophone.core.survey.AxisChainageSeries
import es.topophone.core.survey.AxisCheck
import es.topophone.core.survey.AxisGuidance
import es.topophone.core.survey.AxisGuidanceValue
import es.topophone.core.survey.AxisInputError
import es.topophone.core.survey.AxisStakeInputs
import es.topophone.core.survey.AxisStakeResult
import es.topophone.core.survey.AxisStakeTarget
import es.topophone.core.survey.KeyStations
import es.topophone.core.survey.ManualCoordinates
import es.topophone.core.survey.ManualResult
import es.topophone.core.survey.PoleHeight
import es.topophone.core.survey.ProjectPositionCalculator
import es.topophone.core.survey.VerticalProfileEval
import es.topophone.data.alignment.AlignmentRepository
import es.topophone.data.baseshift.ActiveBaseShift
import es.topophone.data.point.CollectorPreferences
import es.topophone.data.point.StakeoutPreferences
import es.topophone.data.project.ProjectRepository
import es.topophone.receiver.platform.Alerts
import es.topophone.receiver.session.ConnectionState
import es.topophone.receiver.session.ReceiverSession
import java.util.Locale
import javax.inject.Inject
import kotlin.math.roundToLong
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Los campos del replanteo por PK que se validan (S054/RF-1, RF-5, RF-9). */
enum class AxisField { START, END, INTERVAL, OFFSET, THICKNESS, CHAINAGE }

/** Por qué se cierra el replanteo por PK sin que el usuario lo pida (S054/RF-11). */
enum class AxisClosedReason { ALIGNMENT_DELETED, }

/** Un eje del proyecto que se puede elegir en el replanteo (S054/RF-11). */
data class AxisChoice(val id: String, val name: String)

/**
 * Lo que muestra el replanteo por PK y desplazamiento (S054/RF-1 a RF-7, RF-9, RF-11).
 *
 * @property started el replanteo está abierto; antes se ven solo los campos de partida (RF-1).
 * @property startText los textos de los campos, en la unidad del proyecto: lo escrito aunque no se haya aplicado.
 * @property chainageText el PK que se replantea ahora; se puede cambiar a mano (RF-5).
 * @property errors el motivo por el que lo escrito en un campo no se aplica (RF-9); lo aplicado sigue siendo el valor anterior.
 * @property series los PK del replanteo con el elegido en [seriesIndex] (RF-2 a RF-4).
 * @property target el punto de diseño de ahora, o null si el PK no cabe en el eje (RF-9).
 * @property axisGuidance lo que hay que moverse a lo largo y a través del eje con la última época de la guía (RF-6).
 * @property measuring hay una medida en marcha: no se cambia nada.
 */
data class AxisStakeoutUiState(
    val project: Project? = null,
    val loaded: Boolean = false,
    val alignmentId: String? = null,
    val alignmentName: String = "",
    val choices: List<AxisChoice> = emptyList(),
    val startChainageM: Double = 0.0,
    val endChainageM: Double = 0.0,
    val hasProfile: Boolean = false,
    val started: Boolean = false,
    val startText: String = "",
    val endText: String = "",
    val intervalText: String = "",
    val singular: Boolean = false,
    val offsetText: String = "0",
    val thicknessText: String = "",
    val chainageText: String = "",
    val errors: Map<AxisField, AxisInputError> = emptyMap(),
    val series: AxisChainageSeries? = null,
    val seriesIndex: Int = 0,
    val target: AxisStakeResult.Target? = null,
    val settings: StakeoutSettings = StakeoutSettings(),
    val guidance: GuidanceState = GuidanceState.Off(GuidanceOff.NO_POSITION),
    val axisGuidance: AxisGuidanceValue? = null,
    /** La punta no se proyecta sobre el eje: está antes de su inicio o después de su final, y se muestran solo el Norte y el Este (RF-8). */
    val noProjection: Boolean = false,
    val measuring: Boolean = false,
    val closed: AxisClosedReason? = null,
    /** El punto de diseño para el recolector (RF-10). */
    val stakeTarget: StakeTarget? = null,
    /** Las tolerancias que se guardan con el punto replanteado; la cota es la de la rasante más el espesor (RF-7, RF-10). */
    val saveInfo: StakeSaveInfo? = null,
    /** Los tipos de solución admitidos, para el indicador junto a la guía (S047/RF-14). */
    val admitted: AdmittedSolutions = AdmittedSolutions.FIX_ONLY,
)

/**
 * El replanteo por PK y desplazamiento de un eje (S054): lo que escribe el usuario, la serie de PK, el punto de diseño con la cota de la
 * rasante más el espesor, y la guía que sale de [StakeoutController] con un punto de diseño fijo. Medir y guardar lo hace el recolector
 * con el objetivo que se le dé aquí; este modelo solo sabe si hay una medida en marcha.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class AxisStakeoutViewModel @Inject constructor(
    private val projects: ProjectRepository,
    private val alignments: AlignmentRepository,
    private val stakeoutPreferences: StakeoutPreferences,
    private val collectorPreferences: CollectorPreferences,
    private val gear: CurrentGear,
    private val session: ReceiverSession,
    alerts: Alerts,
    private val calculator: Lazy<ProjectPositionCalculator>,
    private val inProgress: MeasurementInProgress = MeasurementInProgress(),
    private val log: AppLog = AppLog.NONE,
    private val tilt: CurrentTilt = CurrentTilt.NONE,
    private val baseShift: ActiveBaseShift = ActiveBaseShift.NONE,
) : ViewModel() {
    private val controller = StakeoutController(alerts, log)

    private val activeShift by lazy { baseShift.track(projectId, viewModelScope) } // S020/RF-7

    /** Todo lo del eje abierto; null si no hay ninguno. */
    private data class Model(
        val projectId: String,
        val alignment: Alignment,
        val geometry: AlignmentGeometry,
        val profile: VerticalProfileEval?,
        val keyStations: List<Double>,
        val startText: String,
        val endText: String,
        val intervalText: String,
        val singular: Boolean,
        val offsetText: String,
        val thicknessText: String,
        val chainageText: String,
        val started: Boolean,
        val series: AxisChainageSeries?,
        val seriesIndex: Int,
        val chainageM: Double?,
        val offsetM: Double,
        val thicknessM: Double,
        val errors: Map<AxisField, AxisInputError>,
        val closed: AxisClosedReason?,
    )

    private val model = MutableStateFlow<Model?>(null)
    private val projectId = MutableStateFlow<String?>(null)

    @Volatile private var project: Project? = null

    @Volatile private var settings = StakeoutSettings()

    private val admittedFlow = MutableStateFlow(AdmittedSolutions.FIX_ONLY)

    @Volatile private var poleHeightM = PoleHeight.DEFAULT_M

    @Volatile private var profile = AntennaProfile.RS3

    private val projectFlow = projectId.flatMapLatest { id -> if (id == null) flowOf(null) else projects.observe(id) }
    private val choicesFlow = projectId.flatMapLatest { id ->
        if (id == null) flowOf(emptyList()) else alignments.summaries(id).map { list -> list.map { AxisChoice(it.id, it.name) } }
    }

    private data class Live(val settings: StakeoutSettings, val guidance: GuidanceState, val measuring: Boolean)

    private val coreState = combine(
        projectFlow,
        model,
        combine(stakeoutPreferences.settings, controller.state, inProgress.active, ::Live),
        choicesFlow,
    ) { project, model, live, choices ->
        if (model == null) {
            return@combine AxisStakeoutUiState(project = project, settings = live.settings, choices = choices)
        }
        val target = if (model.started) targetOf(model) else null
        val stakeTarget = project?.let { p -> target?.let { designTarget(p, model, it) } }
        val on = live.guidance as? GuidanceState.On
        // S054/RF-8: la punta se proyecta sobre el eje si cae entre su inicio y su final; si no, no hay PK ni guía a lo largo.
        val tip = if (target != null &&
            on != null
        ) {
            (target.point.easting + on.ui.tipEastM) to (target.point.northing + on.ui.tipNorthM)
        } else {
            null
        }
        val projects = tip?.let { model.geometry.locate(it.first, it.second) != null }
        AxisStakeoutUiState(
            project = project,
            loaded = true,
            alignmentId = model.alignment.id,
            alignmentName = model.alignment.name,
            choices = choices,
            startChainageM = model.geometry.startChainage,
            endChainageM = model.geometry.endChainage,
            hasProfile = model.profile != null,
            started = model.started,
            startText = model.startText,
            endText = model.endText,
            intervalText = model.intervalText,
            singular = model.singular,
            offsetText = model.offsetText,
            thicknessText = model.thicknessText,
            chainageText = model.chainageText,
            errors = model.errors,
            series = model.series,
            seriesIndex = model.seriesIndex,
            target = target,
            settings = live.settings,
            guidance = live.guidance,
            axisGuidance = if (target != null && tip != null && projects == true) AxisGuidance.of(target, tip.first, tip.second) else null,
            noProjection = projects == false,
            measuring = live.measuring,
            closed = model.closed,
            stakeTarget = stakeTarget,
            saveInfo = target?.let { StakeSaveInfo(it.elevationM, live.settings.validToleranceM, live.settings.validToleranceVM) },
        )
    }

    /** Lo de arriba más los tipos admitidos, que se muestran junto a la guía (S047/RF-14). */
    val state: StateFlow<AxisStakeoutUiState> = combine(coreState, admittedFlow) { state, admitted -> state.copy(admitted = admitted) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, AxisStakeoutUiState())

    /** Da el objetivo de ahora al recolector cuando termina la medida (S054/RF-10). */
    val objectiveProvider: StakeObjectiveProvider = StakeObjectiveProvider { currentObjective() }

    init {
        viewModelScope.launch { projectFlow.collect { project = it } }
        viewModelScope.launch { stakeoutPreferences.settings.collect { settings = it } }
        viewModelScope.launch { collectorPreferences.settings.collect { admittedFlow.value = it.precision.admitted } }
        viewModelScope.launch {
            // S046/RF-23: el mismo equipo activo que el recolector.
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
        // S018/RF-25: sin épocas la guía se apaga hasta que vuelvan.
        viewModelScope.launch {
            session.state.collect { snapshot ->
                if (snapshot.connection !is ConnectionState.Receiving && model.value != null) controller.onSilence(context())
            }
        }
        // S054/RF-11: si el eje del replanteo se borra, se cierra y se dice por qué.
        viewModelScope.launch {
            choicesFlow.collect { choices ->
                val current = model.value ?: return@collect
                if (current.closed == null && choices.none { it.id == current.alignment.id }) close(AxisClosedReason.ALIGNMENT_DELETED)
            }
        }
    }

    private val unit: LinearUnit get() = project?.linearUnit ?: LinearUnit.METRE

    /**
     * Abre el replanteo por PK del eje [alignmentId] del proyecto [projectId], o del eje activo si es null (S054/RF-1, RF-11). Empieza con
     * los campos vacíos, desplazamiento 0 y sin intervalo.
     */
    fun open(projectId: String, alignmentId: String?) {
        this.projectId.value = projectId
        controller.reset()
        viewModelScope.launch {
            val id = alignmentId ?: alignments.activeAlignmentId(projectId).first() ?: return@launch
            val alignment = alignments.alignment(id) ?: return@launch
            if (alignment.projectId != projectId) return@launch
            val project = projects.project(projectId)
            this@AxisStakeoutViewModel.project = project
            model.value = newModel(projectId, alignment, null)
            log.info(LogCategory.POINTS, "Replanteo por PK de un eje abierto") // S014/RF-5
        }
    }

    private fun newModel(projectId: String, alignment: Alignment, previous: Model?): Model {
        val profile = alignment.profile?.let { VerticalProfileEval(it) }
        return Model(
            projectId = projectId,
            alignment = alignment,
            geometry = AlignmentGeometry(alignment),
            profile = profile,
            keyStations = KeyStations.of(alignment.elements, alignment.profile).map { it.chainageM },
            startText = previous?.startText ?: "",
            endText = previous?.endText ?: "",
            intervalText = previous?.intervalText ?: "",
            singular = previous?.singular ?: false,
            offsetText = previous?.offsetText ?: "0",
            thicknessText = previous?.thicknessText ?: "",
            chainageText = previous?.chainageText ?: "",
            started = previous?.started ?: false,
            series = null,
            seriesIndex = 0,
            chainageM = previous?.chainageM,
            offsetM = previous?.offsetM ?: 0.0,
            thicknessM = previous?.thicknessM ?: 0.0,
            errors = emptyMap(),
            closed = null,
        )
    }

    /** Cierra el replanteo y vuelve a los campos de partida, que conservan lo escrito (S054/RF-1). */
    fun close() = close(null)

    private fun close(reason: AxisClosedReason?) {
        val current = model.value ?: return
        controller.reset()
        model.value = if (reason != null) {
            current.copy(started = false, series = null, closed = reason)
        } else {
            current.copy(started = false, series = null, seriesIndex = 0, errors = emptyMap())
        }
        if (reason != null) log.info(LogCategory.POINTS, "Replanteo por PK de un eje cerrado: se borró el eje")
    }

    // ---- campos de partida (solo antes de abrir el replanteo)

    fun setStart(text: String): Boolean = setup { it.copy(startText = text, errors = it.errors - AxisField.START) }

    fun setEnd(text: String): Boolean = setup { it.copy(endText = text, errors = it.errors - AxisField.END) }

    fun setInterval(text: String): Boolean = setup { it.copy(intervalText = text, errors = it.errors - AxisField.INTERVAL) }

    fun setSingular(value: Boolean): Boolean = setup { it.copy(singular = value) }

    private fun setup(transform: (Model) -> Model): Boolean {
        val current = model.value ?: return false
        if (current.started || current.closed != null) return false
        model.value = transform(current)
        return true
    }

    /**
     * Abre el replanteo si todo lo escrito vale (S054/RF-1, RF-9): el PK inicial dentro del eje, el PK final, el intervalo, el
     * desplazamiento y el espesor. Si algo no vale no se abre y [AxisStakeoutUiState.errors] dice qué campo y por qué.
     */
    fun start(): Boolean {
        val m = model.value ?: return false
        if (m.started || m.closed != null || inProgress.active.value) return false
        val u = unit
        val errors = HashMap<AxisField, AxisInputError>()
        val startCheck = AxisStakeInputs.chainage(m.startText, u, m.geometry)
        if (startCheck is AxisCheck.Invalid) errors[AxisField.START] = startCheck.error
        val endCheck = AxisStakeInputs.optionalChainage(m.endText, u, m.geometry)
        if (endCheck is AxisCheck.Invalid) errors[AxisField.END] = endCheck.error
        val intervalCheck = AxisStakeInputs.interval(m.intervalText, u)
        if (intervalCheck is AxisCheck.Invalid) errors[AxisField.INTERVAL] = intervalCheck.error
        val offsetCheck = AxisStakeInputs.offset(m.offsetText, u)
        if (offsetCheck is AxisCheck.Invalid) errors[AxisField.OFFSET] = offsetCheck.error
        val thicknessCheck = AxisStakeInputs.thickness(m.thicknessText, u)
        if (thicknessCheck is AxisCheck.Invalid) errors[AxisField.THICKNESS] = thicknessCheck.error
        if (startCheck !is AxisCheck.Valid ||
            endCheck !is AxisCheck.Valid ||
            intervalCheck !is AxisCheck.Valid ||
            offsetCheck !is AxisCheck.Valid ||
            thicknessCheck !is AxisCheck.Valid
        ) {
            model.value = m.copy(errors = errors)
            return false
        }
        val series = AxisChainageSeries(
            startCheck.value,
            endCheck.value,
            intervalCheck.value,
            m.keyStations.takeIf { m.singular },
            m.geometry.endChainage,
        )
        controller.reset()
        model.value = m.copy(
            started = true,
            series = series,
            seriesIndex = 0,
            chainageM = series.chainageOf(0),
            chainageText = chainageFormat(series.chainageOf(0)),
            offsetM = offsetCheck.value,
            thicknessM = thicknessCheck.value,
            errors = emptyMap(),
        )
        return true
    }

    // ---- con el replanteo abierto (RF-5)

    /** El PK escrito a mano (RF-5); false con una medida en marcha. Si no vale no se aplica y conserva el anterior (RF-9). */
    fun setChainage(text: String): Boolean = edit(resetGuidance = true) { m ->
        when (val check = AxisStakeInputs.chainage(text, unit, m.geometry)) {
            is AxisCheck.Valid -> m.copy(chainageText = text, chainageM = check.value, errors = m.errors - AxisField.CHAINAGE)
            is AxisCheck.Invalid -> m.copy(chainageText = text, errors = m.errors + (AxisField.CHAINAGE to check.error))
        }
    }

    /** El desplazamiento escrito a mano: negativo a la izquierda, positivo a la derecha (RF-5). */
    fun setOffset(text: String): Boolean {
        val current = model.value
        if (current != null && !current.started) return setup { it.copy(offsetText = text, errors = it.errors - AxisField.OFFSET) }
        return edit(resetGuidance = true) { m ->
            when (val check = AxisStakeInputs.offset(text, unit)) {
                is AxisCheck.Valid -> m.copy(offsetText = text, offsetM = check.value, errors = m.errors - AxisField.OFFSET)
                is AxisCheck.Invalid -> m.copy(offsetText = text, errors = m.errors + (AxisField.OFFSET to check.error))
            }
        }
    }

    /** El espesor escrito a mano, positivo o negativo (RF-5). */
    fun setThickness(text: String): Boolean {
        val current = model.value
        if (current != null && !current.started) return setup { it.copy(thicknessText = text, errors = it.errors - AxisField.THICKNESS) }
        return edit(resetGuidance = true) { m ->
            when (val check = AxisStakeInputs.thickness(text, unit)) {
                is AxisCheck.Valid -> m.copy(thicknessText = text, thicknessM = check.value, errors = m.errors - AxisField.THICKNESS)
                is AxisCheck.Invalid -> m.copy(thicknessText = text, errors = m.errors + (AxisField.THICKNESS to check.error))
            }
        }
    }

    /** El PK siguiente de la serie (RF-3); no pasa del último. Reescribe el PK con el de la serie aunque se hubiera cambiado a mano. */
    fun next(): Boolean = move { it.next(it.indexFrom(model.value)) }

    /** El PK anterior de la serie (RF-3); no pasa del primero. */
    fun previous(): Boolean = move { it.previous(it.indexFrom(model.value)) }

    private fun AxisChainageSeries.indexFrom(m: Model?): Int = (m?.seriesIndex ?: 0).coerceIn(0, size - 1)

    private fun move(newIndex: (AxisChainageSeries) -> Int): Boolean = edit(resetGuidance = true) { m ->
        val series = m.series ?: return@edit m
        val index = newIndex(series)
        val pk = series.chainageOf(index)
        m.copy(seriesIndex = index, chainageM = pk, chainageText = chainageFormat(pk), errors = m.errors - AxisField.CHAINAGE)
    }

    /**
     * Cambia el eje del replanteo y lo deja como eje activo del proyecto (S054/RF-11, D3). Lo escrito se conserva y se vuelve a validar
     * contra el eje nuevo: si el PK no cabe, no hay punto de diseño y se dice el PK inicial y el final (RF-9).
     */
    fun setAlignment(alignmentId: String): Boolean {
        val current = model.value ?: return false
        if (inProgress.active.value || current.closed != null) return false
        if (alignmentId == current.alignment.id) return true
        viewModelScope.launch {
            val alignment = alignments.alignment(alignmentId) ?: return@launch
            if (alignment.projectId != current.projectId) return@launch
            alignments.activate(current.projectId, alignmentId)
            controller.reset()
            val latest = model.value ?: return@launch
            model.value = rebuilt(newModel(latest.projectId, alignment, latest))
        }
        return true
    }

    /** Un modelo con otro eje: se revalida el PK actual y se rehace la serie con lo escrito, si cabe (RF-9, RF-11). */
    private fun rebuilt(m: Model): Model {
        val u = unit
        val errors = HashMap<AxisField, AxisInputError>()
        val chainage = m.chainageM
        if (m.started && (chainage == null || !m.geometry.contains(chainage))) errors[AxisField.CHAINAGE] = AxisInputError.OUT_OF_AXIS
        val start = (AxisStakeInputs.chainage(m.startText, u, m.geometry) as? AxisCheck.Valid)?.value
        val end = (AxisStakeInputs.optionalChainage(m.endText, u, m.geometry) as? AxisCheck.Valid)?.value
        val interval = (AxisStakeInputs.interval(m.intervalText, u) as? AxisCheck.Valid)?.value
        val series = if (m.started && start != null) {
            AxisChainageSeries(start, end, interval, m.keyStations.takeIf { m.singular }, m.geometry.endChainage)
        } else {
            null
        }
        return m.copy(series = series, seriesIndex = 0, errors = errors)
    }

    // ---- ajustes de la guía, los mismos que en el replanteo de un punto

    /** "Por dirección" o "Por distancia" (S018/RF-5, RF-30). */
    fun setGuideMode(mode: StakeMode): Boolean = change { it.copy(mode = mode) }

    /** El origen del rumbo del usuario (S018/RF-8). */
    fun setHeading(choice: HeadingChoice): Boolean = change { it.copy(heading = choice) }

    /** La tolerancia horizontal escrita en la unidad del proyecto (S018/RF-18, RF-30). */
    fun setTolerance(text: String): Boolean {
        if (inProgress.active.value) return false
        val metres = StakeoutSettings.parseTolerance(text, unit) ?: return false
        return change { it.copy(toleranceM = metres) }
    }

    /** La tolerancia vertical escrita en la unidad del proyecto (S019/RF-10). */
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

    /** Con el replanteo abierto y sin medida en marcha; con una medida en marcha no se cambia nada. */
    private fun edit(resetGuidance: Boolean, transform: (Model) -> Model): Boolean {
        if (inProgress.active.value) return false
        val current = model.value ?: return false
        if (!current.started || current.closed != null) return false
        model.value = transform(current)
        if (resetGuidance) controller.reset()
        return true
    }

    // ---- punto de diseño, contexto de la guía y objetivo

    private fun targetOf(m: Model): AxisStakeResult.Target? {
        val chainage = m.chainageM ?: return null
        if (AxisField.CHAINAGE in m.errors && !m.geometry.contains(chainage)) return null
        return AxisStakeTarget.of(m.geometry, m.profile, chainage, m.offsetM, m.thicknessM) as? AxisStakeResult.Target
    }

    private fun chainageFormat(chainageM: Double): String = ChainageFormat.format(chainageM, unit, Locale.getDefault())

    private data class TargetKey(val alignmentId: String, val chainageM: Double, val offsetM: Double)

    @Volatile private var cached: Pair<TargetKey, StakeTarget?>? = null

    private fun designTarget(project: Project, m: Model, target: AxisStakeResult.Target): StakeTarget? {
        val key = TargetKey(m.alignment.id, target.chainageM, target.offsetM)
        cached?.let { (k, t) -> if (k == key && t != null) return t }
        val typed = TypedCoordinates(EntryForm.PROJECT_CRS, target.point.easting, target.point.northing, null, HeightKind.PROJECT)
        val coordinates = (ManualCoordinates(calculator.get()).compute(typed, project.crs, project.heightSystem) as? ManualResult.Computed)
            ?.coordinates
        val result = coordinates?.let {
            StakeTarget(
                id = "axis:${m.alignment.name}:${(target.chainageM * MILLIMETRES).roundToLong()}",
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
        if (!m.started || m.closed != null) return null
        if (!gear.state.value.usable) return null // S046/RF-28
        val target = targetOf(m) ?: return null
        val design = designTarget(project, m, target) ?: return null
        val mode: TiltMode? = tilt.state.value.settings.mode
        return StakeContext(
            settings,
            design,
            admittedFlow.value,
            mode,
            poleHeightM,
            project,
            calculator.get(),
            designElevationM = target.elevationM,
            profile = profile,
            shift = activeShift.value?.vector, // S020/RF-7
        )
    }

    /**
     * El objetivo de ahora para guardar el punto replanteado (S054/RF-10): el punto de diseño con la cota de la rasante más el espesor,
     * su PK y su desplazamiento, y cómo calcular de un punto medido la diferencia de PK y de desplazamiento. Null sin punto de diseño.
     */
    private fun currentObjective(): StakeObjective? {
        val project = project ?: return null
        val m = model.value ?: return null
        if (!m.started || m.closed != null) return null
        val target = targetOf(m) ?: return null
        val design = designTarget(project, m, target)?.coordinates ?: return null
        val locate = { measured: PointCoordinates ->
            val e = measured.easting
            val n = measured.northing
            if (e == null || n == null) null else m.geometry.locate(e, n)
        }
        return StakeObjective(
            design = design.copy(height = target.elevationM),
            elevationM = target.elevationM,
            mode = null,
            chainageM = target.chainageM,
            lateralOffsetM = target.offsetM,
            lateralErrorOf = { measured -> locate(measured)?.let { it.offset - target.offsetM } },
            thicknessM = target.thicknessM,
            chainageErrorOf = { measured -> locate(measured)?.let { it.chainage - target.chainageM } },
        )
    }

    private companion object {
        const val MILLIMETRES = 1_000.0
    }
}
