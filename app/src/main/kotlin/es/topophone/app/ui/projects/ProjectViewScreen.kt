package es.topophone.app.ui.projects

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.Lazy
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.lifecycle.HiltViewModel
import es.topophone.app.R
import es.topophone.app.receiver.CurrentTilt
import es.topophone.app.ui.SectionScaffold
import es.topophone.app.ui.alignment.ALIGNMENTS_BUTTON_TAG
import es.topophone.app.ui.alignment.AlignmentFromDrawingUiState
import es.topophone.app.ui.alignment.AlignmentFromDrawingViewModel
import es.topophone.app.ui.alignment.AlignmentMapViewModel
import es.topophone.app.ui.alignment.AlignmentScene
import es.topophone.app.ui.alignment.AlignmentTouch
import es.topophone.app.ui.alignment.AlignmentTouchSheet
import es.topophone.app.ui.alignment.AlignmentsActions
import es.topophone.app.ui.alignment.AlignmentsSheet
import es.topophone.app.ui.alignment.AlignmentsUiState
import es.topophone.app.ui.alignment.AlignmentsViewModel
import es.topophone.app.ui.alignment.AxisAnalysisActions
import es.topophone.app.ui.alignment.AxisAnalysisPanel
import es.topophone.app.ui.alignment.AxisAnalysisUiState
import es.topophone.app.ui.alignment.AxisAnalysisViewModel
import es.topophone.app.ui.alignment.ConvertToAlignmentDialog
import es.topophone.app.ui.cad.DRAWINGS_BUTTON_TAG
import es.topophone.app.ui.cad.DesignElementSheet
import es.topophone.app.ui.cad.DesignMapViewModel
import es.topophone.app.ui.cad.DesignScene
import es.topophone.app.ui.cad.DesignSelection
import es.topophone.app.ui.cad.DrawingsActions
import es.topophone.app.ui.cad.DrawingsSheet
import es.topophone.app.ui.collector.AutoLive
import es.topophone.app.ui.collector.AverageStatsUi
import es.topophone.app.ui.collector.COLLECT_BUTTON_TAG
import es.topophone.app.ui.collector.CollectorActions
import es.topophone.app.ui.collector.CollectorCodeSource
import es.topophone.app.ui.collector.CollectorPanel
import es.topophone.app.ui.collector.CollectorUiState
import es.topophone.app.ui.collector.CollectorViewModel
import es.topophone.app.ui.collector.SurfaceReading
import es.topophone.app.ui.collector.rememberCollectorPhotoLaunchers
import es.topophone.app.ui.geometry.DesignGeometryDialog
import es.topophone.app.ui.geometry.DesignGeometryInput
import es.topophone.app.ui.linear.AxisSurveyActions
import es.topophone.app.ui.linear.AxisSurveyMarkers
import es.topophone.app.ui.linear.AxisSurveyPanel
import es.topophone.app.ui.linear.AxisSurveyUiState
import es.topophone.app.ui.linear.AxisSurveyViewModel
import es.topophone.app.ui.linear.CrossActions
import es.topophone.app.ui.linear.CrossPanel
import es.topophone.app.ui.linear.CrossUiState
import es.topophone.app.ui.linear.CrossViewModel
import es.topophone.app.ui.linear.LongitudinalActions
import es.topophone.app.ui.linear.LongitudinalPanel
import es.topophone.app.ui.linear.LongitudinalUiState
import es.topophone.app.ui.linear.LongitudinalViewModel
import es.topophone.app.ui.lines.LineMapSheet
import es.topophone.app.ui.map.AttributionSource
import es.topophone.app.ui.map.CameraCommand
import es.topophone.app.ui.map.CameraPolicy
import es.topophone.app.ui.map.LineScene
import es.topophone.app.ui.map.MapAttribution
import es.topophone.app.ui.map.MapCamera
import es.topophone.app.ui.map.MapController
import es.topophone.app.ui.map.MapEntryPoint
import es.topophone.app.ui.map.MapLibreMap
import es.topophone.app.ui.map.MapLines
import es.topophone.app.ui.map.MapPoints
import es.topophone.app.ui.map.MapTap
import es.topophone.app.ui.map.NetworkFailureMonitor
import es.topophone.app.ui.map.RoverMarker
import es.topophone.app.ui.map.SCALE_BAR_MAX_DP
import es.topophone.app.ui.points.OBJECTS_BUTTON_TAG
import es.topophone.app.ui.points.ObjectLines
import es.topophone.app.ui.receiver.ReceiverViewModel
import es.topophone.app.ui.receiver.tone
import es.topophone.app.ui.receiver.uiLocale
import es.topophone.app.ui.slope.SlopeStakeActions
import es.topophone.app.ui.slope.SlopeStakePanel
import es.topophone.app.ui.slope.SlopeStakeUiState
import es.topophone.app.ui.slope.SlopeStakeViewModel
import es.topophone.app.ui.stakeout.AxisStakeoutActions
import es.topophone.app.ui.stakeout.AxisStakeoutMarkers
import es.topophone.app.ui.stakeout.AxisStakeoutPanel
import es.topophone.app.ui.stakeout.AxisStakeoutUiState
import es.topophone.app.ui.stakeout.AxisStakeoutViewModel
import es.topophone.app.ui.stakeout.LineSource
import es.topophone.app.ui.stakeout.LineStakeoutActions
import es.topophone.app.ui.stakeout.LineStakeoutMarkers
import es.topophone.app.ui.stakeout.LineStakeoutPanel
import es.topophone.app.ui.stakeout.LineStakeoutUiState
import es.topophone.app.ui.stakeout.LineStakeoutViewModel
import es.topophone.app.ui.stakeout.ProfileGuide
import es.topophone.app.ui.stakeout.StakeoutActions
import es.topophone.app.ui.stakeout.StakeoutMarkers
import es.topophone.app.ui.stakeout.StakeoutPanel
import es.topophone.app.ui.stakeout.StakeoutUiState
import es.topophone.app.ui.stakeout.StakeoutViewModel
import es.topophone.app.ui.surface.SURFACES_BUTTON_TAG
import es.topophone.app.ui.surface.SurfacesActions
import es.topophone.app.ui.surface.SurfacesSheet
import es.topophone.app.ui.surface.SurfacesUiState
import es.topophone.app.ui.surface.SurfacesViewModel
import es.topophone.core.designsystem.component.MinTouchTarget
import es.topophone.core.designsystem.theme.LocalStatusPalette
import es.topophone.core.geodesy.GeoPoint
import es.topophone.core.geodesy.WebMercator
import es.topophone.core.gnss.nmea.GgaFix
import es.topophone.core.gnss.nmea.SolutionStatus
import es.topophone.core.gnss.nmea.TiltData
import es.topophone.core.model.cad.CadEntityKind
import es.topophone.core.model.codes.CodeKey
import es.topophone.core.model.codes.CodeStyle
import es.topophone.core.model.format.AngleFormat
import es.topophone.core.model.format.FactorFormat
import es.topophone.core.model.format.LengthFormat
import es.topophone.core.model.map.Basemap
import es.topophone.core.model.map.MapSettings
import es.topophone.core.model.map.MapViewport
import es.topophone.core.model.map.ScaleBar
import es.topophone.core.model.point.PointSummary
import es.topophone.core.model.preferences.ThemeState
import es.topophone.core.model.project.CrsArea
import es.topophone.core.model.project.HeightSystem
import es.topophone.core.model.project.LinearUnit
import es.topophone.core.model.project.Project
import es.topophone.core.survey.LiveTip
import es.topophone.core.survey.PositionUnavailable
import es.topophone.core.survey.ProjectPosition
import es.topophone.core.survey.ProjectPositionCalculator
import es.topophone.data.baseshift.ActiveBaseShift
import es.topophone.data.cad.CadDrawingWithLayers
import es.topophone.data.map.MapPreferences
import es.topophone.data.map.MapViewportRepository
import es.topophone.data.point.PointRepository
import es.topophone.data.project.ProjectRepository
import es.topophone.data.receiver.ReceiverPreferences
import es.topophone.receiver.session.ConnectionState
import es.topophone.receiver.session.ReceiverSession
import javax.inject.Inject
import javax.inject.Named
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

const val PROJECT_VIEW_TAG = "project_view"
const val PROJECT_EDIT_TAG = "project_edit"
const val PROJECT_INFO_BUTTON_TAG = "project_info_button"
const val PROJECT_MAP_TAG = "project_map"
const val POSITION_UNCOMPENSATED_TAG = "position_uncompensated"
const val POSITION_PANEL_TAG = "position_panel"
const val POSITION_EASTING_TAG = "position_easting"
const val POSITION_NORTHING_TAG = "position_northing"
const val POSITION_HEIGHT_TAG = "position_height"
const val POSITION_LATITUDE_TAG = "position_latitude"
const val POSITION_LONGITUDE_TAG = "position_longitude"
const val ACCURACY_TAG = "position_accuracy"
const val SCALE_FACTOR_TAG = "position_scale_factor"
const val CONVERGENCE_TAG = "position_convergence"
const val COMBINED_FACTOR_TAG = "position_combined_factor"
const val OTHER_ZONE_TAG = "position_other_zone"
const val LAYERS_BUTTON_TAG = "map_layers"
const val CENTER_BUTTON_TAG = "map_center_rover"
const val OFFLINE_BANNER_TAG = "map_offline"
const val SCALE_BAR_TAG = "map_scale_bar"
const val ATTRIBUTION_TAG = "map_attribution"
const val CADASTRE_SWITCH_TAG = "map_cadastre"

fun basemapOptionTag(basemap: Basemap) = "map_basemap_${basemap.name}"

/** Hueco del mapa en la vista de proyecto: recibe su modificador y avisa cuando el mapa está listo (ADR-9). */
typealias MapSlot = @Composable (Modifier, (MapController) -> Unit) -> Unit

/**
 * Lo que muestra la vista de proyecto (S006/RF-29, RF-30; S009/RF-1 a RF-4, RF-16): [position] es null sin
 * posición del receptor, [accuracyM] es la precisión horizontal de la GST (S002/RF-14) y [rover], lo que se
 * pinta en el mapa.
 */
