package es.topophone.app.ui.stakeout

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.Lazy
import dagger.hilt.android.lifecycle.HiltViewModel
import es.topophone.app.receiver.CurrentTilt
import es.topophone.app.ui.cad.DesignSelection
import es.topophone.app.ui.collector.MeasurementInProgress
import es.topophone.core.model.cad.CadEntityKind
import es.topophone.core.model.format.LengthFormat
import es.topophone.core.model.format.NumberInput
import es.topophone.core.model.log.AppLog
import es.topophone.core.model.log.LogCategory
import es.topophone.core.model.point.EntryForm
import es.topophone.core.model.point.HeightKind
import es.topophone.core.model.point.LineInputError
import es.topophone.core.model.point.LineStakeoutMode
import es.topophone.core.model.point.PointCoordinates
import es.topophone.core.model.point.StakeSource
import es.topophone.core.model.point.StakeoutSettings
import es.topophone.core.model.point.TiltMode
import es.topophone.core.model.point.TypedCoordinates
import es.topophone.core.model.project.LinearUnit
import es.topophone.core.model.project.Project
import es.topophone.core.survey.LineCheck
import es.topophone.core.survey.LineInputs
import es.topophone.core.survey.LineLocator
import es.topophone.core.survey.LineRange
import es.topophone.core.survey.LineSeries
import es.topophone.core.survey.LineVertices
import es.topophone.core.survey.ManualCoordinates
import es.topophone.core.survey.ManualResult
import es.topophone.core.survey.PoleHeight
import es.topophone.core.survey.ProjectPositionCalculator
import es.topophone.core.survey.StakeLine
import es.topophone.core.survey.inverted
import es.topophone.data.cad.CadRepository
import es.topophone.data.point.CollectorPreferences
import es.topophone.data.point.StakeoutPreferences
import es.topophone.data.project.ProjectRepository
import es.topophone.data.receiver.ReceiverPreferences
import es.topophone.receiver.platform.Alerts
import es.topophone.receiver.session.ConnectionState
import es.topophone.receiver.session.ReceiverSession
import java.util.Locale
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Los campos que escribe el usuario en el replanteo de una línea (S041/RF-11, RF-16, RF-18). */
enum class LineField { OFFSET, A, B, INTERVAL }

/** Por qué se cierra el replanteo de una línea sin que el usuario lo pida (S041/RF-31). */
enum class LineClosedReason { DRAWING_DELETED, }

/** De qué línea del dibujo es el replanteo: capa, dibujo, tipo, longitud y cota (S041/RF-4). */
data class LineElementInfo(
    val layerId: String,
    val layerName: String,
    val drawingName: String,
    val kind: CadEntityKind,
    val lengthM: Double,
    val closed: Boolean,
    val minHeightM: Double?,
    val maxHeightM: Double?,
)

/**
 * Lo que muestra el replanteo de una línea (S041/RF-2 a RF-4, RF-6 a RF-9, RF-16 a RF-22, RF-31).
 *
 * @property mode el modo elegido, el mismo para todos los proyectos (RF-3).
 * @property offsetText los textos de los campos, en la unidad del proyecto: lo escrito aunque no se haya aplicado.
 * @property errors el motivo por el que lo escrito en un campo no se aplica (RF-21); lo aplicado sigue siendo el valor anterior.
 * @property vertices los vértices de "Por segmentos" con el elegido en [vertexIndex] (RF-6, RF-8).
 * @property series los puntos de "Intervalos" con el elegido en [seriesIndex], o null si aún no hay un intervalo válido (RF-18, RF-20).
 * @property measuring hay una medida en marcha: no se cambia nada (RF-22).
 * @property closed el replanteo se ha cerrado solo, y por qué (RF-31).
 */
data class LineStakeoutUiState(
    val project: Project? = null,
    val element: LineElementInfo? = null,
    val unsupported: Boolean = false,
    val mode: LineStakeoutMode = LineStakeoutMode.DEFAULT,
    val offsetText: String = "",
    val aText: String = "",
    val bText: String = "",
    val intervalText: String = "",
    val errors: Map<LineField, LineInputError> = emptyMap(),
    val vertices: LineVertices? = null,
    val vertexIndex: Int = 0,
    val series: LineSeries? = null,
    val seriesIndex: Int = 0,
    val settings: StakeoutSettings = StakeoutSettings(),
    val guidance: GuidanceState = GuidanceState.Off(GuidanceOff.NO_POSITION),
    val measuring: Boolean = false,
    val designElevationM: Double? = null,
    val elevationOverridden: Boolean = false,
    val closed: LineClosedReason? = null,
)

