package es.topophone.app.ui.baseshift

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.Lazy
import dagger.hilt.android.lifecycle.HiltViewModel
import es.topophone.app.receiver.CurrentGear
import es.topophone.app.receiver.CurrentTilt
import es.topophone.app.ui.collector.MeasureProgress
import es.topophone.core.model.format.NumberInput
import es.topophone.core.model.log.AppLog
import es.topophone.core.model.log.LogCategory
import es.topophone.core.model.point.BaseShift
import es.topophone.core.model.point.BaseShiftClass
import es.topophone.core.model.point.BaseShiftVector
import es.topophone.core.model.point.CollectorSettings
import es.topophone.core.model.point.GeodeticPosition
import es.topophone.core.model.point.MeasurementMode
import es.topophone.core.model.point.PositionSource
import es.topophone.core.model.point.TiltMode
import es.topophone.core.model.project.LinearUnit
import es.topophone.core.model.project.Project
import es.topophone.core.survey.BaseShiftCalculator
import es.topophone.core.survey.BaseShiftResult
import es.topophone.core.survey.KnownPoint
import es.topophone.core.survey.ManualCoordinates
import es.topophone.core.survey.MeasurementFailure
import es.topophone.core.survey.MeasurementUpdate
import es.topophone.core.survey.PointMeasurement
import es.topophone.core.survey.PositionUnavailable
import es.topophone.core.survey.TiltSetup
import es.topophone.data.baseshift.ActivateResult
import es.topophone.data.baseshift.BaseShiftRepository
import es.topophone.data.point.CollectorPreferences
import es.topophone.data.point.PointRepository
import es.topophone.data.project.ProjectRepository
import es.topophone.receiver.session.ConnectionState
import es.topophone.receiver.session.ReceiverSession
import es.topophone.receiver.session.SessionEpoch
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** De dónde sale el punto conocido: un punto del proyecto o sus coordenadas escritas (S020/RF-1). */
enum class KnownSource { POINT, TYPED }

/** Un punto del proyecto que puede ser el conocido (S020/RF-1). */
data class KnownCandidate(val id: String, val name: String, val easting: Double, val northing: Double, val heightM: Double?)

/** Por qué no se puede empezar a medir (S020/RF-1, RF-2). */
enum class BaseShiftBlocker { NO_KNOWN_POINT, INVALID_SECONDS, NO_POSITION, NO_GEAR, SHIFT_UNAVAILABLE, PHONE_SOURCE }

/** En qué paso está la pantalla (S020/RF-1, RF-4, RF-6). */
enum class BaseShiftStep { SETUP, MEASURING, CHOOSE_HORIZONTAL, RESULT }

/** Por qué una medida o un cálculo no llegó a resultado (S020/RF-3, RF-5). */
enum class BaseShiftError { NO_DATA, OUTSIDE_CRS_AREA, KNOWN_OUTSIDE_AREA, TOO_FAR, DATUM_CHANGED }

/**
 * El desplazamiento calculado, tal como se enseña antes de activarlo (S020/RF-4): ΔX, ΔY y ΔZ, su equivalente en el SC y su módulo.
 *
 * @property horizontalOnly no corrige la altura (RF-6).
 */
data class BaseShiftResultUi(
    val vector: BaseShiftVector,
    val deltaEastM: Double,
    val deltaNorthM: Double,
    val deltaHeightM: Double,
    val horizontalOnly: Boolean,
    val classification: BaseShiftClass,
) {
    val moduleM: Double get() = vector.moduleM
}

data class BaseShiftUiState(
    val loaded: Boolean = false,
    val projectName: String = "",
    val linearUnit: LinearUnit = LinearUnit.METRE,
    /** El SC del proyecto es geográfico: las coordenadas del punto conocido se escriben en grados (RF-1). */
    val geographic: Boolean = false,
    val candidates: List<KnownCandidate> = emptyList(),
    val source: KnownSource = KnownSource.POINT,
    val selectedPointId: String? = null,
    val nameText: String = "",
    val eastingText: String = "",
    val northingText: String = "",
    val heightText: String = "",
    val secondsText: String? = null,
    val seconds: Int = 5,
    val secondsValid: Boolean = true,
    val knownValid: Boolean = false,
    val blocker: BaseShiftBlocker? = null,
    val step: BaseShiftStep = BaseShiftStep.SETUP,
    val progress: MeasureProgress? = null,
    val result: BaseShiftResultUi? = null,
    /** El usuario ya confirmó el aviso de más de 10 m (RF-5). */
    val farConfirmed: Boolean = false,
    val error: BaseShiftError? = null,
    val shifts: List<BaseShift> = emptyList(),
    val savedShiftId: String? = null,
)