data class ProjectViewUiState(
    val project: Project? = null,
    val position: ProjectPosition? = null,
    val accuracyM: Double? = null,
    val mapSettings: MapSettings = MapSettings(),
    val rover: Rover? = null,
    /** Último encuadre del proyecto (S009/RF-21), válido cuando [viewportLoaded]. */
    val savedViewport: MapViewport? = null,
    val viewportLoaded: Boolean = false,
    /** Fallan las descargas del mapa por falta de conexión (S009/RF-15). */
    val mapOffline: Boolean = false,
    /** Puntos del proyecto (S010/RF-20, RF-21). */
    val points: List<PointSummary> = emptyList(),
    /** El color y el símbolo vigentes de cada código del proyecto, por su clave (S024/RF-17, RF-19). */
    val codeStyles: Map<String, CodeStyle> = emptyMap(),
    /** La base tal como la da su fuente, mientras la haya (S015/RF-19). */
    val base: GeoPoint? = null,
    /** Las líneas y los polígonos del proyecto para el mapa, ya como GeoJSON (S025/RF-22). */
    val lineScene: LineScene? = null,
    /** El nombre de cada línea del proyecto, para la hoja que se abre al tocarla en el mapa (S027/RF-1). */
    val lineNames: Map<String, String> = emptyMap(),
    /** El modo usa la inclinación pero la época no está en estado 30: "Punta sin compensar" (S016/RF-17). */
    val uncompensated: Boolean = false,
)

/** El rover: posición de la GGA tal cual, su solución y la precisión horizontal de la GST (S009/RF-16, RF-17). */
data class Rover(val position: GeoPoint, val solution: SolutionStatus, val accuracyM: Double?)

/**
 * Proyecto abierto, que pasa a ser el primero de la lista (S006/RF-1), y la posición del receptor en
 * su SC con la altura del jalón de Receptor (S006/RF-29, RF-30), a 5 Hz como máximo (RNF-T13).
 */
@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
@HiltViewModel
class ProjectViewModel @Inject constructor(
    private val repository: ProjectRepository,
    session: ReceiverSession,
    preferences: ReceiverPreferences,
    calculator: Lazy<ProjectPositionCalculator>,
    private val mapPreferences: MapPreferences,
    private val viewports: MapViewportRepository,
    networkMonitor: NetworkFailureMonitor,
    points: PointRepository,
    @param:Named(ReceiverViewModel.UI_SAMPLE_MS) samplePeriodMs: Long,
    tilt: CurrentTilt = CurrentTilt.NONE,
    baseShift: ActiveBaseShift = ActiveBaseShift.NONE,
    codeSource: CollectorCodeSource = CollectorCodeSource.NONE,
    lines: ObjectLines = ObjectLines.NONE,
) : ViewModel() {
    private val projectId = MutableStateFlow<String?>(null)

    /** El desplazamiento de base activo: el rover del mapa y las coordenadas lo llevan (S020/RF-7). */
    private val activeShift = baseShift.track(projectId, viewModelScope)

    /** Encuadre guardado del proyecto: null hasta que se lee (S009/RF-21). */
    private val savedViewport = MutableStateFlow<SavedViewport?>(null)
    private val viewportChanges = MutableSharedFlow<MapViewport>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    init {
        // S009/RF-21: el encuadre se guarda, como mucho cada 2 s mientras se mueve el mapa.
        val changes = if (samplePeriodMs > 0) viewportChanges.sample(VIEWPORT_SAVE_MS) else viewportChanges
        viewModelScope.launch { changes.collect { viewport -> projectId.value?.let { viewports.save(it, viewport) } } }
    }

    private val project = projectId.flatMapLatest { id -> if (id == null) flowOf(null) else repository.observe(id) }

    /** Solo una GGA reciente, con la precisión de la GST: sin datos o sin receptor no se muestra una posición antigua. */
    private val fixes = (if (samplePeriodMs > 0) session.state.sample(samplePeriodMs) else session.state)
        .map { snapshot ->
            snapshot.gga?.takeIf { snapshot.connection is ConnectionState.Receiving }?.let {
                ReceiverFix(it, snapshot.gst?.horizontalM, snapshot.epoch?.gga, snapshot.epoch?.tilt)
            }
        }
        .distinctUntilChanged()

    /** S015/RF-19: la base de la sesión mientras la haya. */
    private val bases = (if (samplePeriodMs > 0) session.state.sample(samplePeriodMs) else session.state)
        .map { snapshot -> snapshot.base.position?.let { GeoPoint(it.latitudeDeg, it.longitudeDeg) } }
        .distinctUntilChanged()

    private val modes = tilt.state.map { it.settings.mode }.distinctUntilChanged()

    val state: StateFlow<ProjectViewUiState> = combine(
        project,
        combine(fixes, modes, ::Pair),
        preferences.poleHeightM,
        mapPreferences.settings,
        savedViewport,
    ) { project, (fix, mode), pole, map, viewport ->
        // S016/RF-16, RF-17: la punta del jalón, con la GGA y el ETC de la misma época si el modo usa la inclinación.
        val usesTilt = mode?.usesTilt == true
        val gga = fix?.let { if (usesTilt && it.epochGga != null) it.epochGga else it.gga }
        val tip = gga?.let { LiveTip.of(it, if (usesTilt) fix?.tilt else null, mode, pole, shift = activeShift.value?.vector) }
        // Con el SC del proyecto: si se cambia, la posición se recalcula en el nuevo (S006/RF-15).
        val position = tip?.let { project?.let { p -> calculator.get().fromGround(it.ground, p.crs, p.heightSystem) } }
        // S016/RF-21: el círculo mide la precisión de la GST más la de la inclinación.
        val accuracy = fix?.accuracyM?.let { it + (tip?.extraHorizontalM ?: 0.0) }
        // S009/RF-16: el rover en su posición real, sea cual sea el SC del proyecto.
        val rover = tip?.let { Rover(GeoPoint(it.ground.latitudeDeg, it.ground.longitudeDeg), checkNotNull(gga).status, accuracy) }
        ProjectViewUiState(
            project,
            position,
            accuracy,
            map,
            rover,
            savedViewport = viewport?.viewport,
            viewportLoaded =
            viewport != null,
            uncompensated = tip?.uncompensated == true,
        )
    }.combine(networkMonitor.offline) { state, offline -> state.copy(mapOffline = offline) }
        .combine(projectId.flatMapLatest { id -> if (id == null) flowOf(emptyList()) else points.summaries(id) }) { state, list ->
            state.copy(points = list)
        }
        .combine(bases) { state, base -> state.copy(base = base) }
        .combine(
            projectId.flatMapLatest { id ->
                if (id == null) {
                    flowOf(emptyMap())
                } else {
                    codeSource.available(id).map { list -> list.associate { CodeKey.of(it.code) to CodeStyle(it.color, it.symbol) } }
                }
            },
        ) { state, styles -> state.copy(codeStyles = styles) }
        .combine(
            projectId.flatMapLatest { id ->
                if (id == null) {
                    flowOf<Pair<LineScene?, Map<String, String>>>(null to emptyMap())
                } else {
                    // S025/RF-22: el GeoJSON de las lineas se escribe aqui, fuera del hilo principal.
                    combine(lines.lines(id), points.summaries(id)) { all, summaries ->
                        MapLines.scene(all, summaries) to all.associate { it.id to it.name }
                    }
                }
            },
        ) { state, (scene, names) -> state.copy(lineScene = scene, lineNames = names) }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), ProjectViewUiState())

    /** Mapa base y Catastro, para todos los proyectos (S009/RF-8). */
    fun setMapSettings(settings: MapSettings) {
        viewModelScope.launch { mapPreferences.save(settings) }
    }

    fun start(id: String) {
        if (projectId.value != null) return
        projectId.value = id
        viewModelScope.launch { repository.markOpened(id) }
        viewModelScope.launch { savedViewport.value = SavedViewport(viewports.viewport(id)) }
    }

    /** El mapa se ha movido: su encuadre pasa a ser el del proyecto (S009/RF-21). */
    fun onViewportChanged(viewport: MapViewport) {
        viewportChanges.tryEmit(viewport)
    }

    private data class SavedViewport(val viewport: MapViewport?)

    private data class ReceiverFix(val gga: GgaFix, val accuracyM: Double?, val epochGga: GgaFix?, val tilt: TiltData?)

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
        const val VIEWPORT_SAVE_MS = 2_000L
    }
}

/** Lo que la cámara del mapa espera quieta antes de recargar el dibujo importado (S040/RF-24). */
private const val DESIGN_DEBOUNCE_MS = 150L

