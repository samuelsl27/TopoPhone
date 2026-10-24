package es.topophone.app.ui.collector

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.Lazy
import dagger.hilt.android.lifecycle.HiltViewModel
import es.topophone.app.receiver.BaseNotices
import es.topophone.app.receiver.CurrentTilt
import es.topophone.app.receiver.CurrentTiltState
import es.topophone.app.receiver.VisibleNotices
import es.topophone.core.gnss.nmea.SolutionStatus
import es.topophone.core.gnss.nmea.TiltData
import es.topophone.core.gnss.nmea.TiltState
import es.topophone.core.model.format.DurationText
import es.topophone.core.model.format.LengthFormat
import es.topophone.core.model.log.AppLog
import es.topophone.core.model.log.LogCategory
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
import es.topophone.core.model.point.TiltMode
import es.topophone.core.model.project.LinearUnit
import es.topophone.core.model.project.Project
import es.topophone.core.survey.AverageStatsSnapshot
import es.topophone.core.survey.BaseFix
import es.topophone.core.survey.Baseline
import es.topophone.core.survey.Dispersion
import es.topophone.core.survey.MeasurementFailure
import es.topophone.core.survey.MeasurementResult
import es.topophone.core.survey.MeasurementUpdate
import es.topophone.core.survey.PointMeasurement
import es.topophone.core.survey.PoleHeight
import es.topophone.core.survey.PositionUnavailable
import es.topophone.core.survey.ProjectPositionCalculator
import es.topophone.core.survey.ReceiverModel
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
    private val statsFlow = MutableStateFlow<AverageStatsUi?>(null)
    private var lastStatsAtMs = 0L

    /** Los milisegundos entre dos publicaciones de las estadísticas; los tests mandan las épocas sin pausa y lo ponen a 0. */
    internal var statsIntervalMs = STATS_INTERVAL_MS
    private var measuringReplay = false
    private var pendingResult: MeasurementResult? = null
    private var ticker: Job? = null
    private var nameCheck: Job? = null
    private var lastEpochUtc: Int? = null
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
        viewModelScope.launch { run.map { it.measuring || it.pending != null }.distinctUntilChanged().collect(inProgress::set) }
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

    private val formState = combine(form, nameTaken, run, collectorPreferences.settings) { form, taken, run, settings ->
        val secondsText = form.secondsText ?: DurationText.format(settings.averagingSeconds)
        val secondsValid = parseSeconds(secondsText) != null
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
            error = run.error,
            restarted = run.restarted,
            restartedDiscardedS = run.restartedDiscardedS,
        )
    }

    /** Las estadísticas del promediado en curso, a 4 Hz; null sin promediado (S017/RF-3 a RF-5). */
    val stats: StateFlow<AverageStatsUi?> = statsFlow

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
                if (measurement != null && measurementRevision != null && revision != measurementRevision) cancelForTiltMode()
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

    /** La descripción se conserva para el siguiente punto (S010/RF-4). */
    fun setDescription(description: String) = form.update { it.copy(description = description) }

    /** La misma altura del jalón que en Coordenadas (S010/RF-5). */
    fun setPoleHeight(text: String) {
        form.update { it.copy(poleText = text) }
        PoleHeight.parse(text)?.let { pole -> viewModelScope.launch { receiverPreferences.savePoleHeight(pole) } }
    }

    /** Se escribe en minutos y segundos (o en segundos) y solo se guarda de 1 s a 60 min; fuera de eso no se puede promediar (S017/RF-1). */
    fun setAveragingSeconds(text: String) {
        form.update { it.copy(secondsText = text) }
        val seconds = parseSeconds(text) ?: return
        viewModelScope.launch { collectorPreferences.save(collectorPreferences.settings.first().copy(averagingSeconds = seconds)) }
    }

    /** Para todos los proyectos (S010/RF-13). */
    fun setSettings(settings: CollectorSettings) {
        viewModelScope.launch { collectorPreferences.save(settings) }
    }

    fun measure() {
        val current = state.value
        val project = project ?: return
        val pole = PoleHeight.parse(current.poleHeightText) ?: return
        if (current.blocker != null || current.measuring || current.pending != null) return
        val settings = current.settings
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
        run.value = Run(measuring = true)
        ticker = viewModelScope.launch {
            while (isActive) {
                delay(TICK_MS)
                tick(System.currentTimeMillis())
            }
        }
    }

    private fun cancelForTiltMode() {
        measurement?.cancel()
        stopMeasuring()
        log.warning(LogCategory.POINTS, "Medida de ${measuredName()} cancelada: ha cambiado el modo de inclinación") // S014/RF-5
        run.value = Run(error = CollectorError.TILT_MODE_CHANGED)
    }

    /** El reloj de la medida, con la hora del móvil: la instantánea sin datos se cancela y la promediada se pausa (S017/RF-8). */
    internal fun tick(nowMs: Long) {
        measurement?.onTick(nowMs)?.let(::handle)
        publishStats()
    }

    /** "Seguir esperando" a la pregunta de 5 min: sigue pausada y se vuelve a preguntar si el corte sigue 5 min más (S017/RF-10). */
    fun keepWaiting() {
        measurement?.keepWaiting(System.currentTimeMillis())
        log.info(LogCategory.POINTS, "Medida de ${measuredName()}: se sigue esperando los datos del receptor") // S014/RF-5
        run.update { it.copy(paused = PauseState.PAUSED) }
    }

    /** "Terminar": cierra el promediado con las épocas reunidas y lo guarda como cualquier medida (S017/RF-6, RF-7). */
    fun finishNow() {
        measurement?.finishNow()?.let(::handle)
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
    fun cancel() {
        measurement?.cancel()
        stopMeasuring()
        run.value = Run()
    }

    /** S010/RF-15: se guarda marcado como fuera de tolerancia. */
    fun confirmOutOfTolerance() {
        val result = pendingResult ?: return
        val failures = run.value.pending?.failures.orEmpty()
        pendingResult = null
        run.update { it.copy(pending = null) }
        save(result, failures)
    }

    fun discardPending() {
        log.info(LogCategory.POINTS, "Medida de ${measuredName()} descartada") // S014/RF-5
        pendingResult = null
        run.value = Run()
    }

    /** Cierra "La base ha cambiado" de ese cambio (S015/RF-17). */
    fun dismissBaseChange(count: Int) = baseNotices.dismissChange(count)

    /** Cierra "Línea base larga" para esa base (S015/RF-15). */
    fun dismissLongBaseline(serial: Int) = baseNotices.dismissLongBaseline(serial)

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
        if (measurement != null) received.base.position?.let { measurementBase = it }
        measurement?.onEpoch(epoch, System.currentTimeMillis(), received.gst)?.forEach(::handle) // S010/RF-22
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
                if (failures.isEmpty()) {
                    save(result, failures)
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
            ),
            rawNmea = result.rawNmea,
            outOfTolerance = failures,
            measuredFromMs = result.measuredFromMs,
            measuredToMs = result.measuredToMs,
            coordinates = PointCoordinates(result.ground, result.position.easting, result.position.northing, result.position.height),
            base = measurementBase?.let { pointBase(it, result.antenna) }, // S015/RF-20
            tilt = result.tilt, // S016/RF-22
        )
        val point = Point.measured(
            id = UUID.randomUUID().toString(),
            projectId = projectId,
            name = name,
            description = form.value.description.trim(),
            measurement = measurement,
            createdAtMs = System.currentTimeMillis(),
        )
        viewModelScope.launch {
            when (points.create(point)) {
                PointSaveResult.Saved -> {
                    alerts.pointSaved() // S010/RF-19
                    form.update { it.copy(name = PointNames.next(name)) }
                    run.value = Run(savedName = name)
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
        ticker?.cancel()
        ticker = null
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
