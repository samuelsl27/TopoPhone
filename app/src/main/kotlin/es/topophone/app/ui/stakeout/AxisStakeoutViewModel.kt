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
import es.topophone.core.model.alignment.section.SectionCode
import es.topophone.core.model.alignment.section.Superelevation
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
import es.topophone.core.model.survey.linear.LinearSurvey
import es.topophone.core.model.survey.linear.LinearSurveyKind
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
import es.topophone.core.survey.SectionKeyStations
import es.topophone.core.survey.SectionResolver
import es.topophone.core.survey.SectionStakeResult
import es.topophone.core.survey.SectionStakeTarget
import es.topophone.core.survey.SectionStationItem
import es.topophone.core.survey.SectionStationOrder
import es.topophone.core.survey.SectionStationSeries
import es.topophone.core.survey.VerticalProfileEval
import es.topophone.data.alignment.AlignmentRepository
import es.topophone.data.baseshift.ActiveBaseShift
import es.topophone.data.localization.ActiveLocalization
import es.topophone.data.point.CollectorPreferences
import es.topophone.data.point.StakeoutPreferences
import es.topophone.data.project.ProjectRepository
import es.topophone.data.section.SectionRepository
import es.topophone.receiver.platform.Alerts
import es.topophone.receiver.session.ConnectionState
import es.topophone.receiver.session.ReceiverSession
import java.math.BigDecimal
import java.math.RoundingMode
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

/**
 * La serie de PK con que se abre el replanteo para guiar un perfil longitudinal (S052/RF-10): del PK inicial al final cada intervalo, en
 * metros.
 */
class ProfileGuide(val fromM: Double, val toM: Double? = null, val intervalM: Double? = null) {
    companion object {
        /**
         * La guía de una toma guiada: del PK inicial al final cada intervalo en un perfil longitudinal, o el PK de la sección en un perfil
         * transversal; null si la toma no tiene PK que guiar.
         */
        fun of(survey: LinearSurvey): ProfileGuide? = when (survey.kind) {
            LinearSurveyKind.CROSS -> survey.chainage?.let { ProfileGuide(it) }
            else -> {
                val from = survey.chainageFrom
                val to = survey.chainageTo
                val interval = survey.intervalM
                if (from == null || to == null || interval == null) null else ProfileGuide(from, to, interval)
            }
        }
    }
}

/** Los campos del replanteo por PK que se validan (S054/RF-1, RF-5, RF-9). */
enum class AxisField { START, END, INTERVAL, OFFSET, THICKNESS, CHAINAGE }

/** Por qué se cierra el replanteo por PK sin que el usuario lo pida (S054/RF-11). */
enum class AxisClosedReason { ALIGNMENT_DELETED, }

/** Por qué no se abre un replanteo por código (S050/RF-21). */
sealed interface AxisSectionError {
    /** No se ha elegido ningún código. */
    data object NoCodes : AxisSectionError

    /** El PK no tiene sección. */
    data class NoSection(val chainageM: Double) : AxisSectionError

    /** El PK queda entre dos secciones que no se pueden interpolar. */
    data class Missing(val chainageM: Double, val codes: List<String>, val heightKindMismatch: Boolean) : AxisSectionError