@Composable
fun ProjectViewRoute(
    projectId: String,
    themeState: ThemeState,
    onToggleSun: () -> Unit,
    navigation: SurveyNavigation,
    stakeoutPointId: String? = null,
    stakeoutQuery: String = "",
    stakeoutLineId: String? = null,
    viewModel: ProjectViewModel = hiltViewModel(),
    collectorViewModel: CollectorViewModel = hiltViewModel(),
    stakeoutViewModel: StakeoutViewModel = hiltViewModel(),
    lineStakeoutViewModel: LineStakeoutViewModel = hiltViewModel(),
    axisStakeoutViewModel: AxisStakeoutViewModel = hiltViewModel(),
    slopeStakeViewModel: SlopeStakeViewModel = hiltViewModel(),
    axisSurveyViewModel: AxisSurveyViewModel = hiltViewModel(),
    longitudinalViewModel: LongitudinalViewModel = hiltViewModel(),
    crossViewModel: CrossViewModel = hiltViewModel(),
    axisAnalysisViewModel: AxisAnalysisViewModel = hiltViewModel(),
    designViewModel: DesignMapViewModel = hiltViewModel(),
    surfacesViewModel: SurfacesViewModel = hiltViewModel(),
    alignmentFromDrawingViewModel: AlignmentFromDrawingViewModel = hiltViewModel(),
    alignmentsViewModel: AlignmentsViewModel = hiltViewModel(),
    alignmentMapViewModel: AlignmentMapViewModel = hiltViewModel(),
) {
    LaunchedEffect(projectId) {
        viewModel.start(projectId)
        collectorViewModel.start(projectId)
        designViewModel.start(projectId) // S040/RF-24
        surfacesViewModel.start(projectId) // S042/RF-16
        alignmentsViewModel.start(projectId) // S049/RF-14
        alignmentMapViewModel.start(projectId) // S049/RF-17
        axisSurveyViewModel.start(projectId) // S052/RF-9
    }
    // S052/RF-1: la toma de un eje con el receptor, en el mismo hueco; el recolector mide y la toma recoge cada punto guardado.
    var axisSurveyOpen by rememberSaveable { mutableStateOf(false) }
    val axisSurvey by axisSurveyViewModel.state.collectAsStateWithLifecycle()
    // S052/RF-10: el perfil longitudinal de un eje, en el mismo hueco; guiado, usa además el replanteo por PK de ese eje.
    var longitudinalOpen by rememberSaveable { mutableStateOf(false) }
    val longitudinal by longitudinalViewModel.state.collectAsStateWithLifecycle()
    // S052/RF-16: el perfil transversal de un PK de un eje, en el mismo hueco, con la guía de replanteo hacia ese PK.
    var crossOpen by rememberSaveable { mutableStateOf(false) }
    val cross by crossViewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(axisSurveyOpen, longitudinalOpen, crossOpen) {
        collectorViewModel.setPointSavedListener(
            when {
                axisSurveyOpen -> axisSurveyViewModel.pointListener // S052/RF-2
                longitudinalOpen -> longitudinalViewModel.pointListener // S052/RF-11
                crossOpen -> crossViewModel.pointListener // S052/RF-17
                else -> null
            },
        )
    }
    LaunchedEffect(longitudinalOpen) { if (longitudinalOpen) longitudinalViewModel.refreshSelection() }
    // S051/RF-9: el replanteo de los taludes de un eje, en el mismo hueco, con su propio modelo.
    var slopeOpen by rememberSaveable { mutableStateOf(false) }
    val slope by slopeStakeViewModel.state.collectAsStateWithLifecycle()
    // S018/RF-1: el replanteo se abre desde la ficha o la lista con el punto elegido, y se cierra con "Cerrar".
    var stakeoutOpen by rememberSaveable { mutableStateOf(stakeoutPointId != null) }
    LaunchedEffect(stakeoutPointId) {
        if (stakeoutPointId != null) {
            axisSurveyOpen = false
            longitudinalOpen = false
            crossOpen = false
            slopeOpen = false
            slopeStakeViewModel.close()
            stakeoutViewModel.open(projectId, stakeoutPointId, stakeoutQuery)
        }
    }
    val stakeout by stakeoutViewModel.state.collectAsStateWithLifecycle()
    // S041/RF-2: el replanteo de una línea del dibujo, en el mismo hueco, con su propio modelo.
    var lineOpen by rememberSaveable { mutableStateOf(false) }
    val line by lineStakeoutViewModel.state.collectAsStateWithLifecycle()
    val lineHighlight by lineStakeoutViewModel.highlight.collectAsStateWithLifecycle()
    // S054/RF-1: el replanteo por PK y desplazamiento de un eje, en el mismo hueco, con su propio modelo.
    var axisOpen by rememberSaveable { mutableStateOf(false) }
    val axis by axisStakeoutViewModel.state.collectAsStateWithLifecycle()
    // S054/RF-12: «Analizar» la posición del jalón respecto a un eje, en el mismo hueco.
    var analysisOpen by rememberSaveable { mutableStateOf(false) }
    val analysis by axisAnalysisViewModel.state.collectAsStateWithLifecycle()
    // S054/RF-13: con el análisis abierto, el recolector guarda lo que mide con su PK, su desplazamiento y su diferencia de cota.
    LaunchedEffect(analysisOpen) { collectorViewModel.setAxisReference(axisAnalysisViewModel.referenceProvider.takeIf { analysisOpen }) }
    // S027/RF-1: el replanteo de una línea del proyecto, desde su ficha, su fila de la lista o la hoja del mapa, en el mismo hueco.
    val startProjectLine: (String) -> Unit = { lineId ->
        designViewModel.dismissSelection()
        axisSurveyOpen = false
        longitudinalOpen = false
        crossOpen = false
        axisOpen = false
        axisStakeoutViewModel.close()
        analysisOpen = false
        axisAnalysisViewModel.close()
        slopeOpen = false
        slopeStakeViewModel.close()
        stakeoutOpen = false
        stakeoutViewModel.close()
        lineStakeoutViewModel.open(projectId, LineSource.ProjectLine(lineId))
        lineOpen = true
    }
    LaunchedEffect(stakeoutLineId) { if (stakeoutLineId != null) startProjectLine(stakeoutLineId) }
    // El recolector mide y guarda el punto replanteado con el punto de diseño de ahora (S018/A5, S041/RF-27, S054/RF-10).
    val activeTarget = when {
        stakeoutOpen -> stakeout.target
        axisOpen -> axis.stakeTarget
        slopeOpen -> slope.stakeTarget
        else -> line.target.takeIf { lineOpen }
    }
    LaunchedEffect(stakeoutOpen, lineOpen, axisOpen, slopeOpen, activeTarget) { collectorViewModel.setStakeTarget(activeTarget) }
    // S019/RF-13: la cota usada y las tolerancias vigentes viajan con el recolector para guardarse con el replanteo.
    val activeInfo = when {
        stakeoutOpen -> stakeout.saveInfo
        axisOpen -> axis.saveInfo
        slopeOpen -> slope.saveInfo
        else -> line.saveInfo.takeIf { lineOpen }
    }
    LaunchedEffect(stakeoutOpen, lineOpen, axisOpen, slopeOpen, activeInfo) { collectorViewModel.setStakeSaveInfo(activeInfo) }
    // S041/RF-28 y S054/RF-10: con una línea o un eje, el recolector pide el objetivo de ese momento al terminar la medida.
    LaunchedEffect(lineOpen, axisOpen, slopeOpen) {
        collectorViewModel.setStakeObjective(
            when {
                axisOpen -> axisStakeoutViewModel.objectiveProvider
                slopeOpen -> slopeStakeViewModel.objectiveProvider
                lineOpen -> lineStakeoutViewModel.objectiveProvider
                else -> null
            },
        )
    }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val design by designViewModel.scene.collectAsStateWithLifecycle()
    val drawings by designViewModel.drawings.collectAsStateWithLifecycle()
    val surfaces by surfacesViewModel.state.collectAsStateWithLifecycle()
    val designSelection by designViewModel.selected.collectAsStateWithLifecycle()
    val alignmentFromDrawing by alignmentFromDrawingViewModel.state.collectAsStateWithLifecycle()
    val alignmentsState by alignmentsViewModel.state.collectAsStateWithLifecycle()
    val alignmentScene by alignmentMapViewModel.scene.collectAsStateWithLifecycle()
    val alignmentTouch by alignmentMapViewModel.touched.collectAsStateWithLifecycle()
    val sectionLinesOn by alignmentMapViewModel.sectionLines.collectAsStateWithLifecycle()
    LaunchedEffect(state.mapSettings.basemap) { designViewModel.setBasemap(state.mapSettings.basemap) } // S040/RF-25
    val collector by collectorViewModel.state.collectAsStateWithLifecycle()
    val collectorStats by collectorViewModel.stats.collectAsStateWithLifecycle() // S017/RF-3 a RF-5
    val collectorAutoLive by collectorViewModel.autoLive.collectAsStateWithLifecycle() // S017/RF-26
    val collectorSurface by collectorViewModel.surfaceReading.collectAsStateWithLifecycle() // S042/RF-33
    val photoLaunchers = rememberCollectorPhotoLaunchers(collectorViewModel.photos) // S017b/RF-1
    val context = LocalContext.current
    val environment =
        remember { EntryPointAccessors.fromApplication(context.applicationContext, MapEntryPoint::class.java).mapEnvironment() }
    ProjectViewScreen(
        themeState,
        onToggleSun,
        state,
        navigation.onBack,
        navigation.onEditProject,
        navigation.onOpenProjectInfo,
        viewModel::setMapSettings,
        viewModel::onViewportChanged,
        map = { modifier, onReady -> MapLibreMap(environment, onReady, modifier) },
        design = design,
        onDesignCamera = {
            designViewModel.onCamera(it)
            alignmentMapViewModel.onCamera(it) // S049/RF-17
        },
        alignmentScene = alignmentScene,
        alignmentTouch = alignmentTouch,
        onDismissAlignmentTouch = alignmentMapViewModel::dismissTouch,
        sectionLinesOn = sectionLinesOn,
        onSectionLines = alignmentMapViewModel::setSectionLines, // S050/RF-14
        onActivateAlignment = alignmentsViewModel::activate,
        onCalculateOnAlignment = { alignmentId -> navigation.onAlignmentPoint(projectId, alignmentId) }, // S049/RF-20
        onSectionsOfAxis = { alignmentId -> navigation.onSections(projectId, alignmentId) }, // S050/RF-1
        onSlopesOfAxis = { alignmentId -> navigation.onSlopes(projectId, alignmentId) }, // S051/RF-1
        onProfiles = { navigation.onLinearSurveys(projectId) }, // S052/RF-22
        drawings = drawings,
        designSelection = designSelection,
        onMapTap = {
            designViewModel.onTap(it)
            alignmentMapViewModel.onTap(it) // S049/RF-18
        },
        onDismissDesignSelection = designViewModel::dismissSelection,
        alignments = alignmentsState,
        alignmentsActions = AlignmentsActions(
            onActivate = alignmentsViewModel::activate, // S049/RF-14
            onRename = alignmentsViewModel::rename, // S049/RF-14
            onVisible = alignmentsViewModel::setVisible, // S049/RF-19
            onDelete = alignmentsViewModel::delete, // S049/RF-15
        ),
        onStakeAxis = { alignmentId ->
            // S054/RF-1: "Replantear eje" en la hoja «Ejes» y en la ficha de un eje; sustituye a cualquier otro replanteo abierto.
            axisSurveyOpen = false
            longitudinalOpen = false
            crossOpen = false
            stakeoutOpen = false
            stakeoutViewModel.close()
            lineOpen = false
            lineStakeoutViewModel.close()
            analysisOpen = false
            axisAnalysisViewModel.close()
            slopeOpen = false
            slopeStakeViewModel.close()
            axisStakeoutViewModel.open(projectId, alignmentId)
            axisOpen = true
        },
        onStakeSlopes = { alignmentId ->
            // S051/RF-9: "Replantear taludes" en la hoja «Ejes» y en la ficha de un eje; sustituye a cualquier otro replanteo abierto.
            axisSurveyOpen = false
            longitudinalOpen = false
            crossOpen = false
            stakeoutOpen = false
            stakeoutViewModel.close()
            lineOpen = false
            lineStakeoutViewModel.close()
            analysisOpen = false
            axisAnalysisViewModel.close()
            axisOpen = false
            axisStakeoutViewModel.close()
            slopeStakeViewModel.open(projectId, alignmentId)
            slopeOpen = true
        },
        onTakeAxis = {
            // S052/RF-1: «Tomar eje» en la hoja «Ejes»; sustituye a cualquier otro panel abierto y recupera la toma abierta, si la hay (RF-9).
            stakeoutOpen = false
            stakeoutViewModel.close()
            lineOpen = false
            lineStakeoutViewModel.close()
            analysisOpen = false
            axisAnalysisViewModel.close()
            axisOpen = false
            axisStakeoutViewModel.close()
            slopeOpen = false
            slopeStakeViewModel.close()
            longitudinalOpen = false
            crossOpen = false
            axisSurveyOpen = true
        },
        onLongitudinal = { alignmentId ->
            // S052/RF-10: «Perfil longitudinal» en la hoja «Ejes» y en la ficha de un eje; sustituye a cualquier otro panel abierto.
            stakeoutOpen = false
            stakeoutViewModel.close()
            lineOpen = false
            lineStakeoutViewModel.close()
            analysisOpen = false
            axisAnalysisViewModel.close()
            axisOpen = false
            axisStakeoutViewModel.close()
            slopeOpen = false
            slopeStakeViewModel.close()
            axisSurveyOpen = false
            crossOpen = false
            longitudinalViewModel.start(projectId, alignmentId)
            longitudinalOpen = true
        },
        onCross = { alignmentId ->
            // S052/RF-16: «Perfil transversal» en la hoja «Ejes» y en la ficha de un eje; sustituye a cualquier otro panel abierto.
            stakeoutOpen = false
            stakeoutViewModel.close()
            lineOpen = false
            lineStakeoutViewModel.close()
            analysisOpen = false
            axisAnalysisViewModel.close()
            axisOpen = false
            axisStakeoutViewModel.close()
            slopeOpen = false
            slopeStakeViewModel.close()
            axisSurveyOpen = false
            longitudinalOpen = false
            crossOpen = false
            crossViewModel.start(projectId, alignmentId)
            crossOpen = true
        },
        cross = cross.takeIf { crossOpen },
        crossActions = CrossActions(
            onClose = {
                crossOpen = false
                axisOpen = false
                axisStakeoutViewModel.close()
            },
            onSetup = crossViewModel::updateSetup,
            onBegin = {
                crossViewModel.begin { survey ->
                    // S052/RF-16: la guía de replanteo por PK lleva hasta el PK de la sección, sobre el eje.
                    axisStakeoutViewModel.open(projectId, survey.alignmentId, ProfileGuide.of(survey))
                    axisOpen = true
                }
            },
            onOpenGuide = {
                cross.survey?.let { survey ->
                    axisStakeoutViewModel.open(projectId, survey.alignmentId, ProfileGuide.of(survey))
                    axisOpen = true
                }
            },
            onRemove = crossViewModel::removePoint,
            onSave = crossViewModel::save,
            onDiscard = {
                crossViewModel.discard()
                axisOpen = false
                axisStakeoutViewModel.close()
            },
            onDismissSaved = {
                crossViewModel.dismissSaved()
                axisOpen = false
                axisStakeoutViewModel.close()
            },
        ),
        crossWriteCsv = crossViewModel::writeCsv,
        longitudinal = longitudinal.takeIf { longitudinalOpen },
        longitudinalActions = LongitudinalActions(
            onClose = {
                longitudinalOpen = false
                crossOpen = false
                axisOpen = false
                axisStakeoutViewModel.close()
            },
            onSetup = longitudinalViewModel::updateSetup,
            onBegin = {
                longitudinalViewModel.begin { survey ->
                    // S052/RF-10: guiado, la guía de replanteo por PK lleva del PK inicial al final cada intervalo.
                    if (survey.guided) {
                        axisStakeoutViewModel.open(projectId, survey.alignmentId, ProfileGuide.of(survey))
                        axisOpen = true
                    }
                }
            },
            onUseSelection = { longitudinalViewModel.addSelected() },
            onOpenGuide = {
                longitudinal.survey?.let { survey ->
                    axisStakeoutViewModel.open(projectId, survey.alignmentId, ProfileGuide.of(survey))
                    axisOpen = true
                }
            },
            onRemove = longitudinalViewModel::removePoint,
            onFinish = {
                longitudinalViewModel.finish()
                axisOpen = false
                axisStakeoutViewModel.close()
            },
            onDiscard = {
                longitudinalViewModel.discard()
                axisOpen = false
                axisStakeoutViewModel.close()
            },
        ),
        longitudinalWriteCsv = longitudinalViewModel::writeCsv,
        axisSurvey = axisSurvey.takeIf { axisSurveyOpen },
        axisSurveyActions = AxisSurveyActions(
            onClose = { axisSurveyOpen = false },
            onBegin = axisSurveyViewModel::begin,
            onRemoveLast = axisSurveyViewModel::removeLast,
            onStartFinish = axisSurveyViewModel::startFinish,
            onFinishDraft = axisSurveyViewModel::updateFinish,
            onCancelFinish = axisSurveyViewModel::cancelFinish,
            onCreate = axisSurveyViewModel::finish,
            onDiscard = axisSurveyViewModel::discard,
            onDismissResult = axisSurveyViewModel::dismissResult,
        ),
        onAnalyzeAxis = { alignmentId ->
            // S054/RF-12: «Analizar» en la hoja «Ejes» y en la ficha de un eje; sustituye a cualquier otro replanteo abierto.
            axisSurveyOpen = false
            longitudinalOpen = false
            crossOpen = false
            stakeoutOpen = false
            stakeoutViewModel.close()
            lineOpen = false
            lineStakeoutViewModel.close()
            axisOpen = false
            axisStakeoutViewModel.close()
            slopeOpen = false
            slopeStakeViewModel.close()
            axisAnalysisViewModel.open(projectId, alignmentId)
            analysisOpen = true
        },
        axisAnalysis = analysis.takeIf { analysisOpen },
        axisAnalysisActions = AxisAnalysisActions(
            onClose = {
                analysisOpen = false
                axisAnalysisViewModel.close()
            },
            onReference = { axisAnalysisViewModel.setReference(it) },
        ),
        slopeStake = slope.takeIf { slopeOpen },
        slopeStakeActions = SlopeStakeActions(
            onClose = {
                slopeOpen = false
                slopeStakeViewModel.close()
            },
            onStart = { slopeStakeViewModel.start() },
            onStartText = { slopeStakeViewModel.setStart(it) },
            onEndText = { slopeStakeViewModel.setEnd(it) },
            onIntervalText = { slopeStakeViewModel.setInterval(it) },
            onSingular = { slopeStakeViewModel.setSingular(it) },
            onSide = { slopeStakeViewModel.toggleSide(it) },
            onStakeOffset = { slopeStakeViewModel.setStakeOffset(it) },
            onPrevious = { slopeStakeViewModel.previous() },
            onNext = { slopeStakeViewModel.next() },
            onGuideMode = { slopeStakeViewModel.setGuideMode(it) },
            onHeading = { slopeStakeViewModel.setHeading(it) },
            onTolerance = slopeStakeViewModel::setTolerance,
            onToleranceV = slopeStakeViewModel::setToleranceV,
        ),
        axisStakeout = axis.takeIf { axisOpen },
        axisStakeoutActions = AxisStakeoutActions(
            onClose = {
                axisOpen = false
                axisStakeoutViewModel.close()
            },
            onStart = { axisStakeoutViewModel.start() },
            onAlignment = { axisStakeoutViewModel.setAlignment(it) }, // S054/RF-11
            onStartText = { axisStakeoutViewModel.setStart(it) },
            onEndText = { axisStakeoutViewModel.setEnd(it) },
            onIntervalText = { axisStakeoutViewModel.setInterval(it) },
            onSingular = { axisStakeoutViewModel.setSingular(it) },
            onByCode = { axisStakeoutViewModel.setByCode(it) }, // S050/RF-16
            onToggleCode = { axisStakeoutViewModel.toggleCode(it) },
            onOrder = { axisStakeoutViewModel.setOrder(it) },
            onChainage = { axisStakeoutViewModel.setChainage(it) }, // S054/RF-5
            onOffset = { axisStakeoutViewModel.setOffset(it) },
            onThickness = { axisStakeoutViewModel.setThickness(it) },
            onPrevious = { axisStakeoutViewModel.previous() },
            onNext = { axisStakeoutViewModel.next() },
            onGuideMode = { axisStakeoutViewModel.setGuideMode(it) },
            onHeading = { axisStakeoutViewModel.setHeading(it) },
            onTolerance = axisStakeoutViewModel::setTolerance,
            onToleranceV = axisStakeoutViewModel::setToleranceV,
        ),
        alignmentFromDrawing = alignmentFromDrawing,
        onConvertToAlignment = { selection, name, start -> alignmentFromDrawingViewModel.convert(projectId, selection, name, start) },
        onDismissAlignmentConversion = alignmentFromDrawingViewModel::dismiss,
        drawingsActions = DrawingsActions(
            onLayerVisible = designViewModel::setLayerVisible, // S040/RF-27
            onDeleteDrawing = designViewModel::deleteDrawing, // S040/RF-30
        ),
        surfaces = surfaces,
        surfacesActions = SurfacesActions(
            onActivate = surfacesViewModel::activate, // S042/RF-16
            onDelete = surfacesViewModel::delete, // S042/RF-18
            onOffset = surfacesViewModel::setOffset, // S042/RF-19, RF-20
        ),
        collector = collector,
        collectorStats = collectorStats,
        collectorAutoLive = collectorAutoLive,
        collectorSurface = collectorSurface,
        collectorActions = CollectorActions(
            onName = collectorViewModel::setName,
            onDescription = collectorViewModel::setDescription,
            onSelectCode = collectorViewModel::selectCode,
            onNewCode = { navigation.onNewProjectCode(projectId) },
            onEditCode = { navigation.onEditProjectCode(projectId, it.id) },
            onCodeQuery = collectorViewModel::setCodeQuery,
            onCodeAttribute = collectorViewModel::setCodeAttribute,
            onFinishLine = collectorViewModel::finishLine, // S025/RF-4
            onUndoLineVertex = collectorViewModel::undoLineVertex, // S025/RF-6
            onContinueLine = collectorViewModel::continueLine, // S025/RF-5
            onPoleHeight = collectorViewModel::setPoleHeight,
            onSelectPole = collectorViewModel::selectPole,
            onMode = { mode -> collectorViewModel.setSettings(collector.settings.copy(mode = mode)) },
            onSeconds = collectorViewModel::setAveragingSeconds,
            onSettings = collectorViewModel::setSettings,
            onMeasure = collectorViewModel::measure,
            onCancel = collectorViewModel::cancel,
            onConfirm = collectorViewModel::confirmOutOfTolerance,
            onDiscard = collectorViewModel::discardPending,
            onDismissBaseChange = collectorViewModel::dismissBaseChange,
            onDismissLongBaseline = collectorViewModel::dismissLongBaseline,
            onOpenTilt = navigation.onOpenTilt, // S016/RF-2, RF-18
            onFinish = collectorViewModel::finishNow, // S017/RF-6, RF-10
            onKeepWaiting = collectorViewModel::keepWaiting, // S017/RF-10
            onAutoEnabled = collectorViewModel::setAutoEnabled, // S017/RF-15
            onAutoTrigger = collectorViewModel::setAutoTrigger,
            onAutoInterval = collectorViewModel::setAutoInterval,
            onAutoDistance = collectorViewModel::setAutoDistance,
            onStartAuto = collectorViewModel::startAuto, // S017/RF-19
            onStopAuto = collectorViewModel::stopAuto, // S017/RF-27
            onDeactivateBaseShift = collectorViewModel::deactivateBaseShift, // S020/RF-11
            onRepeatBaseShift = { navigation.onBaseShift(projectId) }, // S020/RF-15
            onAcknowledgeBaseShiftDatum = collectorViewModel::acknowledgeBaseShiftDatum, // S020/RF-16
            onPhoto = photoLaunchers.camera, // S017b/RF-1
            onGallery = photoLaunchers.gallery,
            onDiscardPhotos = collectorViewModel::confirmCancelWithPhotos, // S017b/RF-3
            onKeepPhotos = collectorViewModel.photos::keepPhotos,
        ),
        onObjects = navigation.onOpenObjects,
        onOpenPoint = navigation.onOpenPoint,
        onOpenLine = navigation.onOpenLine, // S025/RF-22
        onStakeoutLine = startProjectLine, // S027/RF-1
        onLineInformation = navigation.onLineInformation, // S026/RF-1
        lineStakeout = line.takeIf { lineOpen },
        lineHighlight = lineHighlight,
        lineStakeoutActions = LineStakeoutActions(
            onClose = {
                lineOpen = false
                lineStakeoutViewModel.close()
            },
            onMode = { lineStakeoutViewModel.setMode(it) },
            onGuideMode = { lineStakeoutViewModel.setGuideMode(it) },
            onOffset = { lineStakeoutViewModel.setOffset(it) },
            onA = { lineStakeoutViewModel.setA(it) },
            onB = { lineStakeoutViewModel.setB(it) },
            onInterval = { lineStakeoutViewModel.setInterval(it) },
            onInvert = { lineStakeoutViewModel.invert() },
            onHere = { lineStakeoutViewModel.useMyPosition() },
            onSelectVertex = { lineStakeoutViewModel.selectVertex(it) },
            onSelectPoint = { lineStakeoutViewModel.selectSeriesPoint(it) },
            onPrevious = { lineStakeoutViewModel.previous() },
            onNext = { lineStakeoutViewModel.next() },
            onHeading = { lineStakeoutViewModel.setHeading(it) },
            onTolerance = lineStakeoutViewModel::setTolerance,
            onToleranceV = lineStakeoutViewModel::setToleranceV,
            onElevation = lineStakeoutViewModel::setDesignElevation,
            onUseLineElevation = { lineStakeoutViewModel.useLineElevation() },
            onElevationSource = { lineStakeoutViewModel.setElevationSource(it) }, // S042/RF-25
        ),
        onStakeoutElement = {
            // S041/RF-1, RF-2: "Replantear" en la ficha de un elemento del dibujo.
            designSelection?.let { selection ->
                designViewModel.dismissSelection()
                axisSurveyOpen = false
                longitudinalOpen = false
                crossOpen = false
                axisOpen = false
                axisStakeoutViewModel.close()
                analysisOpen = false
                axisAnalysisViewModel.close()
                slopeOpen = false
                slopeStakeViewModel.close()
                if (selection.entity.kind == CadEntityKind.POINT || selection.entity.kind == CadEntityKind.BLOCK_INSERT) {
                    lineOpen = false
                    lineStakeoutViewModel.close()
                    stakeoutViewModel.openElement(projectId, selection)
                    stakeoutOpen = true
                } else {
                    stakeoutOpen = false
                    stakeoutViewModel.close()
                    lineStakeoutViewModel.open(projectId, selection)
                    lineOpen = true
                }
            }
        },
        stakeout = stakeout.takeIf { stakeoutOpen },
        stakeoutActions = StakeoutActions(
            onClose = {
                stakeoutOpen = false
                stakeoutViewModel.close()
            },
            onPrevious = stakeoutViewModel::previous,
            onNext = stakeoutViewModel::next,
            onMode = stakeoutViewModel::setMode,
            onHeading = stakeoutViewModel::setHeading,
            onTolerance = stakeoutViewModel::setTolerance,
            onToleranceV = stakeoutViewModel::setToleranceV,
            onElevation = stakeoutViewModel::setDesignElevation,
            onUseDesignElevation = stakeoutViewModel::useDesignPointElevation,
            onElevationSource = stakeoutViewModel::setElevationSource, // S042/RF-25
        ),
    )
}

