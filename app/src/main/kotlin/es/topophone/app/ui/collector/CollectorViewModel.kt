package es.topophone.app.ui.collector

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.Lazy
import dagger.hilt.android.lifecycle.HiltViewModel
import es.topophone.app.receiver.BaseNotices
import es.topophone.app.receiver.CurrentTilt
import es.topophone.app.receiver.CurrentTiltState
import es.topophone.app.receiver.VisibleNotices
import es.topophone.app.ui.stakeout.StakeObjectiveProvider
import es.topophone.app.ui.stakeout.StakeSaveInfo
import es.topophone.app.ui.stakeout.StakeTarget
import es.topophone.core.gnss.nmea.Epoch
import es.topophone.core.gnss.nmea.SolutionStatus
import es.topophone.core.gnss.nmea.TiltData
import es.topophone.core.gnss.nmea.TiltState
import es.topophone.core.model.format.DurationText
import es.topophone.core.model.format.LengthFormat
import es.topophone.core.model.log.AppLog
import es.topophone.core.model.log.LogCategory
import es.topophone.core.model.point.AutoTrigger
import es.topophone.core.model.point.CollectorSettings
import es.topophone.core.model.point.GeodeticPosition
import es.topophone.core.model.point.Measurement
import es.topophone.core.model.point.MeasurementMode
import es.topophone.core.model.point.Point
import es.topophone.core.model.point.PointBase
import es.topophone.core.model.point.PointCoordinates
import es.topophone.core.model.point.PointNames
import es.topophone.core.model.point.PointQuality
import es.topophone.core.model.point.PrecisionFailure
import es.topophone.core.model.point.StakeSource
import es.topophone.core.model.point.StakeoutNames
import es.topophone.core.model.point.StakeoutRef
import es.topophone.core.model.point.TiltMode
import es.topophone.core.model.project.LinearUnit
import es.topophone.core.model.project.Project
import es.topophone.core.survey.AutoAction
import es.topophone.core.survey.AutoOutcome
import es.topophone.core.survey.AverageStatsSnapshot
import es.topophone.core.survey.BaseFix
import es.topophone.core.survey.Baseline
import es.topophone.core.survey.Dispersion
import es.topophone.core.survey.GroundEN
import es.topophone.core.survey.MeasurementFailure
import es.topophone.core.survey.MeasurementResult
import es.topophone.core.survey.MeasurementUpdate
import es.topophone.core.survey.PointMeasurement
import es.topophone.core.survey.PoleHeight
import es.topophone.core.survey.PositionUnavailable
import es.topophone.core.survey.ProjectPositionCalculator
import es.topophone.core.survey.ReceiverModel
import es.topophone.core.survey.StakeoutDiff
import es.topophone.core.survey.TiltEpoch
import es.topophone.core.survey.TiltGuide
import es.topophone.core.survey.TiltSetup
import es.topophone.core.survey.VerticalPole
import es.topophone.data.point.CollectorPreferences
import es.topophone.data.point.PointRepository
import es.topophone.data.point.PointSaveResult
import es.topophone.data.project.ProjectRepository
import es.topophone.data.receiver.ReceiverPreferences
import es.topophone.receiver.platform.Alerts
import es.topophone.receiver.session.ConnectionState
import es.topophone.receiver.session.ReceiverSession
import es.topophone.receiver.session.ReceiverSnapshot
import es.topophone.receiver.session.SessionEpoch
import java.util.Locale
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Por qué no se puede medir ahora (S010/RF-3, RF-12; sin modo de inclinación o fuera del estado 30, S016/RF-2, RF-18). */
enum class MeasureBlocker {
    INVALID_NAME,
    NAME_TAKEN,
    INVALID_POLE,
    INVALID_SECONDS,
    NO_POSITION,
    OUTSIDE_CRS_AREA,
    TILT_MODE_MISSING,
    TILT_NOT_READY,

    /** El promediado dura más que el intervalo del modo automático por tiempo (S017/RF-18). */
    AUTO_INTERVAL_TOO_SHORT,
    INVALID_AUTO_INTERVAL,
    INVALID_AUTO_DISTANCE,
}

/** Por qué una medida se ha cancelado sola (S010/RF-12; al cambiar el modo de inclinación, S016/RF-28). */
enum class CollectorError { NO_DATA, OUTSIDE_CRS_AREA, TILT_MODE_CHANGED }

/** Solución y precisiones de la GST del receptor (S010/RF-6). */
data class LiveQuality(val solution: SolutionStatus, val horizontalM: Double?, val verticalM: Double?)

/** Tiempo reunido de un promediado y épocas usadas y descartadas (S010/RF-9; S017/RF-2). */
data class MeasureProgress(val elapsedS: Double, val requiredS: Double, val epochsUsed: Int = 0, val epochsDiscarded: Int = 0)

/**
 * Las estadísticas de un promediado en curso y su gráfico de dispersión (S017/RF-3 a RF-5). Salen en su propio flujo,
 * a 4 Hz, para que [CollectorUiState] solo cambie con los sucesos (RNF-T13).
 */
data class AverageStatsUi(val snapshot: AverageStatsSnapshot, val dispersion: Dispersion?)

/** Por qué un promediado está parado: un corte, o un corte de más de 5 min que pide decidir (S017/RF-8, RF-10). */
enum class PauseState { PAUSED, LONG_PAUSE }

/** Medida terminada que no cumple el control de precisión, a la espera de confirmar (S010/RF-14). */
data class PendingPoint(val failures: Set<PrecisionFailure>)