    /** Ningún par PK y código de la serie existe: el código no está en la sección de ese PK. */
    data class NoCode(val code: String, val chainageM: Double) : AxisSectionError
}

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
    /** El modo «Por código» (S050/RF-16): los códigos del eje, los elegidos, el orden de recorrido y el código y la pendiente de ahora. */
    val byCode: Boolean = false,
    val availableCodes: List<String> = emptyList(),
    val selectedCodes: List<String> = emptyList(),
    val order: SectionStationOrder = SectionStationOrder.BY_CHAINAGE,
    val code: String? = null,
    val sectionSlope: Double? = null,
    val codePosition: Int = 0,
    val codeCount: Int = 0,
    val sectionError: AxisSectionError? = null,
    /** Hay secciones o sección tipo en el eje: se ofrece el modo «Por código». */
    val hasSections: Boolean = false,
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
    private val sections: SectionRepository,
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
    private val localization: ActiveLocalization = ActiveLocalization.NONE,
) : ViewModel() {
    private val controller = StakeoutController(alerts, log)

    private val activeShift by lazy { baseShift.track(projectId, viewModelScope) } // S020/RF-7
    private val activeLocalization by lazy { localization.track(projectId, viewModelScope) } // S039/RF-17

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
        val resolver: SectionResolver?,
        val sectionKeys: List<Double>,
        val allCodes: List<String>,
        val byCode: Boolean,
        val selectedCodes: List<String>,
        val order: SectionStationOrder,
        val codeSeries: SectionStationSeries?,
        val codeIndex: Int,
        val code: String?,
        val sectionError: AxisSectionError?,
    )

    /** Lo que hace falta de las secciones de un eje para el modo «Por código» (S050/RF-16, RF-17). */
    private class SectionData(val resolver: SectionResolver?, val keys: List<Double>, val codes: List<String>)

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
            byCode = model.byCode,
            availableCodes = model.allCodes,
            selectedCodes = model.selectedCodes,
            order = model.order,
            code = model.code,
            sectionSlope = sectionSlopeOf(model),
            codePosition = if (model.codeSeries != null) model.codeIndex + 1 else 0,
            codeCount = model.codeSeries?.size ?: 0,
            sectionError = model.sectionError,
            hasSections = model.resolver != null,
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

    /** Una longitud en metros como se escribe en el campo, en la unidad del proyecto y con punto decimal. */
    private fun unitText(valueM: Double): String =
        BigDecimal(unit.fromMetres(valueM)).setScale(UNIT_TEXT_DECIMALS, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()

    /**
     * Abre el replanteo por PK del eje [alignmentId] del proyecto [projectId], o del eje activo si es null (S054/RF-1, RF-11). Empieza con
     * los campos vacíos, desplazamiento 0 y sin intervalo.
     */
    fun open(projectId: String, alignmentId: String?, guide: ProfileGuide? = null) {
        this.projectId.value = projectId
        controller.reset()
        viewModelScope.launch {
            val id = alignmentId ?: alignments.activeAlignmentId(projectId).first() ?: return@launch
            val alignment = alignments.alignment(id) ?: return@launch
            if (alignment.projectId != projectId) return@launch
            val project = projects.project(projectId)
            this@AxisStakeoutViewModel.project = project
            model.value = newModel(projectId, alignment, null, sectionData(alignment))
            log.info(LogCategory.POINTS, "Replanteo por PK de un eje abierto") // S014/RF-5
            if (guide != null) {
                // S052/RF-10: un perfil longitudinal guiado empieza ya con su serie de PK, del inicial al final cada intervalo.
                setup {
                    it.copy(
                        startText = unitText(guide.fromM),
                        endText = guide.toM?.let(::unitText) ?: it.endText,
                        intervalText = guide.intervalM?.let(::unitText) ?: it.intervalText,
                    )
                }
                start()
            }
        }
    }

    /** Las secciones del eje: el resolvedor, los PK singulares de la sección y el peralte, y los códigos que hay (S050/RF-16, RF-17). */
    private suspend fun sectionData(alignment: Alignment): SectionData {
        val templates = sections.templates(alignment.id).first().map { it.template }
        val stored = sections.sections(alignment.id).first().map { it.section }
        val rows = sections.superelevation(alignment.id).first()
        val designs = stored.filter { it.kind == es.topophone.core.model.alignment.section.SectionKind.DESIGN }
        if (templates.isEmpty() && designs.isEmpty()) return SectionData(null, emptyList(), emptyList())
        val codes = LinkedHashSet<String>()
        templates.forEach { t ->
            t.left.reversed().forEach { codes += it.code }
            codes += SectionCode.AXIS
            t.right.forEach { codes += it.code }
        }
        designs.forEach { s -> s.points.forEach { codes += it.code } }
        return SectionData(
            SectionResolver(templates, Superelevation(rows), stored),
            SectionKeyStations.of(templates, stored, rows),
            codes.toList(),
        )
    }

    private fun newModel(projectId: String, alignment: Alignment, previous: Model?, data: SectionData): Model {
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
            resolver = data.resolver,
            sectionKeys = data.keys,
            allCodes = data.codes,
            byCode = previous?.byCode == true && data.resolver != null,
            selectedCodes = previous?.selectedCodes.orEmpty().filter { it in data.codes },
            order = previous?.order ?: SectionStationOrder.BY_CHAINAGE,
            codeSeries = null,
            codeIndex = 0,
            code = null,
            sectionError = null,
        )
    }

    /** Cierra el replanteo y vuelve a los campos de partida, que conservan lo escrito (S054/RF-1). */
    fun close() = close(null)

    private fun close(reason: AxisClosedReason?) {
        val current = model.value ?: return
        controller.reset()
        model.value = if (reason != null) {
            current.copy(started = false, series = null, codeSeries = null, closed = reason)
        } else {
            current.copy(
                started = false,
                series = null,
                seriesIndex = 0,
                codeSeries = null,
                codeIndex = 0,
                code = null,
                errors = emptyMap(),
            )
        }
        if (reason != null) log.info(LogCategory.POINTS, "Replanteo por PK de un eje cerrado: se borró el eje")
    }

    // ---- campos de partida (solo antes de abrir el replanteo)

    fun setStart(text: String): Boolean = setup { it.copy(startText = text, errors = it.errors - AxisField.START) }

    fun setEnd(text: String): Boolean = setup { it.copy(endText = text, errors = it.errors - AxisField.END) }

    fun setInterval(text: String): Boolean = setup { it.copy(intervalText = text, errors = it.errors - AxisField.INTERVAL) }

    fun setSingular(value: Boolean): Boolean = setup { it.copy(singular = value) }

    /** Pasa al modo «Por código» o vuelve a «Por desplazamiento» (S050/RF-16); solo antes de abrir y si el eje tiene secciones. */
    fun setByCode(value: Boolean): Boolean = setup { if (it.resolver == null) it else it.copy(byCode = value, sectionError = null) }

    /** Elige o quita un código del replanteo por código; el orden de elección es el de recorrido dentro de cada PK (S050/RF-16). */
    fun toggleCode(code: String): Boolean = setup {
        if (code !in it.allCodes) {
            it
        } else {
            it.copy(
                selectedCodes = if (code in it.selectedCodes) it.selectedCodes - code else it.selectedCodes + code,
                sectionError = null,
            )
        }
    }

    /** El orden de recorrido: de PK en PK con todos los códigos, o de código en código (S050/RF-16). */
    fun setOrder(order: SectionStationOrder): Boolean = setup { it.copy(order = order) }

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
        if (m.byCode) return startByCode(m, startCheck.value, endCheck.value, intervalCheck.value, thicknessCheck.value)
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

    /**
     * Abre el replanteo por código (S050/RF-16, RF-21): la serie de pares PK y código; el primero que tiene punto de diseño es el de partida.
     * Si no hay códigos, o el PK inicial no tiene sección, o el código no existe en ella, no se abre y se dice el motivo.
     */
    private fun startByCode(m: Model, start: Double, end: Double?, interval: Double?, thickness: Double): Boolean {
        val resolver = m.resolver
        if (resolver == null || m.selectedCodes.isEmpty()) {
            model.value = m.copy(sectionError = AxisSectionError.NoCodes)
            return false
        }
        val series = SectionStationSeries(
            m.order,
            m.selectedCodes,
            start,
            end,
            interval,
            (m.keyStations + m.sectionKeys).takeIf { m.singular },
            m.geometry.endChainage,
        )
        var firstError: AxisSectionError? = null
        for (i in 0 until series.size) {
            val item = series.itemAt(i)
            when (val r = SectionStakeTarget.of(m.geometry, m.profile, resolver, item.chainageM, item.code, thickness)) {
                is SectionStakeResult.Target -> {
                    controller.reset()
                    model.value = m.copy(
                        started = true,
                        series = null,
                        codeSeries = series,
                        codeIndex = i,
                        code = item.code,
                        chainageM = item.chainageM,
                        chainageText = chainageFormat(item.chainageM),
                        offsetM = r.target.offsetM,
                        thicknessM = thickness,
                        errors = emptyMap(),
                        sectionError = null,
                    )
                    return true
                }
                else -> if (firstError == null) firstError = errorOf(r)
            }
        }
        model.value = m.copy(sectionError = firstError ?: AxisSectionError.NoCodes)
        return false
    }

    private fun errorOf(r: SectionStakeResult): AxisSectionError? = when (r) {
        is SectionStakeResult.OutsideRange -> AxisSectionError.NoSection(r.chainageM)
        is SectionStakeResult.Missing -> AxisSectionError.Missing(r.chainageM, r.codes, r.heightKindMismatch)
        is SectionStakeResult.NoCode -> AxisSectionError.NoCode(r.code, r.chainageM)
        else -> null
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
        if (current?.byCode == true) return false // S050/RF-16: en «Por código» el desplazamiento es el de la sección
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
    fun next(): Boolean = if (model.value?.byCode == true) moveCode(1) else move { it.next(it.indexFrom(model.value)) }

    /** El PK anterior de la serie (RF-3); no pasa del primero. */
    fun previous(): Boolean = if (model.value?.byCode == true) moveCode(-1) else move { it.previous(it.indexFrom(model.value)) }

    /**
     * El par siguiente o anterior de la serie por código que tiene punto de diseño (S050/RF-16, RF-21): los pares cuyo código no existe en
     * la sección de ese PK se saltan. Sin ninguno más allá, se queda donde está.
     */
    private fun moveCode(step: Int): Boolean = edit(resetGuidance = true) { m ->
        val series = m.codeSeries ?: return@edit m
        val resolver = m.resolver ?: return@edit m
        var i = m.codeIndex + step
        while (i in 0 until series.size) {
            val item: SectionStationItem = series.itemAt(i)
            val r = SectionStakeTarget.of(m.geometry, m.profile, resolver, item.chainageM, item.code, m.thicknessM)
            if (r is SectionStakeResult.Target) {
                return@edit m.copy(
                    codeIndex = i,
                    code = item.code,
                    chainageM = item.chainageM,
                    chainageText = chainageFormat(item.chainageM),
                    offsetM = r.target.offsetM,
                    errors = m.errors - AxisField.CHAINAGE,
                )
            }
            i += step
        }
        m
    }

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
            model.value = rebuilt(newModel(latest.projectId, alignment, latest, sectionData(alignment)))
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
        if (m.byCode) {
            val code = m.code ?: return null
            val resolver = m.resolver ?: return null
            return (
                SectionStakeTarget.of(
                    m.geometry,
                    m.profile,
                    resolver,
                    chainage,
                    code,
                    m.thicknessM,
                ) as? SectionStakeResult.Target
                )?.target
        }
        return AxisStakeTarget.of(m.geometry, m.profile, chainage, m.offsetM, m.thicknessM) as? AxisStakeResult.Target
    }

    /** La pendiente transversal en el punto de diseño del replanteo por código (S050/RF-18). */
    private fun sectionSlopeOf(m: Model): Double? {
        if (!m.byCode || !m.started) return null
        val chainage = m.chainageM ?: return null
        val code = m.code ?: return null
        val resolver = m.resolver ?: return null
        return (SectionStakeTarget.of(m.geometry, m.profile, resolver, chainage, code, m.thicknessM) as? SectionStakeResult.Target)?.slope
    }

    private fun chainageFormat(chainageM: Double): String = ChainageFormat.format(chainageM, unit, Locale.getDefault())

    private data class TargetKey(val alignmentId: String, val chainageM: Double, val offsetM: Double, val code: String?)

    @Volatile private var cached: Pair<TargetKey, StakeTarget?>? = null

    private fun designTarget(project: Project, m: Model, target: AxisStakeResult.Target): StakeTarget? {
        val key = TargetKey(m.alignment.id, target.chainageM, target.offsetM, m.code.takeIf { m.byCode })
        cached?.let { (k, t) -> if (k == key && t != null) return t }
        val typed = TypedCoordinates(EntryForm.PROJECT_CRS, target.point.easting, target.point.northing, null, HeightKind.PROJECT)
        val coordinates = (ManualCoordinates(calculator.get()).compute(typed, project.crs, project.heightSystem) as? ManualResult.Computed)
            ?.coordinates
        val result = coordinates?.let {
            StakeTarget(
                id =
                "axis:${m.alignment.name}:${(target.chainageM * MILLIMETRES).roundToLong()}" +
                    (m.code.takeIf { m.byCode }?.let { ":$it" } ?: ""),
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
            localization = activeLocalization.value?.params, // S039/RF-17
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
            sectionCode = m.code.takeIf { m.byCode }, // S050/RF-19
        )
    }

    private companion object {
        const val UNIT_TEXT_DECIMALS = 6

        const val MILLIMETRES = 1_000.0
    }
}