/** Vista de proyecto: el mapa en toda la vista y el panel de posición encima, abajo (S009/RF-1). */
@Composable
fun ProjectViewScreen(
    themeState: ThemeState,
    onToggleSun: () -> Unit,
    state: ProjectViewUiState,
    onBack: () -> Unit,
    onEdit: (String) -> Unit = {},
    onInfo: (String) -> Unit = {},
    onMapSettings: (MapSettings) -> Unit = {},
    onViewportChanged: (MapViewport) -> Unit = {},
    map: MapSlot = { _, _ -> },
    collector: CollectorUiState? = null,
    collectorStats: AverageStatsUi? = null,
    collectorAutoLive: AutoLive? = null,
    /** La cota de la superficie activa bajo la punta, en el recolector sin replantear (S042/RF-33). */
    collectorSurface: SurfaceReading? = null,
    collectorActions: CollectorActions = CollectorActions(),
    onObjects: (projectId: String) -> Unit = {},
    onOpenPoint: (projectId: String, pointId: String) -> Unit = { _, _ -> },
    onOpenLine: (projectId: String, lineId: String) -> Unit = { _, _ -> },
    onStakeoutLine: (lineId: String) -> Unit = {},
    onLineInformation: (projectId: String, lineId: String) -> Unit = { _, _ -> },
    stakeout: StakeoutUiState? = null,
    stakeoutActions: StakeoutActions = StakeoutActions(),
    /** El replanteo de una línea del dibujo y el botón "Replantear" de la ficha de un elemento (S041/RF-1, RF-2). */
    lineStakeout: LineStakeoutUiState? = null,
    lineStakeoutActions: LineStakeoutActions = LineStakeoutActions(),
    lineHighlight: List<GeoPoint> = emptyList(),
    onStakeoutElement: () -> Unit = {},
    /** El replanteo por PK y desplazamiento de un eje y el botón "Replantear eje" (S054/RF-1). */
    axisStakeout: AxisStakeoutUiState? = null,
    axisStakeoutActions: AxisStakeoutActions = AxisStakeoutActions(),
    onStakeAxis: (String) -> Unit = {},
    /** El replanteo de los taludes de un eje y el botón «Replantear taludes» (S051/RF-9). */
    slopeStake: SlopeStakeUiState? = null,
    slopeStakeActions: SlopeStakeActions = SlopeStakeActions(),
    onStakeSlopes: (String) -> Unit = {},
    /** El perfil longitudinal de un eje y su botón «Perfil longitudinal» (S052/RF-10). */
    longitudinal: LongitudinalUiState? = null,
    longitudinalActions: LongitudinalActions = LongitudinalActions(),
    longitudinalWriteCsv: suspend (java.io.OutputStream) -> Boolean = { false },
    onLongitudinal: (String) -> Unit = {},
    /** El perfil transversal de un PK de un eje y su botón «Perfil transversal» (S052/RF-16). */
    cross: CrossUiState? = null,
    crossActions: CrossActions = CrossActions(),
    crossWriteCsv: suspend (java.io.OutputStream) -> Boolean = { false },
    onCross: (String) -> Unit = {},
    /** «Tomar eje» con el receptor y su panel (S052/RF-1). */
    axisSurvey: AxisSurveyUiState? = null,
    axisSurveyActions: AxisSurveyActions = AxisSurveyActions(),
    onTakeAxis: () -> Unit = {},
    /** «Analizar» la posición del jalón respecto a un eje y el botón «Analizar» (S054/RF-12). */
    axisAnalysis: AxisAnalysisUiState? = null,
    axisAnalysisActions: AxisAnalysisActions = AxisAnalysisActions(),
    onAnalyzeAxis: (String) -> Unit = {},
    /** El dibujo importado de un DXF en la vista de ahora y el aviso de que la cámara se ha parado (S040/RF-24). */
    design: DesignScene? = null,
    onDesignCamera: (MapCamera) -> Unit = {},
    /** Los dibujos importados con sus capas, para el panel «Dibujos» (S040/RF-26 a RF-30). */
    drawings: List<CadDrawingWithLayers> = emptyList(),
    drawingsActions: DrawingsActions = DrawingsActions(),
    /** Las superficies importadas de un LandXML con la activa y el desplazamiento vertical, para el panel «Superficies» (S042/RF-16 a RF-20). */
    surfaces: SurfacesUiState = SurfacesUiState(),
    surfacesActions: SurfacesActions = SurfacesActions(),
    /** El elemento del dibujo tocado en el mapa, los toques en el mapa y el cierre de su ficha (S040/RF-29). */
    designSelection: DesignSelection? = null,
    onMapTap: (MapTap) -> Unit = {},
    onDismissDesignSelection: () -> Unit = {},
    /** Convertir la línea tocada en un eje, y cómo va (S049/RF-6, RF-7). */
    /** Los ejes del proyecto con el activo, para el panel «Ejes» (S049/RF-14, RF-15, RF-19). */
    alignments: AlignmentsUiState = AlignmentsUiState(),
    /** Los ejes visibles con sus marcas de PK en la vista de ahora (S049/RF-17). */
    alignmentScene: AlignmentScene? = null,
    /** El eje tocado en el mapa, el cierre de su ficha y activarlo (S049/RF-18). */
    alignmentTouch: AlignmentTouch? = null,
    sectionLinesOn: Set<String> = emptySet(),
    onSectionLines: (alignmentId: String, on: Boolean) -> Unit = { _, _ -> },
    onDismissAlignmentTouch: () -> Unit = {},
    onActivateAlignment: (String?) -> Unit = {},
    onCalculateOnAlignment: (String) -> Unit = {},
    onSectionsOfAxis: (String) -> Unit = {},
    onSlopesOfAxis: (String) -> Unit = {},
    onProfiles: () -> Unit = {},
    alignmentsActions: AlignmentsActions = AlignmentsActions(),
    alignmentFromDrawing: AlignmentFromDrawingUiState = AlignmentFromDrawingUiState(),
    onConvertToAlignment: (selection: DesignSelection, name: String, startChainageM: Double) -> Unit = { _, _, _ -> },
    onDismissAlignmentConversion: () -> Unit = {},
) {
    var collectorOpen by rememberSaveable { mutableStateOf(false) }
    val policy = remember { CameraPolicy() }
    val project = state.project
    var controller by remember { mutableStateOf<MapController?>(null) }
    var layersOpen by rememberSaveable { mutableStateOf(false) }
    var drawingsOpen by rememberSaveable { mutableStateOf(false) }
    var surfacesOpen by rememberSaveable { mutableStateOf(false) }
    var convertOpen by rememberSaveable { mutableStateOf(false) }
    var designInfoOpen by rememberSaveable { mutableStateOf(false) } // S026/RF-1
    var alignmentsOpen by rememberSaveable { mutableStateOf(false) }
    var bottomInsetPx by remember { mutableIntStateOf(0) }
    SectionScaffold(
        title = project?.name.orEmpty(),
        themeState = themeState,
        onToggleSun = onToggleSun,
        onNavigateBack = onBack,
        actions = {
            if (project != null) {
                IconButton(onClick = { onEdit(project.id) }, modifier = Modifier.size(MinTouchTarget).testTag(PROJECT_EDIT_TAG)) {
                    Icon(painterResource(R.drawable.ic_edit), contentDescription = stringResource(R.string.project_edit))
                } // S006/RF-14
            }
        },
    ) { padding ->
        Box(modifier = Modifier.padding(padding).fillMaxSize().testTag(PROJECT_VIEW_TAG)) {
            map(Modifier.fillMaxSize().testTag(PROJECT_MAP_TAG)) { controller = it }
            if (state.mapOffline) {
                Text(
                    stringResource(R.string.map_offline),
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(12.dp)
                        .background(MaterialTheme.colorScheme.errorContainer, MaterialTheme.shapes.small)
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                        .testTag(OFFLINE_BANNER_TAG),
                )
            } // S009/RF-15
            Column(
                modifier = Modifier.align(Alignment.TopEnd).padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                FilledTonalIconButton(
                    onClick = { layersOpen = true },
                    modifier = Modifier.size(MinTouchTarget).testTag(LAYERS_BUTTON_TAG),
                ) {
                    Icon(painterResource(R.drawable.ic_layers), contentDescription = stringResource(R.string.map_layers))
                } // S009/RF-6, RF-7
                if (drawings.isNotEmpty()) {
                    FilledTonalIconButton(
                        onClick = { drawingsOpen = true },
                        modifier = Modifier.size(MinTouchTarget).testTag(DRAWINGS_BUTTON_TAG),
                    ) {
                        Icon(painterResource(R.drawable.ic_drawings), contentDescription = stringResource(R.string.cad_drawings_open))
                    } // S040/RF-26
                }
                if (surfaces.surfaces.isNotEmpty()) {
                    FilledTonalIconButton(
                        onClick = { surfacesOpen = true },
                        modifier = Modifier.size(MinTouchTarget).testTag(SURFACES_BUTTON_TAG),
                    ) {
                        Icon(painterResource(R.drawable.ic_surface), contentDescription = stringResource(R.string.surfaces_open))
                    } // S042/RF-16
                }
                if (alignments.alignments.isNotEmpty()) {
                    FilledTonalIconButton(
                        onClick = { alignmentsOpen = true },
                        modifier = Modifier.size(MinTouchTarget).testTag(ALIGNMENTS_BUTTON_TAG),
                    ) {
                        Icon(painterResource(R.drawable.ic_alignment), contentDescription = stringResource(R.string.alignments_open))
                    } // S049/RF-14
                }
                val rover = state.rover
                FilledTonalIconButton(
                    onClick = { if (rover != null) controller?.run { execute(policy.onCenterPressed(rover.position, camera.value)) } },
                    enabled = rover != null,
                    modifier = Modifier.size(MinTouchTarget).testTag(CENTER_BUTTON_TAG),
                ) {
                    Icon(painterResource(R.drawable.ic_center_rover), contentDescription = stringResource(R.string.map_center_rover))
                } // S009/RF-19
                if (project != null) {
                    FilledTonalIconButton(
                        onClick = { onObjects(project.id) },
                        modifier = Modifier.size(MinTouchTarget).testTag(OBJECTS_BUTTON_TAG),
                    ) {
                        Icon(painterResource(R.drawable.ic_list), contentDescription = stringResource(R.string.objects_open))
                    } // S011/RF-1
                }
            }
            Column(modifier = Modifier.align(Alignment.BottomCenter).onSizeChanged { bottomInsetPx = it.height }) {
                // Barra de escala y atribución abajo a la izquierda, justo encima del panel.
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.Bottom,
                ) {
                    val camera = controller?.camera?.collectAsStateWithLifecycle()?.value
                    if (project != null && camera != null) ScaleBarView(camera, project.linearUnit)
                    Attribution(state.mapSettings)
                    if (project != null &&
                        collector != null &&
                        !collectorOpen &&
                        stakeout == null &&
                        lineStakeout == null &&
                        axisStakeout == null &&
                        slopeStake == null &&
                        axisAnalysis == null
                    ) {
                        Spacer(Modifier.weight(1f))
                        ExtendedFloatingActionButton(
                            onClick = { collectorOpen = true },
                            modifier = Modifier.heightIn(min = MinTouchTarget).testTag(COLLECT_BUTTON_TAG),
                        ) { Text(stringResource(R.string.collector_collect)) } // S010/RF-1
                    }
                }
                when {
                    project == null -> Unit
                    stakeout != null -> StakeoutPanel(
                        stakeout,
                        stakeoutActions,
                        collector = collector,
                        collectorActions = collectorActions,
                        stats = collectorStats,
                    ) // S018/RF-1: en lugar del recolector, con el mismo hueco
                    lineStakeout != null -> LineStakeoutPanel(
                        lineStakeout,
                        lineStakeoutActions,
                        collector = collector,
                        collectorActions = collectorActions,
                        stats = collectorStats,
                    ) // S041/RF-2
                    axisStakeout != null -> AxisStakeoutPanel(
                        axisStakeout,
                        axisStakeoutActions,
                        collector = collector,
                        collectorActions = collectorActions,
                        stats = collectorStats,
                    ) // S054/RF-1
                    slopeStake != null -> SlopeStakePanel(
                        slopeStake,
                        slopeStakeActions,
                        collector = collector,
                        collectorActions = collectorActions,
                        stats = collectorStats,
                    ) // S051/RF-9, RF-15
                    cross != null -> Column {
                        CrossPanel(cross, project.name, crossActions, crossWriteCsv) // S052/RF-16
                        if (axisStakeout != null) {
                            AxisStakeoutPanel(
                                axisStakeout,
                                axisStakeoutActions,
                                collector = collector,
                                collectorActions = collectorActions,
                                stats = collectorStats,
                            )
                        } else if (collector != null) {
                            CollectorPanel(
                                collector,
                                project.linearUnit,
                                collectorActions,
                                onClose = crossActions.onClose,
                                stats = collectorStats,
                                autoLive = collectorAutoLive,
                                surface = collectorSurface,
                            )
                        }
                    }
                    longitudinal != null -> Column {
                        LongitudinalPanel(longitudinal, project.name, longitudinalActions, longitudinalWriteCsv) // S052/RF-10
                        if (axisStakeout != null) {
                            AxisStakeoutPanel(
                                axisStakeout,
                                axisStakeoutActions,
                                collector = collector,
                                collectorActions = collectorActions,
                                stats = collectorStats,
                            )
                        } else if (collector != null) {
                            CollectorPanel(
                                collector,
                                project.linearUnit,
                                collectorActions,
                                onClose = longitudinalActions.onClose,
                                stats = collectorStats,
                                autoLive = collectorAutoLive,
                                surface = collectorSurface,
                            )
                        }
                    }
                    axisSurvey != null -> Column {
                        AxisSurveyPanel(axisSurvey, axisSurveyActions) // S052/RF-1
                        if (collector != null) {
                            CollectorPanel(
                                collector,
                                project.linearUnit,
                                collectorActions,
                                onClose = axisSurveyActions.onClose,
                                stats = collectorStats,
                                autoLive = collectorAutoLive,
                                surface = collectorSurface,
                            )
                        }
                    }
                    axisAnalysis != null -> AxisAnalysisPanel(
                        axisAnalysis,
                        axisAnalysisActions,
                        collector = collector,
                        collectorActions = collectorActions,
                        stats = collectorStats,
                    ) // S054/RF-12
                    collector != null && collectorOpen -> CollectorPanel(collector, project.linearUnit, collectorActions, onClose = {
                        collectorOpen =
                            false
                    }, stats = collectorStats, autoLive = collectorAutoLive, surface = collectorSurface)
                    else -> PositionPanel(project, state.position, state.accuracyM, state.uncompensated, onInfo, Modifier)
                }
            }
        }
    }
    BindMap(controller, state, stakeout, design, onDesignCamera, lineStakeout, lineHighlight, alignmentScene, axisStakeout, axisSurvey)
    LaunchedEffect(controller, bottomInsetPx) { controller?.setBottomInset(bottomInsetPx) } // S010/RF-1
    controller?.let { FollowRover(it, state, policy, onViewportChanged) }
    // S011/RF-5: tocar un punto del mapa abre su ficha.
    val openPoint by rememberUpdatedState(onOpenPoint)
    val projectId = project?.id
    LaunchedEffect(controller, projectId) {
        val map = controller ?: return@LaunchedEffect
        if (projectId != null) map.pointClicks.collect { pointId -> openPoint(projectId, pointId) }
    }
    // S025/RF-22, S027/RF-1: tocar una linea o un poligono del mapa abre una hoja con su ficha, su replanteo y su informacion.
    var tappedLine by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(controller, projectId) {
        val map = controller ?: return@LaunchedEffect
        if (projectId != null) map.lineClicks.collect { lineId -> tappedLine = lineId }
    }
    val sheetLine = tappedLine
    if (sheetLine != null && projectId != null) {
        LineMapSheet(
            name = state.lineNames[sheetLine].orEmpty(),
            onOpen = {
                tappedLine = null
                onOpenLine(projectId, sheetLine)
            },
            onStakeout = {
                tappedLine = null
                onStakeoutLine(sheetLine)
            },
            onInformation = {
                tappedLine = null
                onLineInformation(projectId, sheetLine)
            },
            onDismiss = { tappedLine = null },
        )
    }
    if (layersOpen) LayersSheet(state.mapSettings, onMapSettings) { layersOpen = false }
    if (drawingsOpen) DrawingsSheet(drawings, drawingsActions) { drawingsOpen = false }
    if (surfacesOpen) SurfacesSheet(surfaces, surfacesActions) { surfacesOpen = false }
    if (alignmentsOpen) {
        AlignmentsSheet(
            alignments,
            AlignmentsActions(
                alignmentsActions.onActivate,
                alignmentsActions.onRename,
                alignmentsActions.onVisible,
                alignmentsActions.onDelete,
                onCalculate = {
                    alignmentsOpen = false
                    onCalculateOnAlignment(it)
                },
                onStake = {
                    alignmentsOpen = false
                    onStakeAxis(it)
                },
                onAnalyze = {
                    alignmentsOpen = false
                    onAnalyzeAxis(it)
                },
                onSections = {
                    alignmentsOpen = false
                    onSectionsOfAxis(it)
                },
                onSlopes = {
                    alignmentsOpen = false
                    onSlopesOfAxis(it)
                },
                onStakeSlopes = {
                    alignmentsOpen = false
                    onStakeSlopes(it)
                },
                onTakeAxis = {
                    alignmentsOpen = false
                    onTakeAxis()
                },
                onLongitudinal = {
                    alignmentsOpen = false
                    onLongitudinal(it)
                },
                onCross = {
                    alignmentsOpen = false
                    onCross(it)
                },
                onProfiles = {
                    alignmentsOpen = false
                    onProfiles()
                },
            ),
        ) { alignmentsOpen = false }
    }
    if (alignmentTouch != null && project != null) {
        AlignmentTouchSheet(
            touch = alignmentTouch,
            unit = project.linearUnit,
            unitSymbol = unitSymbol(project.linearUnit),
            onActivate = {
                onActivateAlignment(alignmentTouch.alignment.id)
                onDismissAlignmentTouch()
            },
            onDismiss = onDismissAlignmentTouch,
            onCalculate = {
                onDismissAlignmentTouch()
                onCalculateOnAlignment(alignmentTouch.alignment.id)
            },
            onStake = {
                onDismissAlignmentTouch()
                onStakeAxis(alignmentTouch.alignment.id)
            },
            onAnalyze = {
                onDismissAlignmentTouch()
                onAnalyzeAxis(alignmentTouch.alignment.id)
            },
            onSections = {
                onDismissAlignmentTouch()
                onSectionsOfAxis(alignmentTouch.alignment.id)
            },
            onSlopes = {
                onDismissAlignmentTouch()
                onSlopesOfAxis(alignmentTouch.alignment.id)
            },
            onStakeSlopes = {
                onDismissAlignmentTouch()
                onStakeSlopes(alignmentTouch.alignment.id)
            },
            onLongitudinal = {
                onDismissAlignmentTouch()
                onLongitudinal(alignmentTouch.alignment.id)
            },
            onCross = {
                onDismissAlignmentTouch()
                onCross(alignmentTouch.alignment.id)
            },
            sectionLines = alignmentTouch.alignment.id in sectionLinesOn,
            onSectionLines = { on -> onSectionLines(alignmentTouch.alignment.id, on) },
        )
    } else if (designSelection != null && project != null) {
        DesignElementSheet(
            designSelection,
            project.linearUnit,
            unitSymbol(project.linearUnit),
            geographic = project.crs.utmZone == null,
            onDismiss = onDismissDesignSelection,
            onStakeout = onStakeoutElement,
            onConvertToAxis = { convertOpen = true },
            onInformation = { designInfoOpen = true },
        )
    }
    if (designInfoOpen && designSelection != null && project != null) {
        DesignGeometryDialog(
            DesignGeometryInput(designSelection, project.name, project.crs, project.linearUnit, project.angularUnit),
            onDismiss = { designInfoOpen = false },
        )
    }
    if (convertOpen && designSelection != null && project != null) {
        ConvertToAlignmentDialog(
            defaultName = designSelection.layerName,
            unit = project.linearUnit,
            unitSymbol = unitSymbol(project.linearUnit),
            state = alignmentFromDrawing,
            onConvert = { name, start -> onConvertToAlignment(designSelection, name, start) },
            onDismiss = {
                convertOpen = false
                onDismissAlignmentConversion()
                onDismissDesignSelection()
            },
        )
    }
    LaunchedEffect(controller) { controller?.mapTaps?.collect { onMapTap(it) } } // S040/RF-29
}

