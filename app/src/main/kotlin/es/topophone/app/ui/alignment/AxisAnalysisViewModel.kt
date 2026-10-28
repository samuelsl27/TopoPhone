package es.topophone.app.ui.alignment

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.Lazy
import dagger.hilt.android.lifecycle.HiltViewModel
import es.topophone.app.receiver.CurrentGear
import es.topophone.app.receiver.CurrentTilt
import es.topophone.app.ui.stakeout.GuidanceOff
import es.topophone.core.gnss.nmea.Epoch
import es.topophone.core.model.alignment.Alignment
import es.topophone.core.model.log.AppLog
import es.topophone.core.model.log.LogCategory
import es.topophone.core.model.point.AdmittedSolutions
import es.topophone.core.model.point.AxisHeightRef
import es.topophone.core.model.project.Project
import es.topophone.core.model.surface.SurfaceGuide
import es.topophone.core.survey.AlignmentGeometry
import es.topophone.core.survey.AxisAnalysis
import es.topophone.core.survey.AxisAnalysisValue
import es.topophone.core.survey.LiveTip
import es.topophone.core.survey.PoleHeight
import es.topophone.core.survey.ProjectPositionCalculator
import es.topophone.core.survey.SolutionTypes
import es.topophone.core.survey.TiltEpoch
import es.topophone.core.survey.VerticalProfileEval
import es.topophone.data.alignment.AlignmentRepository
import es.topophone.data.point.CollectorPreferences
import es.topophone.data.project.ProjectRepository
import es.topophone.data.surface.SurfaceGuides
import es.topophone.receiver.session.ConnectionState
import es.topophone.receiver.session.ReceiverSession
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Lo que muestra «Analizar» (S054/RF-12, RF-14).
 *
 * @property reference contra qué se calcula la diferencia de cota: la rasante, la superficie activa o ninguna (RF-12).
 * @property canChoose el eje tiene rasante y hay superficie activa: se ofrece elegir.
 * @property analysis la última época: el PK, el desplazamiento y la diferencia de cota, o sin proyección sobre el eje (RF-14); null
 *   si no hay posición.
 * @property off por qué no hay análisis, si no lo hay.
 */
data class AxisAnalysisUiState(
    val project: Project? = null,
    val loaded: Boolean = false,
    val alignmentId: String? = null,
    val alignmentName: String = "",
    val hasProfile: Boolean = false,
    val hasSurface: Boolean = false,
    val reference: AxisHeightRef? = null,
    val analysis: AxisAnalysisValue? = null,
    val off: GuidanceOff? = GuidanceOff.NO_POSITION,
) {
    val canChoose: Boolean get() = hasProfile && hasSurface
}