/** Lo que muestra el recolector (S010/RF-1 a RF-19). */
data class CollectorUiState(
    val loaded: Boolean = false,
    val name: String = "",
    val description: String = "",
    val poleHeightText: String = "",
    val poleHeightValid: Boolean = true,
    val settings: CollectorSettings = CollectorSettings(),
    /** Segundos del promediado tal como se escriben; solo se guardan si están dentro del rango (S010/RF-9). */
    val averagingSecondsText: String = DurationText.format(CollectorSettings().averagingSeconds),
    val averagingSecondsValid: Boolean = true,
    val live: LiveQuality? = null,
    val blocker: MeasureBlocker? = null,
    val measuring: Boolean = false,
    val progress: MeasureProgress? = null,
    val solutionLost: Boolean = false,
    /** La medida en curso descarta épocas por la inclinación, con la guía de la primera (S016/RF-19). */
    val tiltLost: TiltGuide? = null,
    /** "Terminar" sin ninguna época usada: no se ha guardado nada y la medida sigue (S017/RF-7). */
    val noEpochsYet: Boolean = false,
    /** El promediado está en pausa por un corte, o pregunta qué hacer tras 5 min (S017/RF-8, RF-10). */
    val paused: PauseState? = null,
    val pending: PendingPoint? = null,
    /** Último punto guardado, para decirlo (S010/RF-19). */
    val savedName: String? = null,
    /** Las diferencias del último punto replanteado que se ha guardado, hasta la medida siguiente (S018/RF-29). */
    val savedStake: StakeoutRef? = null,
    val error: CollectorError? = null,
    /** Avisos de la base que no se han cerrado (S015/RF-15, RF-17). */
    val notices: VisibleNotices = VisibleNotices(null, null, 0),
    /** La medida en curso empezó de nuevo porque cambió la base (S015/RF-18). */
    val restarted: Boolean = false,
    /** El tiempo promediado que se descartó con ese reinicio, si había (S017/RF-30). */
    val restartedDiscardedS: Double? = null,
    /** El modo de inclinación del receptor en uso (S016/RF-15). */
    val tiltMode: TiltMode? = null,
    /** La guía de la inclinación, mientras el modo la use y la época no esté en estado 30 sin aviso (S016/RF-12). */
    val tiltGuide: TiltGuide? = null,
    /** La opción "Automático" está encendida; no se guarda y el recolector abre siempre en manual (S017/RF-15). */
    val autoEnabled: Boolean = false,
    val auto: AutoUiState = AutoUiState(),
    /** El intervalo y la distancia del modo automático tal como se escriben (S017/RF-16, RF-17). */
    val autoIntervalText: String = DurationText.format(CollectorSettings().autoIntervalS),
    val autoIntervalValid: Boolean = true,
    val autoDistanceText: String = "1,000",
    val autoDistanceValid: Boolean = true,
)