/** Cámara del mapa según [CameraPolicy], y su encuadre al ViewModel una vez colocado (S009/RF-19 a RF-21). */
@Composable
private fun FollowRover(
    controller: MapController,
    state: ProjectViewUiState,
    policy: CameraPolicy,
    onViewportChanged: (MapViewport) -> Unit,
) {
    val position = state.rover?.position
    LaunchedEffect(controller, state.viewportLoaded) {
        // Sin posición, el último encuadre o España; si ya hay rover, manda él (S009/RF-19, RF-21).
        if (state.viewportLoaded &&
            !policy.placed &&
            !policy.hasRover &&
            position == null
        ) {
            controller.execute(policy.onOpen(state.savedViewport))
        }
    }
    LaunchedEffect(controller, position) {
        if (position != null) policy.onPosition(position, controller.camera.value)?.let { controller.execute(it) }
    }
    LaunchedEffect(controller) { controller.userGestures.collect { policy.onUserGesture() } }
    LaunchedEffect(controller) {
        controller.camera.filterNotNull().filter {
            policy.placed
        }.collect { onViewportChanged(MapViewport(it.latitudeDeg, it.longitudeDeg, it.zoom)) }
    }
}

private fun MapController.execute(command: CameraCommand) = when (command) {
    is CameraCommand.MoveTo -> moveTo(command.viewport)
    CameraCommand.ShowSpain -> showSpain()
}