/**
 * El análisis en vivo de la punta del jalón respecto a un eje (S054/RF-12, RF-14): con cada época calcula el PK y el desplazamiento
 * de la punta, y su diferencia de cota con la rasante o con la superficie activa, a elegir. Se refresca a 5 Hz como mucho (RNF-T13).
 * Sin proyección sobre el eje (antes de su inicio o después de su final) no hay PK ni desplazamiento.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class AxisAnalysisViewModel @Inject constructor(
    private val projects: ProjectRepository,
    private val alignments: AlignmentRepository,
    private val collectorPreferences: CollectorPreferences,
    private val gear: CurrentGear,
    private val session: ReceiverSession,
    private val calculator: Lazy<ProjectPositionCalculator>,
    private val log: AppLog = AppLog.NONE,
    private val tilt: CurrentTilt = CurrentTilt.NONE,
    private val surfaces: SurfaceGuides = SurfaceGuides.NONE,
) : ViewModel() {
    /** El eje abierto con lo que hace falta para calcular sobre él. */
    private class Model(val projectId: String, val alignment: Alignment) {
        val geometry = AlignmentGeometry(alignment)
        val profile = alignment.profile?.let { VerticalProfileEval(it) }
    }

    private val model = MutableStateFlow<Model?>(null)
    private val projectId = MutableStateFlow<String?>(null)

    @Volatile private var project: Project? = null

    /** Lo que eligió el usuario; null es que no ha elegido y manda la regla de [resolved] (RF-12). */
    private val choice = MutableStateFlow<AxisHeightRef?>(null)
    private val live = MutableStateFlow<Pair<AxisAnalysisValue?, GuidanceOff?>>(null to GuidanceOff.NO_POSITION)
    private val admitted = MutableStateFlow(AdmittedSolutions.FIX_ONLY)

    @Volatile private var lastAtMs = Long.MIN_VALUE

    /** Cada cuánto se calcula una época como mínimo: 5 Hz (RNF-T13). Los tests lo ponen a 0. */
    internal var intervalMs: Long = 200L

    internal var now: () -> Long = System::currentTimeMillis

    private val projectFlow = projectId.flatMapLatest { id -> if (id == null) flowOf(null) else projects.observe(id) }
    private val surfaceGuide: StateFlow<SurfaceGuide?> = projectId
        .flatMapLatest { id -> if (id == null) flowOf(null) else surfaces.guide(id) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** La referencia que vale: la elegida si existe; si no, la rasante si el eje la tiene y si no la superficie activa (D5). */
    private fun resolved(m: Model?, guide: SurfaceGuide?, chosen: AxisHeightRef?): AxisHeightRef? {
        val hasProfile = m?.profile != null
        val hasSurface = guide != null
        return when {
            chosen == AxisHeightRef.PROFILE && hasProfile -> AxisHeightRef.PROFILE
            chosen == AxisHeightRef.SURFACE && hasSurface -> AxisHeightRef.SURFACE
            hasProfile -> AxisHeightRef.PROFILE
            hasSurface -> AxisHeightRef.SURFACE
            else -> null
        }
    }

    val state: StateFlow<AxisAnalysisUiState> = combine(projectFlow, model, surfaceGuide, choice, live) {
            project,
            m,
            guide,
            chosen,
            liveValue,
        ->
        if (m == null) return@combine AxisAnalysisUiState(project = project)
        AxisAnalysisUiState(
            project = project,
            loaded = true,
            alignmentId = m.alignment.id,
            alignmentName = m.alignment.name,
            hasProfile = m.profile != null,
            hasSurface = guide != null,
            reference = resolved(m, guide, chosen),
            analysis = liveValue.first,
            off = liveValue.second,
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, AxisAnalysisUiState())

    init {
        viewModelScope.launch { projectFlow.collect { project = it } }
        viewModelScope.launch { collectorPreferences.settings.collect { admitted.value = it.precision.admitted } }
        viewModelScope.launch { session.epochs.collect { received -> onEpoch(received.epoch) } }
        // S018/RF-25: sin épocas no hay análisis hasta que vuelvan.
        viewModelScope.launch {
            session.state.collect { snapshot ->
                if (snapshot.connection !is ConnectionState.Receiving && model.value != null) live.value = null to GuidanceOff.NO_DATA
            }
        }
    }

    /** Abre el análisis del eje [alignmentId] del proyecto [projectId] (S054/RF-12). */
    fun open(projectId: String, alignmentId: String) {
        this.projectId.value = projectId
        choice.value = null
        live.value = null to GuidanceOff.NO_POSITION
        lastAtMs = Long.MIN_VALUE
        viewModelScope.launch {
            val alignment = alignments.alignment(alignmentId) ?: return@launch
            if (alignment.projectId != projectId) return@launch
            project = projects.project(projectId)
            model.value = Model(projectId, alignment)
            log.info(LogCategory.POINTS, "Análisis de un eje abierto") // S014/RF-5
        }
    }

    /** Cierra el análisis. */
    fun close() {
        model.value = null
        live.value = null to GuidanceOff.NO_POSITION
    }

    /** Elige contra qué se calcula la diferencia de cota (RF-12); false si el eje no tiene esa referencia. */
    fun setReference(reference: AxisHeightRef): Boolean {
        val m = model.value ?: return false
        val available = when (reference) {
            AxisHeightRef.PROFILE -> m.profile != null
            AxisHeightRef.SURFACE -> surfaceGuide.value != null
        }
        if (!available) return false
        choice.value = reference
        return true
    }

    private fun onEpoch(epoch: Epoch) {
        val m = model.value ?: return
        val project = project ?: return
        val time = now()
        if (intervalMs > 0 && lastAtMs != Long.MIN_VALUE && time - lastAtMs < intervalMs) return
        lastAtMs = time
        val fix = epoch.gga?.takeIf { it.hasPosition }
        if (fix == null) {
            live.update { null to GuidanceOff.NO_POSITION }
            return
        }
        if (SolutionTypes.of(fix.status) !in admitted.value) {
            live.update { null to GuidanceOff.SOLUTION_NOT_ADMITTED }
            return
        }
        val tiltMode = tilt.state.value.settings.mode
        if (tiltMode?.usesTilt == true && !TiltEpoch.isCompensating(epoch.tilt)) {
            live.update { null to GuidanceOff.TILT_NOT_READY }
            return
        }
        val g = gear.state.value
        if (!g.usable) { // S046/RF-28
            live.update { null to GuidanceOff.NO_POSITION }
            return
        }
        val tip = LiveTip.of(fix, epoch.tilt, tiltMode, g.verticalPoleM ?: PoleHeight.DEFAULT_M, g.profile)
        val position = tip?.let { calculator.get().fromGround(it.ground, project.crs, project.heightSystem) }
        val easting = position?.easting
        val northing = position?.northing
        if (position == null || easting == null || northing == null) {
            live.update { null to GuidanceOff.NO_POSITION }
            return
        }
        val reference = resolved(m, surfaceGuide.value, choice.value)
        // La superficie con su desplazamiento vertical: es la cota de diseño que la rasante no da (S042/RF-24).
        val surfaceHeight = surfaceGuide.value?.takeIf { reference == AxisHeightRef.SURFACE }?.designAt(easting, northing)
        live.value = AxisAnalysis.of(m.geometry, m.profile, reference, surfaceHeight, easting, northing, position.height) to null
    }
}