/**
 * Recolector de un proyecto (S010): propone el nombre, mide con [PointMeasurement] a partir de todas las épocas de
 * la sesión, aplica el control de precisión y guarda el punto con su sonido.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class CollectorViewModel @Inject constructor(
    private val projects: ProjectRepository,
    private val points: PointRepository,
    private val collectorPreferences: CollectorPreferences,
    private val receiverPreferences: ReceiverPreferences,
    private val session: ReceiverSession,
    private val alerts: Alerts,
    private val calculator: Lazy<ProjectPositionCalculator>,
    private val inProgress: MeasurementInProgress = MeasurementInProgress(),
    private val log: AppLog = AppLog.NONE,
    private val baseNotices: BaseNotices = BaseNotices(),
    private val tilt: CurrentTilt = CurrentTilt.NONE,
) : ViewModel() {
    private data class Form(
        val loaded: Boolean = false,
        val name: String = "",
        val description: String = "",
        val poleText: String = "",
        /** Null mientras no se escriba: se ven los segundos guardados. */
        val secondsText: String? = null,
        val autoIntervalText: String? = null,
        val autoDistanceText: String? = null,
    )

    private data class Run(
        val measuring: Boolean = false,
        val progress: MeasureProgress? = null,
        val solutionLost: Boolean = false,
        val tiltLost: TiltGuide? = null,
        val noEpochsYet: Boolean = false,
        val paused: PauseState? = null,
        val pending: PendingPoint? = null,
        val savedName: String? = null,
        val savedStake: StakeoutRef? = null,
        val error: CollectorError? = null,
        val restarted: Boolean = false,
        val restartedDiscardedS: Double? = null,
    )

    private val projectId = MutableStateFlow<String?>(null)
    private var project: Project? = null
    private val form = MutableStateFlow(Form())
    private val nameTaken = MutableStateFlow(false)
    private val run = MutableStateFlow(Run())
    private var measurement: PointMeasurement? = null

    /** El punto de diseño que se replantea, o null si el recolector mide como siempre (S018/RF-26). */
    private var stake: StakeTarget? = null

    /** El nombre y la descripción del recolector antes de empezar el replanteo, para devolverlos al terminar (S018/RF-26). */
    private var stakeBackup: Pair<String, String>? = null

    /** La cota de diseño usada y las tolerancias vigentes que se guardan con el replanteo (S019/RF-13); null: como en S018. */
    private var stakeSaveInfo: StakeSaveInfo? = null

    /** De una línea del dibujo, quien da el objetivo de ahora al terminar la medida (S041/RF-28); null con un punto fijo. */
    private var stakeObjective: StakeObjectiveProvider? = null
    private val statsFlow = MutableStateFlow<AverageStatsUi?>(null)
    private var lastStatsAtMs = 0L

    /** Los milisegundos entre dos publicaciones de las estadísticas; los tests mandan las épocas sin pausa y lo ponen a 0. */
    internal var statsIntervalMs = STATS_INTERVAL_MS
    private var measuringReplay = false
    private val autoEnabled = MutableStateFlow(false)
    private val autoCollector = AutoCollector(log, alerts)
    private var lastEpochAtMs = 0L
    private var autoRevision: Int? = null
    private var lastAutoGround: GroundEN? = null

    /** La medida en curso es de un punto del modo automático (S017/RF-14). */
    private var measuringAuto = false
    private var pendingResult: MeasurementResult? = null
    private var ticker: Job? = null
    private var nameCheck: Job? = null

    @Volatile private var lastEpochUtc: Int? = null

    /** La hora UTC de la última época que el recolector ha recibido; los tests esperan a ella antes de medir. */
    internal val lastEpochUtcMs: Int? get() = lastEpochUtc
    private var epochIntervalS = DEFAULT_INTERVAL_S

    /** Crea la medida con los datos de "Medir", para empezarla de nuevo si cambia la base (S015/RF-18). */
    private var newMeasurement: (() -> PointMeasurement)? = null

    /** Número del último cambio de base al empezar la medida, y en la última época (plan de S015, A10). */
    private var measurementBaseChange: Int? = null
    private var lastBaseChange: Int? = null

    /** La base de las épocas de la medida en curso; con el reinicio de RF-18 es siempre la misma (S015/RF-20). */
    private var measurementBase: BaseFix? = null

    /** La revisión del modo de inclinación al empezar la medida (S016/RF-28; plan A9). */
    private var measurementRevision: Int? = null

    init {
        // S013/RF-16: la medida y el punto que espera a confirmarse, fuera del recolector.
        viewModelScope.launch {
            combine(run, autoCollector.ui) { run, auto -> run.measuring || run.pending != null || auto.running }
                .distinctUntilChanged().collect(inProgress::set)
        }
    }

    override fun onCleared() {
        inProgress.set(false)
    }

    private val projectFlow = projectId.flatMapLatest { id ->
        if (id ==
            null
        ) {
            flowOf(null)
        } else {
            projects.observe(id)
        }
    }.onEach { project = it }

    private val baseFormState = combine(form, nameTaken, run, collectorPreferences.settings) { form, taken, run, settings ->
        val secondsText = form.secondsText ?: DurationText.format(settings.averagingSeconds)
        val secondsValid = parseSeconds(secondsText) != null
        val unit = project?.linearUnit ?: LinearUnit.METRE
        val intervalText = form.autoIntervalText ?: DurationText.format(settings.autoIntervalS)
        val distanceText = form.autoDistanceText ?: LengthFormat.inUnit(settings.autoDistanceM, unit, Locale.getDefault())
        CollectorUiState(
            loaded = form.loaded,
            name = form.name,
            description = form.description,
            poleHeightText = form.poleText,
            poleHeightValid = PoleHeight.parse(form.poleText) != null,
            settings = settings,
            averagingSecondsText = secondsText,
            averagingSecondsValid = secondsValid,
            blocker = formBlocker(form, taken) ?: MeasureBlocker.INVALID_SECONDS.takeIf {
                settings.mode == MeasurementMode.AVERAGED && !secondsValid
            },
            measuring = run.measuring,
            progress = run.progress,
            solutionLost = run.solutionLost,
            tiltLost = run.tiltLost,
            noEpochsYet = run.noEpochsYet,
            paused = run.paused,
            pending = run.pending,
            savedName = run.savedName,
            savedStake = run.savedStake,
            error = run.error,
            restarted = run.restarted,
            restartedDiscardedS = run.restartedDiscardedS,
            autoIntervalText = intervalText,
            autoIntervalValid = DurationText.parse(intervalText, CollectorSettings.AUTO_INTERVAL_SECONDS) != null,
            autoDistanceText = distanceText,
            autoDistanceValid = CollectorSettings.parseAutoDistance(distanceText, unit) != null,
        )
    }

    /** Con el modo automático: los bloqueos de sus ajustes (S017/RF-18). */
    private val formState = combine(baseFormState, autoEnabled, autoCollector.ui) { base, enabled, auto ->
        base.copy(autoEnabled = enabled, auto = auto, blocker = base.blocker ?: autoBlocker(base, enabled))
    }

    /** El proyecto ya ha llegado de Room: hasta entonces `measure()` se sale en silencio; los tests lo esperan. */
    internal val isProjectLoaded: Boolean get() = project != null

    /** Las estadísticas del promediado en curso, a 4 Hz; null sin promediado (S017/RF-3 a RF-5). */
    val stats: StateFlow<AverageStatsUi?> = statsFlow

    /** Lo que falta para el siguiente punto del modo automático (S017/RF-26). */
    val autoLive: StateFlow<AutoLive?> = autoCollector.live

    val state: StateFlow<CollectorUiState> = combine(
        formState,
        projectFlow,
        session.state,
        baseNotices.dismissed,
        tilt.state,
    ) { state, project, snapshot, dismissed, tiltState ->
        val gga = snapshot.gga?.takeIf { snapshot.connection is ConnectionState.Receiving && it.hasPosition }
        val epochTilt = snapshot.epoch?.tilt?.takeIf { snapshot.connection is ConnectionState.Receiving }
        val mode = tiltState.settings.mode
        val live = gga?.let { liveQuality(it.status, snapshot, mode, epochTilt, state.poleHeightText) }
        state.copy(
            live = live,
            blocker = state.blocker ?: positionBlocker(snapshot, project, state.poleHeightText) ?: tiltBlocker(tiltState, epochTilt),
            notices = BaseNotices.visible(gga, snapshot.base, dismissed), // S015/RF-15, RF-17
            tiltMode = mode,
            tiltGuide = if (mode?.usesTilt == true) TiltEpoch.guide(epochTilt) else null, // S016/RF-12
        )
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.Eagerly, CollectorUiState())

    init {
        viewModelScope.launch { session.epochs.collect(::onEpoch) }
        // S014/RF-5: la guía de la inclinación queda en el log cada vez que aparece o cambia.
        viewModelScope.launch {
            state.map { it.tiltGuide }.distinctUntilChanged().collect { guide ->
                guide?.let { log.warning(LogCategory.RECEIVER, "Guía de la inclinación: ${it.logText()}") }
            }
        }
        // S016/RF-28: cambiar el modo o abrir el asistente durante una medida la cancela sin guardar nada.
        viewModelScope.launch {
            tilt.state.map { it.revision }.distinctUntilChanged().collect { revision ->
                val measuringChanged = measurement != null && measurementRevision != null && revision != measurementRevision
                val autoChanged = autoCollector.running && autoRevision != null && revision != autoRevision
                if (measuringChanged || autoChanged) cancelForTiltMode() // S016/RF-28; con el modo automático, S017/RF-29
            }
        }
    }

    fun start(id: String) {
        if (projectId.value != null) return
        projectId.value = id
        viewModelScope.launch {
            val pole = receiverPreferences.poleHeightM.first()
            form.value = Form(loaded = true, name = PointNames.next(points.lastName(id)), poleText = formatPole(pole)) // S010/RF-2
            checkName()
        }
    }

    fun setName(name: String) {
        form.update { it.copy(name = name) }
        checkName()
    }

    /**
     * El punto de diseño del replanteo, o null al cerrarlo (S018/A5, RF-26 a RF-28): el recolector propone `<diseño>_stkd`,
     * con la descripción del diseño, y al guardar crea un punto replanteado. Con una medida en marcha no cambia (RF-30).
     */
    @Synchronized
    fun setStakeTarget(target: StakeTarget?) {
        val id = projectId.value ?: return
        if (run.value.measuring || run.value.pending != null) return
        if (target == null) {
            val backup = stakeBackup
            stake = null
            stakeBackup = null
            stakeSaveInfo = null
            stakeObjective = null
            run.value = Run()
            if (backup != null) form.update { it.copy(name = backup.first, description = backup.second) }
            checkName()
            return
        }
        if (stake == null) stakeBackup = form.value.name to form.value.description
        stake = target
        run.value = Run()
        viewModelScope.launch {
            val taken = points.names(id)
            form.update { it.copy(name = stakeName(target, taken), description = target.description) }
            checkName()
        }
    }

    /**
     * La cota de diseño usada y las tolerancias vigentes del replanteo, que se guardan con el punto replanteado y con las que se
     * decide si cumple (S019/RF-13, RF-14). Sin ellas el replanteo se guarda como antes de S019, sin evaluar (RF-26).
     */
    @Synchronized
    fun setStakeSaveInfo(info: StakeSaveInfo?) {
        stakeSaveInfo = info
    }

    /** Quien da el objetivo de una línea del dibujo en el momento de guardar, o null con un punto fijo (S041/RF-28). */
    @Synchronized
    fun setStakeObjective(provider: StakeObjectiveProvider?) {
        stakeObjective = provider
    }

    /** `<capa>_<n>_stkd` con un elemento de un dibujo (S041/RF-27) y `<diseño>_stkd` con un punto del proyecto (S018/RF-27). */
    private fun stakeName(target: StakeTarget, taken: Set<String>): String = if (target.source is StakeSource.CadElement) {
        StakeoutNames.nextForLayer(
            target.name,
            taken,
        )
    } else {
        StakeoutNames.next(target.name, taken)
    }

    /** La descripción se conserva para el siguiente punto (S010/RF-4). */
    fun setDescription(description: String) = form.update { it.copy(description = description) }

    /** La misma altura del jalón que en Coordenadas (S010/RF-5). */
    fun setPoleHeight(text: String) {
        form.update { it.copy(poleText = text) }
        PoleHeight.parse(text)?.let { pole -> viewModelScope.launch { receiverPreferences.savePoleHeight(pole) } }
    }

    /** Se escribe en minutos y segundos (o en segundos) y solo se guarda de 1 s a 60 min; fuera de eso no se puede promediar (S017/RF-1). */
    fun setAveragingSeconds(text: String) {
        if (autoCollector.running) return // S017/RF-32
        form.update { it.copy(secondsText = text) }
        val seconds = parseSeconds(text) ?: return
        viewModelScope.launch { collectorPreferences.save(collectorPreferences.settings.first().copy(averagingSeconds = seconds)) }
    }

    /** Para todos los proyectos (S010/RF-13). */
    fun setSettings(settings: CollectorSettings) {
        viewModelScope.launch {
            val current = collectorPreferences.settings.first()
            // S017/RF-32: con el modo automático en marcha, la medida y el disparo no cambian; el control de precisión sí.
            val effective = if (autoCollector.running) {
                settings.copy(
                    mode = current.mode,
                    averagingSeconds = current.averagingSeconds,
                    autoTrigger = current.autoTrigger,
                    autoIntervalS = current.autoIntervalS,
                    autoDistanceM = current.autoDistanceM,
                )
            } else {
                settings
            }
            collectorPreferences.save(effective)
        }
    }

    /** Enciende o apaga la opción "Automático"; no se guarda, y con el modo en marcha no se toca (S017/RF-15, RF-32). */
    fun setAutoEnabled(enabled: Boolean) {
        if (!autoCollector.running) autoEnabled.value = enabled
    }

    fun setAutoTrigger(trigger: AutoTrigger) = changeAutoSettings { it.copy(autoTrigger = trigger) }

    /** El intervalo del disparo por tiempo, en min:s o en segundos (S017/RF-16). */
    fun setAutoInterval(text: String) {
        if (autoCollector.running) return
        form.update { it.copy(autoIntervalText = text) }
        DurationText.parse(text, CollectorSettings.AUTO_INTERVAL_SECONDS)?.let { seconds ->
            changeAutoSettings { it.copy(autoIntervalS = seconds) }
        }
    }

    /** La distancia del disparo por distancia, en la unidad del proyecto (S017/RF-17). */
    fun setAutoDistance(text: String) {
        if (autoCollector.running) return
        form.update { it.copy(autoDistanceText = text) }
        val unit = project?.linearUnit ?: LinearUnit.METRE
        CollectorSettings.parseAutoDistance(text, unit)?.let { metres -> changeAutoSettings { it.copy(autoDistanceM = metres) } }
    }

    private fun changeAutoSettings(change: (CollectorSettings) -> CollectorSettings) {
        if (autoCollector.running) return
        viewModelScope.launch { collectorPreferences.save(change(collectorPreferences.settings.first())) }
    }

    @Synchronized
    fun measure() {
        val current = state.value
        project ?: return
        PoleHeight.parse(current.poleHeightText) ?: return
        if (autoCollector.running || current.blocker != null || current.measuring || current.pending != null) return
        startPoint(auto = false)
    }

    /** Empieza un punto con los datos del recolector de ahora, a mano o desde el modo automático (S010/RF-8, RF-9; S017/RF-19). */
    private fun startPoint(auto: Boolean) {
        val project = project ?: return
        val pole = PoleHeight.parse(form.value.poleText) ?: return
        val settings = state.value.settings
        // S016/RF-13, RF-14: el modo del receptor al empezar; sin modo, sin ETC o con la inclinación apagada, jalón vertical.
        val tiltState = tilt.state.value
        val tiltSetup = TiltSetup(tiltState.settings.mode ?: TiltMode.VERTICAL, tiltState.settings.verified)
        measurementRevision = tiltState.revision
        val factory = {
            PointMeasurement(
                mode = settings.mode,
                averagingSeconds = settings.validAveragingSeconds,
                fixOnly = settings.precision.fixOnly,
                poleHeightM = pole,
                crs = project.crs,
                heightSystem = project.heightSystem,
                epochIntervalS = epochIntervalS,
                startedAtMs = System.currentTimeMillis(),
                calculator = calculator.get(),
                tilt = tiltSetup,
            )
        }
        newMeasurement = factory
        measurementBaseChange = lastBaseChange
        measurementBase = null
        measurement = factory()
        measuringReplay = session.state.value.replay != null
        measuringAuto = auto
        run.value = Run(measuring = true)
        ensureTicker()
    }

    /** El reloj de la medida y del modo automático, mientras haya una de las dos cosas (S010/RF-12; S017/RF-8, RF-28). */
    private fun ensureTicker() {
        if (ticker != null) return
        ticker = viewModelScope.launch {
            while (isActive) {
                delay(TICK_MS)
                tick(System.currentTimeMillis())
            }
        }
    }

    /**
     * "Iniciar" del modo automático: el primer punto de inmediato y los demás cuando toque (S017/RF-15, RF-19). Con los
     * mismos bloqueos que "Medir": el nombre repetido (RF-33), la inclinación sin estado 30 (RF-29) y el intervalo más
     * corto que el promediado (RF-18).
     */
    @Synchronized
    fun startAuto() {
        val current = state.value
        if (!current.autoEnabled || autoCollector.running || current.blocker != null || current.measuring || current.pending != null) return
        if (stake != null) return // el modo automático no se mezcla con un replanteo (S018)
        val project = project ?: return
        val pole = PoleHeight.parse(current.poleHeightText) ?: return
        val epoch = session.state.value.epoch ?: return
        autoRevision = tilt.state.value.revision
        lastEpochAtMs = System.currentTimeMillis()
        val ground = autoCollector.ground(epoch, tilt.state.value.settings.mode, pole, project, calculator.get())
        lastAutoGround = ground
        run.value = Run()
        applyAuto(autoCollector.start(current.settings, epoch.utcMillisOfDay, ground))
        ensureTicker()
    }

    /** "Parar": detiene el modo, descarta el punto en curso y conserva los guardados (S017/RF-27). */
    @Synchronized
    fun stopAuto() {
        if (!autoCollector.running) return
        measurement?.cancel()
        // Antes de parar el modo, para que el estado no pase por "parado" con una medida todavía en curso.
        run.value = Run()
        autoCollector.stop()
        stopAutoState()
        stopMeasuring()
    }

    private fun stopAutoState() {
        measuringAuto = false
        autoRevision = null
        lastAutoGround = null
    }

    /** Lo que pide el modo automático: empezar un punto u omitir el que no terminó (S017/RF-19, RF-24). */
    private fun applyAuto(actions: List<AutoAction>) {
        for (action in actions) {
            when (action) {
                AutoAction.StartPoint -> if (autoCollector.running) startPoint(auto = true) // no tras "Parar"
                is AutoAction.OmitCurrent -> {
                    measurement?.cancel()
                    stopMeasuring()
                    run.value = Run()
                }
            }
        }
    }

    /** Una época con el modo automático en marcha: reanuda si había un corte y mira si toca otro punto (S017/RF-20, RF-21, RF-28). */
    private fun onAutoEpoch(epoch: Epoch) {
        if (autoCollector.paused) autoCollector.resume(epoch.utcMillisOfDay)
        val project = project ?: return
        val pole = PoleHeight.parse(form.value.poleText) ?: return
        val ground = autoCollector.ground(epoch, tilt.state.value.settings.mode, pole, project, calculator.get())
        lastAutoGround = ground
        applyAuto(autoCollector.onEpoch(epoch.utcMillisOfDay, ground))
    }

    private fun autoBlocker(state: CollectorUiState, enabled: Boolean): MeasureBlocker? {
        if (!enabled) return null
        val settings = state.settings
        return when {
            settings.autoTrigger == AutoTrigger.TIME && !state.autoIntervalValid -> MeasureBlocker.INVALID_AUTO_INTERVAL
            settings.autoTrigger == AutoTrigger.DISTANCE && !state.autoDistanceValid -> MeasureBlocker.INVALID_AUTO_DISTANCE
            settings.autoTrigger == AutoTrigger.TIME &&
                settings.mode == MeasurementMode.AVERAGED &&
                settings.averagingSeconds > settings.validAutoIntervalS -> MeasureBlocker.AUTO_INTERVAL_TOO_SHORT
            else -> null
        }
    }

    @Synchronized
    private fun cancelForTiltMode() {
        measurement?.cancel()
        log.warning(LogCategory.POINTS, "Medida de ${measuredName()} cancelada: ha cambiado el modo de inclinación") // S014/RF-5
        run.value = Run(error = CollectorError.TILT_MODE_CHANGED)
        if (autoCollector.running) {
            autoCollector.stop() // S017/RF-29: como "Parar"
            stopAutoState()
        }
        stopMeasuring()
    }

    /** El reloj de la medida, con la hora del móvil: la instantánea sin datos se cancela y la promediada se pausa (S017/RF-8). */
    @Synchronized
    internal fun tick(nowMs: Long) {
        measurement?.onTick(nowMs)?.let(::handle)
        if (autoCollector.running) {
            // S017/RF-28: sin épocas en 2 s hay un corte; si hay un promediado en curso, él ya avisa de la pausa.
            if (!autoCollector.paused && nowMs - lastEpochAtMs >= NO_DATA_MS) autoCollector.pause(alert = measurement == null)
            autoCollector.refreshLive(lastAutoGround)
        }
        publishStats()
    }

    /** "Seguir esperando" a la pregunta de 5 min: sigue pausada y se vuelve a preguntar si el corte sigue 5 min más (S017/RF-10). */
    @Synchronized
    fun keepWaiting() {
        measurement?.keepWaiting(System.currentTimeMillis())
        log.info(LogCategory.POINTS, "Medida de ${measuredName()}: se sigue esperando los datos del receptor") // S014/RF-5
        run.update { it.copy(paused = PauseState.PAUSED) }
    }

    /** "Terminar": cierra el promediado con las épocas reunidas y lo guarda como cualquier medida (S017/RF-6, RF-7). */
    @Synchronized
    fun finishNow() {
        val update = measurement?.finishNow() ?: return
        log.info(LogCategory.POINTS, "Medida de ${measuredName()}: Terminar con las épocas reunidas") // S014/RF-5
        handle(update)
    }

    /**
     * Publica las estadísticas del promediado a 4 Hz como mucho (S017/RNF-T13), con el reloj del móvil: se pide desde cada época
     * y desde el reloj de la medida, que cubre los cortes en que no llegan épocas.
     */
    private fun publishStats() {
        val current = measurement
        if (current == null) {
            statsFlow.value = null
            return
        }
        val now = System.currentTimeMillis()
        if (now - lastStatsAtMs < statsIntervalMs) return
        lastStatsAtMs = now
        statsFlow.value = current.stats()?.let { AverageStatsUi(it, current.dispersion()) }
    }

    /** S010/RF-11: no se guarda nada. */
    @Synchronized
    fun cancel() {
        // S017/RF-27: con el modo automático en marcha no hay "Cancelar", salvo para descartar el punto de la pregunta de 5 min.
        if (autoCollector.running) {
            if (run.value.paused != PauseState.LONG_PAUSE) return
            autoCollector.onPointCancelled()
        }
        measurement?.cancel()
        stopMeasuring()
        run.value = Run()
    }

    /** S010/RF-15: se guarda marcado como fuera de tolerancia. */
    @Synchronized
    fun confirmOutOfTolerance() {
        val result = pendingResult ?: return
        val failures = run.value.pending?.failures.orEmpty()
        pendingResult = null
        run.update { it.copy(pending = null) }
        save(result, failures)
    }

    @Synchronized
    fun discardPending() {
        log.info(LogCategory.POINTS, "Medida de ${measuredName()} descartada") // S014/RF-5
        pendingResult = null
        run.value = Run()
    }

    /** Cierra "La base ha cambiado" de ese cambio (S015/RF-17). */
    fun dismissBaseChange(count: Int) = baseNotices.dismissChange(count)

    /** Cierra "Línea base larga" para esa base (S015/RF-15). */
    fun dismissLongBaseline(serial: Int) = baseNotices.dismissLongBaseline(serial)

    @Synchronized
    private fun onEpoch(received: SessionEpoch) {
        val epoch = received.epoch
        // S015/RF-18: la época llega con la base que había al emitirla; si cambió, la medida empieza de nuevo antes de usarla.
        val baseChange = received.base.change?.count
        if (baseChange != null) lastBaseChange = baseChange
        if (measurement != null && baseChange != null && baseChange != measurementBaseChange) restartMeasurement(baseChange)
        // Intervalo del receptor: la menor separación entre épocas seguidas, de 0,05 a 1 s.
        lastEpochUtc?.let { previous ->
            val delta = (epoch.utcMillisOfDay - previous) / 1_000.0
            if (delta > 0) epochIntervalS = delta.coerceIn(MIN_INTERVAL_S, DEFAULT_INTERVAL_S)
        }
        lastEpochUtc = epoch.utcMillisOfDay
        lastEpochAtMs = System.currentTimeMillis()
        if (measurement != null) received.base.position?.let { measurementBase = it }
        measurement?.onEpoch(epoch, lastEpochAtMs, received.gst)?.forEach(::handle) // S010/RF-22
        if (autoCollector.running) onAutoEpoch(epoch)
        publishStats()
    }

    private fun handle(update: MeasurementUpdate) {
        when (update) {
            is MeasurementUpdate.Progress -> run.update {
                it.copy(
                    progress = MeasureProgress(update.elapsedS, update.requiredS, update.epochsUsed, update.epochsDiscarded),
                    noEpochsYet = false,
                )
            }
            MeasurementUpdate.SolutionLost -> {
                log.warning(LogCategory.POINTS, "Solución perdida durante la medida de ${measuredName()}") // S014/RF-5
                alerts.solutionLost() // S010/RF-10
                run.update { it.copy(solutionLost = true) }
            }
            is MeasurementUpdate.TiltLost -> {
                // S014/RF-5
                val cause = update.guide.logText()
                log.warning(LogCategory.POINTS, "Épocas descartadas por la inclinación durante la medida de ${measuredName()}: $cause")
                alerts.tiltLost() // S016/RF-19
                run.update { it.copy(tiltLost = update.guide) }
            }
            // S017/RF-8 a RF-10: la pausa, la reanudación y la pregunta de 5 min llegan al recolector en T16.
            MeasurementUpdate.Paused -> {
                log.warning(LogCategory.POINTS, "Medida de ${measuredName()} en pausa: sin datos del receptor") // S014/RF-5
                alerts.measurementPaused() // S017/RF-8
                run.update { it.copy(paused = PauseState.PAUSED) }
            }
            MeasurementUpdate.Resumed -> {
                log.info(LogCategory.POINTS, "Medida de ${measuredName()} reanudada: han vuelto los datos del receptor") // S014/RF-5
                run.update { it.copy(paused = null) } // S017/RF-9: también cierra la pregunta de 5 min
            }
            MeasurementUpdate.LongPause -> {
                // S014/RF-5
                log.warning(LogCategory.POINTS, "Corte de más de 5 min en la medida de ${measuredName()}: se pregunta qué hacer")
                run.update { it.copy(paused = PauseState.LONG_PAUSE) } // S017/RF-10
            }
            MeasurementUpdate.NoEpochsYet -> run.update { it.copy(noEpochsYet = true) } // S017/RF-7
            is MeasurementUpdate.Failed -> {
                stopMeasuring()
                if (measuringAuto && autoCollector.running) {
                    // S017/RF-24, RF-28: un corte no es un punto omitido; lo demás, sí.
                    if (update.reason == MeasurementFailure.NO_DATA) {
                        autoCollector.onPointCancelled()
                    } else {
                        autoCollector.onOutcome(AutoOutcome.Omitted("fuera del ámbito del SC del proyecto"))
                    }
                    run.value = Run()
                    return
                }
                val error = when (update.reason) {
                    MeasurementFailure.NO_DATA -> CollectorError.NO_DATA
                    MeasurementFailure.OUTSIDE_CRS_AREA -> CollectorError.OUTSIDE_CRS_AREA
                }
                // S014/RF-5
                val cause = if (error == CollectorError.NO_DATA) "sin datos del receptor" else "fuera del ámbito del SC del proyecto"
                log.error(LogCategory.POINTS, "No se pudo medir ${measuredName()}: $cause")
                run.value = Run(error = error)
            }
            is MeasurementUpdate.Done -> {
                stopMeasuring()
                val result = update.result
                val failures = state.value.settings.precision.check(
                    result.solution == SolutionStatus.FIX,
                    result.horizontalM,
                    result.verticalM,
                    result.maxTiltRad, // S016/RF-20: null si el modo no usa la inclinación
                )
                val auto = measuringAuto && autoCollector.running
                if (failures.isEmpty()) {
                    save(result, failures)
                    if (auto) autoCollector.onOutcome(AutoOutcome.Saved(result.position.easting, result.position.northing))
                } else if (auto) {
                    // S017/RF-24: sin pedir confirmación punto a punto, el que no cumple se omite.
                    run.value = Run()
                    autoCollector.onOutcome(AutoOutcome.Omitted("no cumple el control de precisión: ${failures.joinToString { it.name }}"))
                } else {
                    pendingResult = result // S010/RF-14: se pregunta antes de guardar
                    log.warning(LogCategory.POINTS, "Medida de ${measuredName()} fuera de tolerancia: ${failures.joinToString { it.name }}")
                    run.value = Run(pending = PendingPoint(failures))
                }
            }
        }
    }

    private fun save(result: MeasurementResult, failures: Set<PrecisionFailure>) {
        val projectId = projectId.value ?: return
        val name = PointNames.clean(form.value.name)
        val auto = measuringAuto
        val measurement = Measurement(
            antenna = result.antenna,
            poleHeightM = result.poleHeightM,
            quality = PointQuality(
                solution = result.solution.name,
                satellites = result.satellites,
                hdop = result.hdop,
                correctionAgeS = result.correctionAgeS,
                horizontalM = result.horizontalM,
                verticalM = result.verticalM,
                mode = result.mode,
                epochsUsed = result.epochsUsed,
                epochsDiscarded = result.epochsDiscarded,
                fromReplay = measuringReplay,
                // S017/RF-12, RF-14: lo que dejó el promediado, y si lo midió el modo automático (T19).
                averagedTimeS = result.averagedTimeS,
                deviationHorizontalM = result.deviationHorizontalM,
                deviationVerticalM = result.deviationVerticalM,
                auto = measuringAuto,
            ),
            rawNmea = result.rawNmea,
            outOfTolerance = failures,
            measuredFromMs = result.measuredFromMs,
            measuredToMs = result.measuredToMs,
            coordinates = PointCoordinates(result.ground, result.position.easting, result.position.northing, result.position.height),
            base = measurementBase?.let { pointBase(it, result.antenna) }, // S015/RF-20
            tilt = result.tilt, // S016/RF-22
        )
        // S018/RF-28: el diseño por valor y lo que se desvió lo medido (medido menos diseño). S019/RF-13, RF-14: contra la cota
        // usada, con las tolerancias vigentes y si cumple.
        val staked = stake?.let { target ->
            val info = stakeSaveInfo
            val provider = stakeObjective
            val objective = provider?.current()
            if (provider != null && objective == null) {
                null // S041: una línea que aún no ha dado objetivo no tiene qué comparar
            } else if (objective != null) {
                // S041/RF-28: el objetivo en el momento de terminar la medida, con su origen, su modo, su PK y su error lateral.
                StakeoutDiff.ref(
                    target.id,
                    target.name,
                    objective.design,
                    measurement.coordinates,
                    elevationM = objective.elevationM,
                    toleranceHM = info?.toleranceHM,
                    toleranceVM = info?.toleranceVM,
                ).copy(
                    source = target.source,
                    mode = objective.mode,
                    chainageM = objective.chainageM,
                    lateralOffsetM = objective.lateralOffsetM,
                    dLateralM = objective.lateralErrorOf(measurement.coordinates),
                )
            } else if (info == null) {
                StakeoutDiff.ref(target.id, target.name, target.coordinates, measurement.coordinates).copy(source = target.source)
            } else {
                StakeoutDiff.ref(
                    target.id,
                    target.name,
                    target.coordinates,
                    measurement.coordinates,
                    elevationM = info.elevationM,
                    toleranceHM = info.toleranceHM,
                    toleranceVM = info.toleranceVM,
                ).copy(source = target.source)
            }
        }
        val point = Point.measured(
            id = UUID.randomUUID().toString(),
            projectId = projectId,
            name = name,
            description = form.value.description.trim(),
            measurement = measurement,
            createdAtMs = System.currentTimeMillis(),
            staked = staked,
        )
        // S017/RF-22: el modo automático propone ya el nombre siguiente, que la época que sigue puede necesitar enseguida.
        if (auto) form.update { if (PointNames.clean(it.name) == name) it.copy(name = PointNames.next(name)) else it }
        viewModelScope.launch {
            // S017/RF-23: si el nombre ya existe, el modo automático usa el siguiente libre.
            val taken = if (auto) points.names(projectId) else emptySet()
            val finalName = if (name in taken) PointNames.nextFree(name, taken) else name
            when (points.create(if (finalName == name) point else point.copy(name = finalName))) {
                PointSaveResult.Saved -> {
                    alerts.pointSaved() // S010/RF-19
                    val target = stake
                    if (target != null) {
                        // S018/RF-27: el replanteo siguiente del mismo punto de diseño, `_stkd2`…
                        val taken = points.names(projectId)
                        form.update { it.copy(name = stakeName(target, taken)) }
                    } else if (!auto) {
                        form.update { it.copy(name = PointNames.next(name)) }
                    }
                    run.value = Run(savedName = finalName, savedStake = staked)
                    checkName()
                }
                PointSaveResult.DuplicateName -> {
                    log.warning(LogCategory.POINTS, "No se guardó ${measuredName()}: ya hay un punto con ese nombre") // S014/RF-5
                    nameTaken.value = true
                    run.value = Run()
                }
                PointSaveResult.NotFound -> run.value = Run()
            }
        }
    }

    /**
     * La base tal como la dio su fuente, con la línea base desde la antena medida (S015/RF-20). Sin altura de la antena,
     * se mide a la altura de la base: casi la distancia horizontal.
     */
    private fun pointBase(base: BaseFix, antenna: GeodeticPosition) = PointBase(
        position = GeodeticPosition(base.latitudeDeg, base.longitudeDeg, base.ellipsoidalHeightM),
        source = base.source,
        stationId = base.stationId,
        antennaHeightM = base.antennaHeightM,
        baselineM = Baseline.distanceM(
            antenna.latitudeDeg,
            antenna.longitudeDeg,
            antenna.ellipsoidalHeightM ?: base.ellipsoidalHeightM,
            base.ecef,
        ),
    )

    /** El nombre del punto que se mide, para el log (sin coordenadas, S014/RF-7). */
    private fun measuredName(): String = PointNames.clean(form.value.name)

    private fun stopMeasuring() {
        // El reloj sigue mientras el modo automático esté en marcha: detecta los cortes y cuenta el tiempo del siguiente punto.
        if (!autoCollector.running) {
            ticker?.cancel()
            ticker = null
        }
        measurement = null
        statsFlow.value = null
        lastStatsAtMs = 0
        newMeasurement = null
        measurementRevision = null
    }

    /** Las épocas con la base vieja se descartan y la medida empieza de nuevo con la nueva (S015/RF-18). */
    private fun restartMeasurement(baseChange: Int) {
        measurement?.cancel()
        measurement = newMeasurement?.invoke()
        measurementBaseChange = baseChange
        measurementBase = null
        log.warning(LogCategory.POINTS, "Medida de ${measuredName()} empezada de nuevo: la base ha cambiado") // S014/RF-5
        // S017/RF-30: en un promediado se avisa de cuánto tiempo promediado se descarta.
        val discardedS = run.value.progress?.elapsedS?.takeIf { it > 0 }
        if (discardedS != null) {
            log.warning(
                LogCategory.POINTS,
                "Medida de ${measuredName()}: se descartan ${DurationText.format(discardedS.toInt())} de promediado",
            )
            alerts.measurementRestarted()
        }
        run.update {
            it.copy(
                progress = null,
                solutionLost = false,
                tiltLost = null,
                restarted = true,
                restartedDiscardedS = discardedS,
                paused = null,
            )
        }
    }

    private fun checkName() {
        val id = projectId.value ?: return
        val name = PointNames.clean(form.value.name)
        nameCheck?.cancel()
        nameCheck = viewModelScope.launch { nameTaken.value = name.isNotEmpty() && points.nameExists(id, name) } // S010/RF-3
    }

    private fun parseSeconds(text: String): Int? = DurationText.parse(text, CollectorSettings.AVERAGING_SECONDS)

    private fun formBlocker(form: Form, taken: Boolean): MeasureBlocker? = when {
        !PointNames.isValid(form.name) -> MeasureBlocker.INVALID_NAME
        taken -> MeasureBlocker.NAME_TAKEN
        PoleHeight.parse(form.poleText) == null -> MeasureBlocker.INVALID_POLE
        else -> null
    }

    /** S010/RF-12: sin posición, o fuera del ámbito del SC del proyecto. */
    private fun positionBlocker(snapshot: ReceiverSnapshot, project: Project?, poleText: String): MeasureBlocker? {
        val gga =
            snapshot.gga?.takeIf { snapshot.connection is ConnectionState.Receiving && it.hasPosition } ?: return MeasureBlocker.NO_POSITION
        val pole = PoleHeight.parse(poleText) ?: return null
        val project = project ?: return null
        val position = calculator.get().compute(gga, pole, project.crs, project.heightSystem) ?: return MeasureBlocker.NO_POSITION
        val outside =
            PositionUnavailable.OUTSIDE_CRS_AREA in position.unavailable || PositionUnavailable.OUTSIDE_ED50_GRID in position.unavailable
        return if (outside) MeasureBlocker.OUTSIDE_CRS_AREA else null
    }

    /** S010/RF-6 con S016/RF-21: en "Calculada por la app", la precisión de la GST más la de la inclinación de la época. */
    private fun liveQuality(
        status: SolutionStatus,
        snapshot: ReceiverSnapshot,
        mode: TiltMode?,
        epochTilt: TiltData?,
        poleText: String,
    ): LiveQuality {
        val pole = PoleHeight.parse(poleText)
        val accuracy = if (mode == TiltMode.APP && pole != null) {
            TiltEpoch.accuracy(epochTilt?.takeIf(TiltEpoch::isCompensating), VerticalPole.antennaHeightM(pole, ReceiverModel.RS3))
        } else {
            null
        }
        return LiveQuality(
            status,
            snapshot.gst?.horizontalM?.plus(accuracy?.horizontalM ?: 0.0),
            snapshot.gst?.verticalM?.plus(accuracy?.verticalM ?: 0.0),
        )
    }

    /**
     * S016/RF-2: un receptor sin modo que manda ETC con la inclinación encendida; S016/RF-18: un modo con la inclinación y
     * la época fuera del estado 30. Sin ETC, o con la inclinación apagada, se mide con el jalón vertical (S002/RF-32).
     */
    private fun tiltBlocker(current: CurrentTiltState, epochTilt: TiltData?): MeasureBlocker? {
        val mode = current.settings.mode
        return when {
            mode == null -> MeasureBlocker.TILT_MODE_MISSING.takeIf {
                current.key != null && epochTilt != null && epochTilt.state != TiltState.OFF
            }
            mode.usesTilt && !TiltEpoch.isCompensating(epochTilt) -> MeasureBlocker.TILT_NOT_READY
            else -> null
        }
    }

    private fun formatPole(valueM: Double): String = LengthFormat.inUnit(valueM, LinearUnit.METRE, Locale.getDefault())

    private companion object {
        const val TICK_MS = 250L
        const val STATS_INTERVAL_MS = 200L
        const val NO_DATA_MS = 2_000L
        const val DEFAULT_INTERVAL_S = 1.0
        const val MIN_INTERVAL_S = 0.05
    }
}

/** La guía en el log, en español y sin depender del idioma de la interfaz (S014/RF-5). */
internal fun TiltGuide.logText(): String = when (this) {
    TiltGuide.NO_TILT -> "no llega la inclinación"
    TiltGuide.FATAL -> "error de la inclinación"
    TiltGuide.SETUP -> "la inclinación se está preparando"
    TiltGuide.ALIGNMENT -> "la inclinación está en alineación"
    TiltGuide.FAST_MOTION -> "movimiento rápido"
    TiltGuide.BAD_GNSS -> "GNSS malo"
    TiltGuide.FILTER_FAULT -> "fallo del filtro"
}