/** Hoja "Capas": mapa base y Catastro (S009/RF-6, RF-7). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LayersSheet(settings: MapSettings, onChange: (MapSettings) -> Unit, onDismiss: () -> Unit) {
    // Es corta: se abre entera.
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            modifier = Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ChoiceGroup(stringResource(R.string.map_basemap), Basemap.entries, settings.basemap, ::basemapOptionTag, { basemapLabel(it) }) {
                onChange(settings.copy(basemap = it))
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = MinTouchTarget)
                    .toggleable(value = settings.cadastre, role = Role.Switch, onValueChange = { onChange(settings.copy(cadastre = it)) })
                    .testTag(CADASTRE_SWITCH_TAG),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(R.string.map_cadastre), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                Switch(checked = settings.cadastre, onCheckedChange = null)
            }
        }
    }
}

/**
 * Barra de escala: la mayor longitud redonda en la unidad del proyecto que cabe en 100 dp, dibujada con su
 * ancho a escala (S009/RF-10).
 */
@Composable
private fun ScaleBarView(camera: MapCamera, unit: LinearUnit) {
    val bar = ScaleBar.choose(WebMercator.metresPerPixel(camera.latitudeDeg, camera.zoom), SCALE_BAR_MAX_DP, unit)
    val text = "${bar.valueText(uiLocale())} ${unitSymbol(unit)}"
    val color = MaterialTheme.colorScheme.onSurface
    Column(
        modifier = Modifier
            .background(MaterialTheme.colorScheme.surface.copy(alpha = OVERLAY_ALPHA), MaterialTheme.shapes.small)
            .padding(horizontal = 6.dp, vertical = 2.dp)
            .semantics(mergeDescendants = true) {}
            .testTag(SCALE_BAR_TAG),
    ) {
        Text(text, style = MaterialTheme.typography.labelMedium, color = color)
        Canvas(modifier = Modifier.width(bar.widthPx.dp).height(6.dp)) {
            val stroke = 2.dp.toPx()
            drawLine(color, Offset(0f, size.height), Offset(size.width, size.height), stroke)
            drawLine(color, Offset(0f, 0f), Offset(0f, size.height), stroke)
            drawLine(color, Offset(size.width, 0f), Offset(size.width, size.height), stroke)
        }
    }
}