/**
 * Calcula un desplazamiento de base (S020/RF-1 a RF-6, RF-12): elige el punto conocido, lo mide con el receptor (promediado, tipos
 * admitidos de S047, jalón de S046) **sin guardarlo como punto y sin el desplazamiento activo**, muestra el vector y, con la confirmación
 * del usuario, lo guarda como el activo del proyecto. También gestiona la lista de desplazamientos del proyecto.
 */
@HiltViewModel
class BaseShiftViewModel @Inject constructor(
    private val projects: ProjectRepository,
    private val points: PointRepository,
    private val shifts: BaseShiftRepository,
    private val collectorPreferences: CollectorPreferences,
    private val session: ReceiverSession,
    private val calculator: Lazy<es.topophone.core.survey.ProjectPositionCalculator>,
    private val tilt: CurrentTilt = CurrentTilt.NONE,
    private val gear: CurrentGear = CurrentGear.NONE,
    private val log: AppLog = AppLog.NONE,
) : ViewModel() {
    private val mutable = MutableStateFlow(BaseShiftUiState())
    val state: StateFlow<BaseShiftUiState> = mutable.asStateFlow()

    private var projectId: String? = null
    private var project: Project? = null
    private var measurement: PointMeasurement? = null
    private var measuringReplay = false
    private var measuredGround: GeodeticPosition? = null
    private var epochsUsed = 0
    private var measuredBase: GeodeticPosition? = null
    private var pendingHorizontal = false
    private var ticker: kotlinx.coroutines.Job? = null
    private var latestSettings = CollectorSettings()

    init {
        viewModelScope.launch { session.epochs.collect(::onEpoch) }
        viewModelScope.launch { collectorPreferences.settings.collect { latestSettings = it } }
        viewModelScope.launch { session.state.collect { refreshBlocker() } }
        viewModelScope.launch { gear.state.collect { refreshBlocker() } }
    }

    /** Abre la pantalla del proyecto [id]; solo la primera vez. */
    fun start(id: String) {
        if (projectId != null) return
        projectId = id
        viewModelScope.launch {
            project = projects.project(id)
            val settings = collectorPreferences.settings.first()
            mutable.update {
                it.copy(
                    loaded = true,
                    projectName = project?.name.orEmpty(),
                    linearUnit = project?.linearUnit ?: LinearUnit.METRE,
                    geographic = project?.crs?.isUtm == false,
                    seconds = settings.validAveragingSeconds,
                )
            }
            refreshBlocker()
        }
        viewModelScope.launch {
            points.points(id).collect { list ->
                val candidates = list.filter { it.deletedAtMs == null && !it.hidden }.mapNotNull { point ->
                    val c = point.coordinates
                    val e = c.easting
                    val n = c.northing
                    if (e == null || n == null) null else KnownCandidate(point.id, point.name, e, n, c.height)
                }.sortedBy { it.name }
                mutable.update { it.copy(candidates = candidates) }
                refreshBlocker()
            }
        }
        viewModelScope.launch { shifts.shifts(id).collect { list -> mutable.update { it.copy(shifts = list) } } }
    }

    fun setSource(source: KnownSource) {
        if (state.value.step != BaseShiftStep.SETUP) return
        mutable.update { it.copy(source = source) }
        refreshBlocker()
    }

    fun selectPoint(id: String) {
        if (state.value.step != BaseShiftStep.SETUP) return
        mutable.update { it.copy(selectedPointId = id) }
        refreshBlocker()
    }

    fun setName(text: String) = typed { it.copy(nameText = text) }

    fun setEasting(text: String) = typed { it.copy(eastingText = text) }

    fun setNorthing(text: String) = typed { it.copy(northingText = text) }

    fun setHeight(text: String) = typed { it.copy(heightText = text) }

    private fun typed(change: (BaseShiftUiState) -> BaseShiftUiState) {
        if (state.value.step != BaseShiftStep.SETUP) return
        mutable.update(change)
        refreshBlocker()
    }

    /** Los segundos de la medida, de 1 s a 60 min (RF-2). */
    fun setSeconds(text: String) {
        if (state.value.step != BaseShiftStep.SETUP) return
        val seconds = text.trim().toIntOrNull()
        mutable.update {
            it.copy(
                secondsText = text,
                secondsValid = seconds != null && seconds in CollectorSettings.AVERAGING_SECONDS,
                seconds = seconds?.takeIf { s -> s in CollectorSettings.AVERAGING_SECONDS } ?: it.seconds,
            )
        }
        refreshBlocker()
    }

    /** El punto conocido tal como está escrito o elegido, en metros, o null si falta algo (RF-1). */
    private fun knownPoint(s: BaseShiftUiState): Pair<String, KnownPoint>? = when (s.source) {
        KnownSource.POINT -> s.candidates.firstOrNull { it.id == s.selectedPointId }?.let {
            it.name to
                KnownPoint(it.easting, it.northing, it.heightM)
        }
        KnownSource.TYPED -> typedKnown(s)
    }

    private fun typedKnown(s: BaseShiftUiState): Pair<String, KnownPoint>? {
        val unit = if (s.geographic) null else s.linearUnit
        fun number(text: String) = NumberInput.parse(text)
        val east = number(s.eastingText) ?: return null
        val north = number(s.northingText) ?: return null
        val height = if (s.heightText.isBlank()) null else (number(s.heightText) ?: return null)
        val known = if (unit == null) {
            KnownPoint(east, north, height?.let(s.linearUnit::toMetres))
        } else {
            KnownPoint(unit.toMetres(east), unit.toMetres(north), height?.let(unit::toMetres))
        }
        return s.nameText.trim().ifEmpty { s.eastingText } to known
    }

    private fun refreshBlocker() {
        val snapshot = session.state.value
        val receiving = snapshot.connection is ConnectionState.Receiving && snapshot.epoch != null
        val phone = snapshot.source == PositionSource.PHONE || snapshot.source == PositionSource.PHONE_MOCK
        val gearUsable = gear.state.value.usable
        // Dentro del update: otra corrutina (puntos, sesión, equipo) puede cambiar el estado mientras se calcula el bloqueo.
        mutable.update { s ->
            val known = knownPoint(s)
            val blocker = when {
                phone -> BaseShiftBlocker.PHONE_SOURCE // S022/RF-9: el GNSS del móvil no tiene base
                known == null -> BaseShiftBlocker.NO_KNOWN_POINT
                !s.secondsValid -> BaseShiftBlocker.INVALID_SECONDS
                !gearUsable -> BaseShiftBlocker.NO_GEAR
                !receiving -> BaseShiftBlocker.NO_POSITION
                else -> null
            }
            s.copy(knownValid = known != null, blocker = blocker)
        }
    }

    /** "Medir": empieza el promediado del punto conocido, sin guardarlo como punto (RF-2). */
    @Synchronized
    fun measure() {
        val s = state.value
        val project = project ?: return
        if (s.step != BaseShiftStep.SETUP || s.blocker != null) return
        val equipment = gear.state.value
        val pole = equipment.verticalPoleM ?: return
        val settings = latestSettings
        val tiltState = tilt.state.value
        measurement = PointMeasurement(
            mode = MeasurementMode.AVERAGED,
            averagingSeconds = s.seconds,
            admitted = settings.precision.admitted,
            poleHeightM = pole,
            crs = project.crs,
            heightSystem = project.heightSystem,
            epochIntervalS = epochIntervalS,
            startedAtMs = System.currentTimeMillis(),
            calculator = calculator.get(),
            tilt = TiltSetup(tiltState.settings.mode ?: TiltMode.VERTICAL, tiltState.settings.verified),
            profile = equipment.profile,
            gear = equipment.toUsed(),
            shift = null, // RF-2: la medida del punto conocido no lleva el desplazamiento activo
        )
        measuringReplay = session.state.value.replay != null
        measuredBase = null
        mutable.update { it.copy(step = BaseShiftStep.MEASURING, progress = null, error = null, result = null, farConfirmed = false) }
        ensureTicker()
    }

    private var epochIntervalS: Double = DEFAULT_INTERVAL_S
    private var lastEpochUtc: Int? = null

    @Synchronized
    private fun onEpoch(received: SessionEpoch) {
        val epoch = received.epoch
        lastEpochUtc?.let { previous ->
            val delta = (epoch.utcMillisOfDay - previous) / 1_000.0
            if (delta > 0) epochIntervalS = delta.coerceIn(MIN_INTERVAL_S, DEFAULT_INTERVAL_S)
        }
        lastEpochUtc = epoch.utcMillisOfDay
        received.base.position?.let { measuredBase = GeodeticPosition(it.latitudeDeg, it.longitudeDeg, it.ellipsoidalHeightM) }
        measurement?.onEpoch(epoch, System.currentTimeMillis(), received.gst)?.forEach(::handle)
    }

    private fun ensureTicker() {
        if (ticker?.isActive == true) return
        ticker = viewModelScope.launch {
            while (isActive) {
                delay(TICK_MS)
                tick(System.currentTimeMillis())
            }
        }
    }

    /** El reloj de la medida, con la hora del móvil (RF-2). */
    @Synchronized
    internal fun tick(nowMs: Long) {
        measurement?.onTick(nowMs)?.let(::handle)
    }

    private fun handle(update: MeasurementUpdate) {
        when (update) {
            is MeasurementUpdate.Progress -> mutable.update {
                it.copy(progress = MeasureProgress(update.elapsedS, update.requiredS, update.epochsUsed, update.epochsDiscarded))
            }
            is MeasurementUpdate.Failed -> {
                stopMeasuring()
                val error = if (update.reason == MeasurementFailure.NO_DATA) BaseShiftError.NO_DATA else BaseShiftError.OUTSIDE_CRS_AREA
                log.warning(LogCategory.POINTS, "Base shift: no se pudo medir el punto conocido")
                mutable.update { it.copy(step = BaseShiftStep.SETUP, error = error) }
            }
            is MeasurementUpdate.Done -> {
                stopMeasuring()
                measuredGround = update.result.ground
                epochsUsed = update.result.epochsUsed
                compute(acceptHorizontalOnly = false)
            }
            else -> Unit // las pausas y los avisos de solución de la medida son los del recolector (no se repiten aquí)
        }
    }

    private fun stopMeasuring() {
        measurement = null
        ticker?.cancel()
        ticker = null
    }

    /** Calcula el vector; sin altura del punto conocido, pregunta antes si vale el desplazamiento solo horizontal (RF-6). */
    private fun compute(acceptHorizontalOnly: Boolean) {
        val project = project ?: return
        val ground = measuredGround ?: return
        val (_, known) = knownPoint(state.value) ?: return
        if (known.heightM == null && !acceptHorizontalOnly) {
            pendingHorizontal = true
            mutable.update { it.copy(step = BaseShiftStep.CHOOSE_HORIZONTAL, progress = null) }
            return
        }
        pendingHorizontal = false
        val positions = calculator.get()
        val result = BaseShiftCalculator(ManualCoordinates(positions), positions).compute(
            known = known,
            measured = ground,
            crs = project.crs,
            heightSystem = project.heightSystem,
            acceptHorizontalOnly = acceptHorizontalOnly,
        )
        when (result) {
            is BaseShiftResult.Rejected -> mutable.update {
                it.copy(step = BaseShiftStep.SETUP, progress = null, error = result.reason.toError())
            }
            is BaseShiftResult.Computed -> mutable.update {
                it.copy(
                    step = BaseShiftStep.RESULT,
                    progress = null,
                    result = BaseShiftResultUi(
                        result.vector,
                        result.deltaEastM,
                        result.deltaNorthM,
                        result.deltaHeightM,
                        result.horizontalOnly,
                        result.classification,
                    ),
                    error = if (result.classification == BaseShiftClass.REJECTED) BaseShiftError.TOO_FAR else null,
                )
            }
        }
    }

    private fun PositionUnavailable.toError(): BaseShiftError = when (this) {
        PositionUnavailable.OUTSIDE_CRS_AREA, PositionUnavailable.OUTSIDE_ED50_GRID, PositionUnavailable.OUTSIDE_GEOID ->
            BaseShiftError.KNOWN_OUTSIDE_AREA
        PositionUnavailable.NO_HEIGHT -> BaseShiftError.KNOWN_OUTSIDE_AREA
    }

    /** "Aplicar solo horizontal": el vector sin corregir la altura (RF-6). */
    fun acceptHorizontalOnly() {
        if (state.value.step != BaseShiftStep.CHOOSE_HORIZONTAL) return
        compute(acceptHorizontalOnly = true)
    }

    /** La confirmación aparte del aviso de más de 10 m (RF-5). */
    fun confirmFar() = mutable.update { it.copy(farConfirmed = true) }

    /** Vuelve a empezar: descarta lo medido (RF-4). */
    fun discard() {
        stopMeasuring()
        measuredGround = null
        pendingHorizontal = false
        mutable.update { it.copy(step = BaseShiftStep.SETUP, progress = null, result = null, error = null, farConfirmed = false) }
        refreshBlocker()
    }

    /** "Cancelar" una medida en curso (RF-2). */
    fun cancelMeasuring() {
        if (state.value.step == BaseShiftStep.MEASURING) discard()
    }

    /**
     * "Activar": guarda el desplazamiento y lo deja activo (RF-4). Con un módulo de más de 10 m exige antes [confirmFar]; con más de
     * 1.000 m no se permite (RF-5).
     */
    fun activate() {
        val s = state.value
        val result = s.result ?: return
        val project = project ?: return
        val ground = measuredGround ?: return
        if (s.step != BaseShiftStep.RESULT) return
        when (result.classification) {
            BaseShiftClass.REJECTED -> return
            BaseShiftClass.NEEDS_CONFIRMATION -> if (!s.farConfirmed) return
            BaseShiftClass.OK -> Unit
        }
        val (name, known) = knownPoint(s) ?: return
        val id = UUID.randomUUID().toString()
        val shift = BaseShift(
            id = id,
            projectId = project.id,
            createdAtMs = System.currentTimeMillis(),
            fromReplay = measuringReplay,
            knownName = name,
            knownPointId = if (s.source == KnownSource.POINT) s.selectedPointId else null,
            knownEasting = known.easting,
            knownNorthing = known.northing,
            knownHeightM = known.heightM,
            measured = ground,
            epochsUsed = epochsUsed,
            vector = result.vector,
            horizontalOnly = result.horizontalOnly,
            correctionsDatum = project.correctionsDatum,
            active = true,
            base = measuredBase,
        )
        viewModelScope.launch {
            shifts.add(shift, activate = true)
            log.info(LogCategory.POINTS, "Base shift activado (módulo ${"%.3f".format(java.util.Locale.ROOT, result.moduleM)} m)")
            mutable.update { it.copy(step = BaseShiftStep.SETUP, result = null, progress = null, savedShiftId = id, farConfirmed = false) }
            refreshBlocker()
        }
    }

    /** Reactiva un desplazamiento de la lista; devuelve por qué no, si no se pudo (RF-12, RF-16). */
    fun reactivate(id: String) {
        viewModelScope.launch {
            val result = shifts.activate(id)
            if (result == ActivateResult.DATUM_MISMATCH) mutable.update { it.copy(error = BaseShiftError.DATUM_CHANGED) }
        }
    }

    fun deactivate() {
        val id = projectId ?: return
        viewModelScope.launch { shifts.deactivate(id) }
    }

    fun delete(id: String) {
        viewModelScope.launch { shifts.delete(id) }
    }

    fun dismissError() = mutable.update { it.copy(error = null) }

    private companion object {
        const val TICK_MS = 250L
        const val MIN_INTERVAL_S = 0.05
        const val DEFAULT_INTERVAL_S = 1.0
    }
}
