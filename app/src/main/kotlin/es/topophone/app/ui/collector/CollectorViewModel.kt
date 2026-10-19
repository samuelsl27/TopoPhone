package es.topophone.app.ui.collector

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.Lazy
import dagger.hilt.android.lifecycle.HiltViewModel
import es.topophone.core.gnss.nmea.Epoch
import es.topophone.core.gnss.nmea.SolutionStatus
import es.topophone.core.model.format.LengthFormat
import es.topophone.core.model.log.AppLog
import es.topophone.core.model.log.LogCategory
import es.topophone.core.model.point.CollectorSettings
import es.topophone.core.model.point.Measurement
import es.topophone.core.model.point.MeasurementMode
import es.topophone.core.model.point.Point
import es.topophone.core.model.point.PointCoordinates
import es.topophone.core.model.point.PointNames
import es.topophone.core.model.point.PointQuality
import es.topophone.core.model.point.PrecisionFailure
import es.topophone.core.model.project.LinearUnit
import es.topophone.core.model.project.Project
import es.topophone.core.survey.MeasurementFailure
import es.topophone.core.survey.MeasurementResult
import es.topophone.core.survey.MeasurementUpdate
import es.topophone.core.survey.PointMeasurement
import es.topophone.core.survey.PoleHeight
import es.topophone.core.survey.PositionUnavailable
import es.topophone.core.survey.ProjectPositionCalculator
import es.topophone.data.point.CollectorPreferences
import es.topophone.data.point.PointRepository
import es.topophone.data.point.PointSaveResult
import es.topophone.data.project.ProjectRepository
import es.topophone.data.receiver.ReceiverPreferences
import es.topophone.receiver.platform.Alerts
import es.topophone.receiver.session.ConnectionState
import es.topophone.receiver.session.ReceiverSession
import es.topophone.receiver.session.ReceiverSnapshot
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

/** Por qué no se puede medir ahora (S010/RF-3, RF-12). */
enum class MeasureBlocker { INVALID_NAME, NAME_TAKEN, INVALID_POLE, INVALID_SECONDS, NO_POSITION, OUTSIDE_CRS_AREA }

/** Por qué una medida se ha cancelado sola (S010/RF-12). */
enum class CollectorError { NO_DATA, OUTSIDE_CRS_AREA }

/** Solución y precisiones de la GST del receptor (S010/RF-6). */
data class LiveQuality(val solution: SolutionStatus, val horizontalM: Double?, val verticalM: Double?)

/** Tiempo reunido de un promediado (S010/RF-9). */
data class MeasureProgress(val elapsedS: Double, val requiredS: Double)

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
    val averagingSecondsText: String = CollectorSettings().averagingSeconds.toString(),
    val averagingSecondsValid: Boolean = true,
    val live: LiveQuality? = null,
    val blocker: MeasureBlocker? = null,
    val measuring: Boolean = false,
    val progress: MeasureProgress? = null,
    val solutionLost: Boolean = false,
    val pending: PendingPoint? = null,
    /** Último punto guardado, para decirlo (S010/RF-19). */
    val savedName: String? = null,
    val error: CollectorError? = null,
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
        val pending: PendingPoint? = null,
        val savedName: String? = null,
        val error: CollectorError? = null,
    )

    private val projectId = MutableStateFlow<String?>(null)
    private var project: Project? = null
    private val form = MutableStateFlow(Form())
    private val nameTaken = MutableStateFlow(false)
    private val run = MutableStateFlow(Run())
    private var measurement: PointMeasurement? = null
    private var measuringReplay = false
    private var pendingResult: MeasurementResult? = null
    private var ticker: Job? = null
    private var nameCheck: Job? = null
    private var lastEpochUtc: Int? = null
    private var epochIntervalS = DEFAULT_INTERVAL_S

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
        val secondsText = form.secondsText ?: settings.averagingSeconds.toString()
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
            pending = run.pending,
            savedName = run.savedName,
            error = run.error,
        )
    }

    val state: StateFlow<CollectorUiState> = combine(formState, projectFlow, session.state) { state, project, snapshot ->
        val gga = snapshot.gga?.takeIf { snapshot.connection is ConnectionState.Receiving && it.hasPosition }
        val live = gga?.let { LiveQuality(it.status, snapshot.gst?.horizontalM, snapshot.gst?.verticalM) }
        state.copy(live = live, blocker = state.blocker ?: positionBlocker(snapshot, project, state.poleHeightText))
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.Eagerly, CollectorUiState())

    init {
        viewModelScope.launch { session.epochs.collect { onEpoch(it.epoch) } }
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

    /** Se guardan solo de 1 a 60 s; fuera de eso no se puede promediar (S010/RF-9). */
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
        measurement = PointMeasurement(
            mode = settings.mode,
            averagingSeconds = settings.validAveragingSeconds,
            fixOnly = settings.precision.fixOnly,
            poleHeightM = pole,
            crs = project.crs,
            heightSystem = project.heightSystem,
            epochIntervalS = epochIntervalS,
            startedAtMs = System.currentTimeMillis(),
            calculator = calculator.get(),
        )
        measuringReplay = session.state.value.replay != null
        run.value = Run(measuring = true)
        ticker = viewModelScope.launch {
            while (isActive) {
                delay(TICK_MS)
                measurement?.onTick(System.currentTimeMillis())?.let(::handle)
            }
        }
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

    private fun onEpoch(epoch: Epoch) {
        // Intervalo del receptor: la menor separación entre épocas seguidas, de 0,05 a 1 s.
        lastEpochUtc?.let { previous ->
            val delta = (epoch.utcMillisOfDay - previous) / 1_000.0
            if (delta > 0) epochIntervalS = delta.coerceIn(MIN_INTERVAL_S, DEFAULT_INTERVAL_S)
        }
        lastEpochUtc = epoch.utcMillisOfDay
        measurement?.onEpoch(epoch, System.currentTimeMillis())?.forEach(::handle)
    }

    private fun handle(update: MeasurementUpdate) {
        when (update) {
            is MeasurementUpdate.Progress -> run.update { it.copy(progress = MeasureProgress(update.elapsedS, update.requiredS)) }
            MeasurementUpdate.SolutionLost -> {
                log.warning(LogCategory.POINTS, "Solución perdida durante la medida de ${measuredName()}") // S014/RF-5
                alerts.solutionLost() // S010/RF-10
                run.update { it.copy(solutionLost = true) }
            }
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

    /** El nombre del punto que se mide, para el log (sin coordenadas, S014/RF-7). */
    private fun measuredName(): String = PointNames.clean(form.value.name)

    private fun stopMeasuring() {
        ticker?.cancel()
        ticker = null
        measurement = null
    }

    private fun checkName() {
        val id = projectId.value ?: return
        val name = PointNames.clean(form.value.name)
        nameCheck?.cancel()
        nameCheck = viewModelScope.launch { nameTaken.value = name.isNotEmpty() && points.nameExists(id, name) } // S010/RF-3
    }

    private fun parseSeconds(text: String): Int? = text.trim().toIntOrNull()?.takeIf { it in CollectorSettings.AVERAGING_SECONDS }

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

    private fun formatPole(valueM: Double): String = LengthFormat.inUnit(valueM, LinearUnit.METRE, Locale.getDefault())

    private companion object {
        const val TICK_MS = 250L
        const val DEFAULT_INTERVAL_S = 1.0
        const val MIN_INTERVAL_S = 0.05
    }
}