/** Atribución de las fuentes a la vista, siempre visible (S009/RF-9, RNF-T14). */
@Composable
private fun Attribution(settings: MapSettings) {
    val sources = MapAttribution.sources(settings)
    if (sources.isEmpty()) return
    val text = sources.map { source ->
        stringResource(
            when (source) {
                AttributionSource.IGN -> R.string.attribution_ign
                AttributionSource.OPENFREEMAP -> R.string.attribution_openfreemap
                AttributionSource.CADASTRE -> R.string.attribution_cadastre
            },
        )
    }.joinToString(" · ")
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier
            .background(MaterialTheme.colorScheme.surface.copy(alpha = OVERLAY_ALPHA), MaterialTheme.shapes.small)
            .padding(horizontal = 6.dp, vertical = 2.dp)
            .testTag(ATTRIBUTION_TAG),
    )
}

@Composable
private fun basemapLabel(basemap: Basemap): String = stringResource(
    when (basemap) {
        Basemap.IGN_VECTOR -> R.string.map_basemap_ign
        Basemap.OSM_VECTOR -> R.string.map_basemap_osm
        Basemap.PNOA_ORTHOPHOTO -> R.string.map_basemap_pnoa
        Basemap.NONE -> R.string.map_basemap_none
    },
)

/** Lleva al mapa sus ajustes, la unidad del proyecto y el rover con el color de su solución (S009/RF-6, RF-10, RF-16). */
@OptIn(FlowPreview::class)
@Composable
private fun BindMap(
    controller: MapController?,
    state: ProjectViewUiState,
    stakeout: StakeoutUiState?,
    design: DesignScene?,
    onDesignCamera: (MapCamera) -> Unit,
    lineStakeout: LineStakeoutUiState?,
    lineHighlight: List<GeoPoint>,
    alignmentScene: AlignmentScene?,
    axisStakeout: AxisStakeoutUiState?,
    axisSurvey: AxisSurveyUiState?,
) {
    val unit = state.project?.linearUnit
    val palette = LocalStatusPalette.current
    val marker = state.rover?.let { RoverMarker(it.position, it.accuracyM, palette.colorsFor(it.solution.tone()).container.toArgb()) }
    LaunchedEffect(controller, marker) { controller?.showRover(marker) }
    LaunchedEffect(controller, state.points, state.codeStyles) {
        controller?.showPoints(MapPoints.from(state.points, state.codeStyles))
    } // S010/RF-20
    LaunchedEffect(controller, state.lineScene) { controller?.showLines(state.lineScene) } // S025/RF-22
    LaunchedEffect(controller, design) { controller?.showDesign(design) } // S040/RF-24
    LaunchedEffect(controller, alignmentScene) { controller?.showAlignments(alignmentScene) } // S049/RF-17
    LaunchedEffect(controller) {
        // S040/RF-24: el dibujo se vuelve a cargar cuando la cámara lleva 150 ms quieta.
        controller?.camera?.filterNotNull()?.debounce(DESIGN_DEBOUNCE_MS)?.collect(onDesignCamera)
    }
    LaunchedEffect(controller, state.base) { controller?.showBase(state.base) } // S015/RF-19
    val centralLabel = stringResource(R.string.central_point_map_label)
    val central = state.project?.centralPoint
    LaunchedEffect(controller, central, centralLabel) {
        controller?.showCentralPoint(central?.let { GeoPoint(it.latitudeDeg, it.longitudeDeg) }, centralLabel) // S006/RF-36
    }
    // S041/RF-5, S054/RF-1: la línea, el eje o el punto que se replantea
    val stakeoutMarker = LineStakeoutMarkers.of(lineStakeout, lineHighlight)
        ?: AxisStakeoutMarkers.of(axisStakeout)
        ?: AxisSurveyMarkers.of(axisSurvey) // S052/RF-3
        ?: StakeoutMarkers.of(stakeout)
    LaunchedEffect(controller, stakeoutMarker) { controller?.showStakeout(stakeoutMarker) } // S018/RF-14
    LaunchedEffect(controller, state.mapSettings) { controller?.setSettings(state.mapSettings) }
    LaunchedEffect(controller, unit) { if (unit != null) controller?.setLinearUnit(unit) }
}