/**
 * El replanteo de una línea de un dibujo (S041): el modo, lo que escribe el usuario, el vértice o el punto de la serie elegido,
 * y la guía que sale de [StakeoutController] con el objetivo de cada época. Medir y guardar lo hace el recolector con lo que
 * se le dé aquí (T16); este modelo solo sabe si hay una medida en marcha (RF-22).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class LineStakeoutViewModel @Inject constructor(
    private val projects: ProjectRepository,
    private val cad: CadRepository,
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

    /** Todo lo de la línea abierta; null si no hay replanteo abierto. */
    private class Model(
        val projectId: String,
        val selection: DesignSelection,
        val line: StakeLine,
        val locator: LineLocator,
        val vertices: LineVertices,
        val offsetText: String,
        val aText: String,
        val bText: String,
        val intervalText: String,
        val offsetM: Double,
        val range: LineRange,
        val intervalM: Double?,
        val errors: Map<LineField, LineInputError>,
        val vertexIndex: Int,
        val seriesIndex: Int,
        val elevationOverrideM: Double?,
        val closed: LineClosedReason?,
    ) {
        fun copy(
            offsetText: String = this.offsetText,
            aText: String = this.aText,
            bText: String = this.bText,
            intervalText: String = this.intervalText,
            offsetM: Double = this.offsetM,
            range: LineRange = this.range,
            intervalM: Double? = this.intervalM,
            errors: Map<LineField, LineInputError> = this.errors,
            vertexIndex: Int = this.vertexIndex,
            seriesIndex: Int = this.seriesIndex,
            elevationOverrideM: Double? = this.elevationOverrideM,
            closed: LineClosedReason? = this.closed,
        ) = Model(
            projectId, selection, line, locator, vertices, offsetText, aText, bText, intervalText, offsetM, range, intervalM, errors,
            vertexIndex, seriesIndex, elevationOverrideM, closed,
        )

        val series: LineSeries? get() = intervalM?.let { LineSeries(line, range, it, offsetM) }
    }

    private val model = MutableStateFlow<Model?>(null)
    private val unsupported = MutableStateFlow(false)
    private val projectId = MutableStateFlow<String?>(null)
    private var drawingWatcher: Job? = null

    @Volatile private var project: Project? = null

    @Volatile private var settings = StakeoutSettings()

    @Volatile private var fixOnly = true

    @Volatile private var poleHeightM = PoleHeight.DEFAULT_M

    private val projectFlow = projectId.flatMapLatest { id -> if (id == null) flowOf(null) else projects.observe(id) }

    private data class Live(val settings: StakeoutSettings, val guidance: GuidanceState, val measuring: Boolean, val unsupported: Boolean)

    val state: StateFlow<LineStakeoutUiState> = combine(
        projectFlow,
        model,
        combine(stakeoutPreferences.settings, controller.state, inProgress.active, unsupported, ::Live),
    ) { project, model, live ->
        if (model == null) {
            return@combine LineStakeoutUiState(
                project = project,
                settings = live.settings,
                mode = live.settings.lineMode,
                unsupported = live.unsupported,
            )
        }
        val line = model.line
        val heights = (0 until line.vertexCount).mapNotNull { line.height(it) }
        val series = model.series
        LineStakeoutUiState(
            project = project,
            element = LineElementInfo(
                model.selection.ref.layerId,
                model.selection.layerName,
                model.selection.drawingName,
                model.selection.entity.kind,
                line.lengthM,
                line.closed,
                heights.minOrNull(),
                heights.maxOrNull(),
            ),
            mode = live.settings.lineMode,
            offsetText = model.offsetText,
            aText = model.aText,
            bText = model.bText,
            intervalText = model.intervalText,
            errors = model.errors,
            vertices = model.vertices,
            vertexIndex = model.vertexIndex,
            series = series,
            seriesIndex = model.seriesIndex.coerceIn(0, ((series?.size ?: 1) - 1).coerceAtLeast(0)),
            settings = live.settings,
            guidance = live.guidance,
            measuring = live.measuring,
            designElevationM = model.elevationOverrideM,
            elevationOverridden = model.elevationOverrideM != null,
            closed = model.closed,
            unsupported = live.unsupported,
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, LineStakeoutUiState())

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
        // S018/RF-25 y S041/RF-26: sin épocas la guía se apaga hasta que vuelvan.
        viewModelScope.launch {
            session.state.collect { snapshot ->
                if (snapshot.connection !is ConnectionState.Receiving && model.value != null) controller.onSilence(context())
            }
        }
    }

    /**
     * Abre el replanteo de la línea [selection] del proyecto [projectId] (S041/RF-2): empieza con 0 de desplazamiento, A en el inicio
     * y B en el final, sin intervalo y con el modo recordado (RF-3).
     */
    fun open(projectId: String, selection: DesignSelection) {
        this.projectId.value = projectId
        controller.reset()
        val line = StakeLine.of(selection.entity, withHeight = selection.hasElevation)
        if (line == null) {
            model.value = null
            unsupported.value = true
            return
        }
        unsupported.value = false
        val unit = project?.linearUnit ?: LinearUnit.METRE
        model.value = Model(
            projectId = projectId,
            selection = selection,
            line = line,
            locator = LineLocator(line),
            vertices = LineVertices.of(line, selection.entity.kind),
            offsetText = format(0.0, unit),
            aText = format(0.0, unit),
            bText = format(line.lengthM, unit),
            intervalText = "",
            offsetM = 0.0,
            range = LineRange.whole(line),
            intervalM = null,
            errors = emptyMap(),
            vertexIndex = 0,
            seriesIndex = 0,
            elevationOverrideM = null,
            closed = null,
        )
        anchor = null
        cached = null
        watchDrawing(projectId, selection.ref.layerId)
        log.info(LogCategory.POINTS, "Replanteo de una línea abierto") // S014/RF-5
    }

    /** Cierra el replanteo y empieza de cero. */
    fun close() {
        drawingWatcher?.cancel()
        model.value = null
        unsupported.value = false
        controller.reset()
    }

    // S041/RF-31: si el dibujo de la línea se borra con el replanteo abierto, se cierra y se dice por qué.
    private fun watchDrawing(projectId: String, layerId: String) {
        drawingWatcher?.cancel()
        drawingWatcher = viewModelScope.launch {
            cad.drawings(projectId).collect { drawings ->
                val exists = drawings.any { d -> d.layers.any { it.id == layerId } }
                if (!exists && model.value != null) {
                    model.value = model.value?.copy(closed = LineClosedReason.DRAWING_DELETED)
                    controller.reset()
                    log.info(LogCategory.POINTS, "Replanteo de una línea cerrado: se borró el dibujo")
                }
            }
        }
    }

    /** S041/RF-3, RF-22: el modo se recuerda para todos los proyectos y no cambia con una medida en marcha. */
    fun setMode(mode: LineStakeoutMode): Boolean {
        if (inProgress.active.value || model.value?.closed != null || model.value == null) return false
        controller.reset()
        viewModelScope.launch { stakeoutPreferences.save(stakeoutPreferences.settings.first().copy(lineMode = mode)) }
        return true
    }

    /** El desplazamiento lateral escrito en la unidad del proyecto (RF-11); false si no se aplica (RF-21, RF-22). */
    fun setOffset(text: String): Boolean = edit { m, unit ->
        when (val check = LineInputs.offset(text)) {
            is LineCheck.Valid -> m.copy(offsetText = text, offsetM = unit.toMetres(check.value), errors = m.errors - LineField.OFFSET)
            is LineCheck.Invalid -> m.copy(offsetText = text, errors = m.errors + (LineField.OFFSET to check.error))
        }
    }

    /** El PK de A escrito en la unidad del proyecto (RF-16). */
    fun setA(text: String): Boolean = edit { m, unit -> withRange(m, unit, text, m.bText) }

    /** El PK de B escrito en la unidad del proyecto (RF-16). */
    fun setB(text: String): Boolean = edit { m, unit -> withRange(m, unit, m.aText, text) }

    /** El intervalo escrito en la unidad del proyecto (RF-18, RF-19). */
    fun setInterval(text: String): Boolean = edit { m, unit ->
        when (val check = LineInputs.interval(text)) {
            is LineCheck.Valid -> m.copy(
                intervalText = text,
                intervalM = unit.toMetres(check.value),
                errors = m.errors - LineField.INTERVAL,
            )
            is LineCheck.Invalid -> m.copy(intervalText = text, errors = m.errors + (LineField.INTERVAL to check.error))
        }
    }

    /** "Invertir": A y B cambian entre sí (RF-16). */
    fun invert(): Boolean = edit { m, _ ->
        val range = m.range.inverted()
        m.copy(aText = m.bText, bText = m.aText, range = range, errors = m.errors - LineField.A - LineField.B)
    }

    /** "Mi posición": A pasa a ser el PK del punto más cercano a la punta (RF-16); false sin guía. */
    fun useMyPosition(): Boolean {
        val here = tipChainage() ?: return false
        return edit { m, unit -> withRange(m, unit, format(here, unit), m.bText) }
    }

    private fun withRange(m: Model, unit: LinearUnit, aText: String, bText: String): Model {
        val a = LineInputs.parse(aText)?.let(unit::toMetres)
        val b = LineInputs.parse(bText)?.let(unit::toMetres)
        val errors = m.errors - LineField.A - LineField.B
        return when (val check = LineInputs.range(m.line, a, b)) {
            is LineCheck.Valid -> m.copy(aText = aText, bText = bText, range = check.value, errors = errors)
            is LineCheck.Invalid -> {
                val field = when {
                    a == null -> LineField.A
                    b == null -> LineField.B
                    check.error == LineInputError.OUT_OF_LINE && (a < 0.0 || a > m.line.lengthM) -> LineField.A
                    else -> LineField.B
                }
                m.copy(aText = aText, bText = bText, errors = errors + (field to check.error))
            }
        }
    }

    /** El vértice [index] de la lista de "Por segmentos" como punto de diseño (RF-8). */
    fun selectVertex(index: Int): Boolean = edit(resetGuidance = true) { m, _ ->
        m.copy(vertexIndex = index.coerceIn(0, m.vertices.size - 1))
    }

    /** "Siguiente" en "Por segmentos" (RF-7) o en "Intervalos" (RF-20). */
    fun next(): Boolean = move(+1)

    /** "Anterior" en "Por segmentos" (RF-7) o en "Intervalos" (RF-20). */
    fun previous(): Boolean = move(-1)

    private fun move(step: Int): Boolean = edit(resetGuidance = true) { m, _ ->
        when (settings.lineMode) {
            LineStakeoutMode.BY_SEGMENTS -> m.copy(
                vertexIndex = if (step >
                    0
                ) {
                    m.vertices.next(m.vertexIndex)
                } else {
                    m.vertices.previous(m.vertexIndex)
                },
            )
            LineStakeoutMode.INTERVALS -> {
                val last = (m.series?.size ?: 1) - 1
                m.copy(seriesIndex = (m.seriesIndex + step).coerceIn(0, last.coerceAtLeast(0)))
            }
            else -> m
        }
    }

    /** El punto [index] de la serie de "Intervalos" como punto de diseño (RF-20). */
    fun selectSeriesPoint(index: Int): Boolean = edit(resetGuidance = true) { m, _ ->
        m.copy(seriesIndex = index.coerceIn(0, ((m.series?.size ?: 1) - 1).coerceAtLeast(0)))
    }

    /** La cota de diseño escrita en la unidad del proyecto, en lugar de la de la línea (S019/RF-2, S041/RF-24); false si no es un número. */
    fun setDesignElevation(text: String): Boolean {
        val unit = project?.linearUnit ?: LinearUnit.METRE
        val metres = NumberInput.parse(text)?.let(unit::toMetres)?.takeIf { it.isFinite() } ?: return false
        return edit { m, _ -> m.copy(elevationOverrideM = metres) }
    }

    /** Descarta la cota escrita y vuelve a la de la línea (S019/RF-3). */
    fun useLineElevation(): Boolean = edit { m, _ -> m.copy(elevationOverrideM = null) }

    /** Con una medida en marcha no se cambia nada (RF-22). */
    private fun edit(resetGuidance: Boolean = false, transform: (Model, LinearUnit) -> Model): Boolean {
        if (inProgress.active.value) return false
        val current = model.value ?: return false
        if (current.closed != null) return false
        model.value = transform(current, project?.linearUnit ?: LinearUnit.METRE)
        if (resetGuidance) controller.reset()
        return true
    }

    private fun format(valueM: Double, unit: LinearUnit): String = LengthFormat.inUnit(valueM, unit, Locale.getDefault())

    /** El PK del punto más cercano a la punta, de la última época de la guía; null con la guía apagada (RF-16). */
    private fun tipChainage(): Double? {
        val m = model.value ?: return null
        val ui = (controller.state.value as? GuidanceState.On)?.ui ?: return null
        val design = ui.objective?.let { it.easting to it.northing } ?: (anchorPoint()?.let { it.easting!! to it.northing!! })
        val base = design ?: return null
        return m.locator.locate(base.first + ui.tipEastM, base.second + ui.tipNorthM).chainageM
    }

    @Volatile private var anchor: PointCoordinates? = null

    private fun anchorPoint(): PointCoordinates? = anchor

    /** Los objetivos fijos y el ancla de la línea, con coordenadas de suelo para guardarlos (S041/RF-28). */
    private data class TargetKey(val vertex: Int?, val series: Int?, val offsetM: Double, val range: LineRange, val intervalM: Double?)

    @Volatile private var cached: Pair<TargetKey, StakeTarget?>? = null

    private fun coordinatesOf(project: Project, east: Double, north: Double, height: Double?): PointCoordinates? {
        val typed = TypedCoordinates(EntryForm.PROJECT_CRS, east, north, height, HeightKind.PROJECT)
        val result = ManualCoordinates(calculator.get()).compute(typed, project.crs, project.heightSystem)
        return (result as? ManualResult.Computed)?.coordinates
    }

    private fun context(): StakeContext? {
        val project = project ?: return null
        val m = model.value ?: return null
        if (m.closed != null) return null
        val design = designTarget(project, m) ?: return null
        val mode: TiltMode? = tilt.state.value.settings.mode
        val guide = when (settings.lineMode) {
            LineStakeoutMode.TO_LINE -> LineGuide(m.line, m.locator, LineStakeoutMode.TO_LINE)
            LineStakeoutMode.ALONG -> LineGuide(m.line, m.locator, LineStakeoutMode.ALONG, m.offsetM)
            LineStakeoutMode.SEGMENT_AB -> LineGuide(m.line, m.locator, LineStakeoutMode.SEGMENT_AB, m.offsetM, m.range)
            else -> null
        }
        return StakeContext(settings, design, fixOnly, mode, poleHeightM, project, calculator.get(), m.elevationOverrideM, guide)
    }

    /** El objetivo de ahora: el vértice o el punto de la serie elegido, o el primer vértice, que solo da nombre y origen a una línea que guía. */
    private fun designTarget(project: Project, m: Model): StakeTarget? {
        val mode = settings.lineMode
        val key = TargetKey(
            vertex = m.vertexIndex.takeIf { mode == LineStakeoutMode.BY_SEGMENTS },
            series = m.seriesIndex.takeIf { mode == LineStakeoutMode.INTERVALS },
            offsetM = m.offsetM,
            range = m.range,
            intervalM = m.intervalM,
        )
        cached?.let { (k, t) -> if (k == key && t != null) return t }
        val line = m.line
        val (east, north, height) = when (mode) {
            LineStakeoutMode.BY_SEGMENTS -> {
                val entry = m.vertices.entries.getOrNull(m.vertexIndex) ?: return null
                Triple(entry.point.easting, entry.point.northing, entry.heightM)
            }
            LineStakeoutMode.INTERVALS -> {
                val series = m.series ?: return null
                val point = series[m.seriesIndex.coerceIn(0, series.size - 1)]
                Triple(point.point.easting, point.point.northing, point.heightM)
            }
            else -> Triple(line.easting(0), line.northing(0), line.height(0))
        }
        val coordinates = coordinatesOf(project, east, north, height) ?: return null
        if (mode != LineStakeoutMode.BY_SEGMENTS && mode != LineStakeoutMode.INTERVALS) anchor = coordinates
        val selection = m.selection
        val target = StakeTarget(
            id = "cad:${selection.ref.layerId}:${selection.ref.chunkId}:${selection.ref.index}",
            name = selection.layerName,
            description = "",
            coordinates = coordinates,
            source = StakeSource.CadElement(selection.drawingName, selection.layerName, selection.entity.kind),
        )
        cached = key to target
        return target
    }
}