/**
 * Panel de posición: coordenadas y altura del punto del suelo con sus avisos y la precisión horizontal; al
 * desplegarlo, también los factores (S006/RF-29, RF-30; S008/RF-8; S009/RF-2 a RF-4).
 */
@Composable
private fun PositionPanel(
    project: Project,
    position: ProjectPosition?,
    accuracyM: Double?,
    uncompensated: Boolean,
    onInfo: (String) -> Unit,
    modifier: Modifier,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    Card(modifier = modifier.fillMaxWidth().padding(8.dp).testTag(POSITION_PANEL_TAG)) {
        Column(
            modifier = Modifier.heightIn(
                max = PANEL_MAX_HEIGHT,
            ).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            // "Datos del proyecto" va aquí y no en la barra superior, que con el indicador de estado no tiene sitio (S009/RF-5).
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.project_position_title),
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    onClick = { onInfo(project.id) },
                    modifier = Modifier.heightIn(min = MinTouchTarget).testTag(PROJECT_INFO_BUTTON_TAG),
                ) { Text(stringResource(R.string.project_info_screen)) }
            }
            when {
                position == null -> Text(stringResource(R.string.project_no_position))
                PositionUnavailable.OUTSIDE_CRS_AREA in position.unavailable ->
                    Text(stringResource(R.string.project_outside_crs), color = MaterialTheme.colorScheme.error)
                PositionUnavailable.OUTSIDE_ED50_GRID in position.unavailable ->
                    Text(stringResource(R.string.project_outside_ed50), color = MaterialTheme.colorScheme.error)
                else -> {
                    if (uncompensated) {
                        Text(
                            stringResource(R.string.tilt_tip_uncompensated),
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.testTag(POSITION_UNCOMPENSATED_TAG),
                        ) // S016/RF-17
                    }
                    PositionValues(project, position, accuracyM, showFactors = expanded)
                    // S008/RF-16: en geográficas no hay factores, así que no hay nada que desplegar.
                    if (project.crs.isUtm) {
                        TextButton(onClick = { expanded = !expanded }, modifier = Modifier.heightIn(min = MinTouchTarget)) {
                            Text(stringResource(if (expanded) R.string.map_panel_less else R.string.map_panel_more))
                        }
                    }
                }
            }
        }
    }
}

/**
 * Este y Norte en el huso del proyecto, con aviso si el punto cae en otro (S008/RF-12), o latitud y longitud
 * (S008/RF-6); la altura del proyecto (S008/RF-5); la precisión horizontal (S009/RF-3); y, con [showFactors]
 * en UTM, los factores y la convergencia (S008/RF-13 a RF-16, S009/RF-4).
 */
@Composable
private fun PositionValues(project: Project, position: ProjectPosition, accuracyM: Double?, showFactors: Boolean) {
    val unit = unitSymbol(project.linearUnit)
    val locale = uiLocale()
    fun length(valueM: Double) = "${LengthFormat.inUnit(valueM, project.linearUnit, locale)} $unit"
    val zone = project.crs.utmZone
    val positionZone = position.positionZone
    if (zone != null && positionZone != null && positionZone != zone) {
        Text(
            stringResource(R.string.project_other_zone, positionZone, zone),
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.testTag(OTHER_ZONE_TAG),
        )
    }
    position.easting?.let { CoordinateRow(stringResource(R.string.coord_easting), length(it), POSITION_EASTING_TAG) }
    position.northing?.let { CoordinateRow(stringResource(R.string.coord_northing), length(it), POSITION_NORTHING_TAG) }
    position.latitudeDeg?.let {
        CoordinateRow(
            stringResource(R.string.coord_latitude_symbol),
            AngleFormat.latitude(it, project.angularUnit, locale),
            POSITION_LATITUDE_TAG,
        )
    }
    position.longitudeDeg?.let {
        CoordinateRow(
            stringResource(R.string.coord_longitude_symbol),
            AngleFormat.longitude(it, project.angularUnit, locale),
            POSITION_LONGITUDE_TAG,
        )
    }
    CoordinateRow(
        heightTitle(project.heightSystem),
        position.height?.let(::length) ?: heightUnavailable(project, position),
        POSITION_HEIGHT_TAG,
    )
    accuracyM?.let { CoordinateRow(stringResource(R.string.map_accuracy_h), length(it), ACCURACY_TAG) }
    if (zone == null || !showFactors) return
    position.scaleFactor?.let {
        CoordinateRow(stringResource(R.string.project_scale_factor), FactorFormat.factor(it, locale), SCALE_FACTOR_TAG)
    }
    position.convergenceRad?.let {
        CoordinateRow(
            stringResource(R.string.project_convergence),
            AngleFormat.convergence(it, project.angularUnit, locale),
            CONVERGENCE_TAG,
        )
    }
    position.combinedFactor?.let {
        CoordinateRow(stringResource(R.string.project_combined_factor), FactorFormat.factor(it, locale), COMBINED_FACTOR_TAG)
    }
}

@Composable
internal fun heightTitle(height: HeightSystem): String = stringResource(
    when (height) {
        HeightSystem.ORTHOMETRIC -> R.string.coord_orthometric_height
        HeightSystem.ELLIPSOIDAL -> R.string.coord_ellipsoidal_height
    },
)

/** Por qué falta la altura: sin altura en la GGA, o fuera del geoide del ámbito del SC (S006/RF-30, S008/RF-4). */
@Composable
private fun heightUnavailable(project: Project, position: ProjectPosition): String = stringResource(
    when {
        PositionUnavailable.OUTSIDE_GEOID !in position.unavailable -> R.string.unavailable_no_height
        project.crs.area == CrsArea.CANARIAS -> R.string.unavailable_outside_geoid_canarias
        else -> R.string.unavailable_outside_geoid
    },
)

/** Etiqueta a la izquierda y valor a la derecha, en tipografía monoespaciada (RNF-T8); TalkBack la lee entera. */
@Composable
internal fun CoordinateRow(label: String, value: String, tag: String) {
    Row(
        modifier = Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}.testTag(tag),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(end = 8.dp))
        Text(value, style = MaterialTheme.typography.titleMedium.copy(fontFamily = FontFamily.Monospace), textAlign = TextAlign.End)
    }
}

@Composable
internal fun unitSymbol(unit: LinearUnit): String = stringResource(
    when (unit) {
        LinearUnit.METRE -> R.string.unit_symbol_metre
        LinearUnit.INTERNATIONAL_FOOT -> R.string.unit_symbol_foot
        LinearUnit.US_SURVEY_FOOT -> R.string.unit_symbol_us_survey_foot
    },
)

/** El panel no tapa más de esto del mapa; si no cabe, se desplaza. */
private val PANEL_MAX_HEIGHT = 320.dp

/** Fondo de la barra de escala y la atribución: legibles sobre cualquier mapa base (RNF-T8). */
private const val OVERLAY_ALPHA = 0.85f
