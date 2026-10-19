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
import es.topophone.app.ui.SectionScaffold
import es.topophone.app.ui.collector.COLLECT_BUTTON_TAG
import es.topophone.app.ui.collector.CollectorActions
import es.topophone.app.ui.collector.CollectorPanel
import es.topophone.app.ui.collector.CollectorUiState
import es.topophone.app.ui.collector.CollectorViewModel
import es.topophone.app.ui.map.AttributionSource
import es.topophone.app.ui.map.CameraCommand
import es.topophone.app.ui.map.CameraPolicy
import es.topophone.app.ui.map.MapAttribution
import es.topophone.app.ui.map.MapCamera
import es.topophone.app.ui.map.MapController
import es.topophone.app.ui.map.MapEntryPoint
import es.topophone.app.ui.map.MapLibreMap
import es.topophone.app.ui.map.MapPoints
import es.topophone.app.ui.map.NetworkFailureMonitor
import es.topophone.app.ui.map.RoverMarker
import es.topophone.app.ui.map.SCALE_BAR_MAX_DP
import es.topophone.app.ui.points.OBJECTS_BUTTON_TAG
import es.topophone.app.ui.receiver.ReceiverViewModel
import es.topophone.app.ui.receiver.tone
import es.topophone.app.ui.receiver.uiLocale
import es.topophone.core.designsystem.component.MinTouchTarget
import es.topophone.core.designsystem.theme.LocalStatusPalette
import es.topophone.core.geodesy.GeoPoint
import es.topophone.core.geodesy.WebMercator
import es.topophone.core.gnss.nmea.GgaFix
import es.topophone.core.gnss.nmea.SolutionStatus
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
import es.topophone.core.survey.PositionUnavailable
import es.topophone.core.survey.ProjectPosition
import es.topophone.core.survey.ProjectPositionCalculator
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
    /** La base tal como la da su fuente, mientras la haya (S015/RF-19). */
    val base: GeoPoint? = null,
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
) : ViewModel() {
    private val projectId = MutableStateFlow<String?>(null)

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
            snapshot.gga?.takeIf { snapshot.connection is ConnectionState.Receiving }?.let { ReceiverFix(it, snapshot.gst?.horizontalM) }
        }
        .distinctUntilChanged()

    /** S015/RF-19: la base de la sesión mientras la haya. */
    private val bases = (if (samplePeriodMs > 0) session.state.sample(samplePeriodMs) else session.state)
        .map { snapshot -> snapshot.base.position?.let { GeoPoint(it.latitudeDeg, it.longitudeDeg) } }
        .distinctUntilChanged()

    val state: StateFlow<ProjectViewUiState> = combine(
        project,
        fixes,
        preferences.poleHeightM,
        mapPreferences.settings,
        savedViewport,
    ) { project, fix, pole, map, viewport ->
        // Con el SC del proyecto: si se cambia, la posición se recalcula en el nuevo (S006/RF-15).
        val position = fix?.let { project?.let { p -> calculator.get().compute(it.gga, pole, p.crs, p.heightSystem) } }
        // S009/RF-16: el rover en su posición real, sea cual sea el SC del proyecto.
        val rover = fix?.gga?.takeIf { it.hasPosition }?.let { Rover(GeoPoint(it.latitudeDeg, it.longitudeDeg), it.status, fix.accuracyM) }
        ProjectViewUiState(
            project,
            position,
            fix?.accuracyM,
            map,
            rover,
            savedViewport = viewport?.viewport,
            viewportLoaded =
            viewport != null,
        )
    }.combine(networkMonitor.offline) { state, offline -> state.copy(mapOffline = offline) }
        .combine(projectId.flatMapLatest { id -> if (id == null) flowOf(emptyList()) else points.summaries(id) }) { state, list ->
            state.copy(points = list)
        }
        .combine(bases) { state, base -> state.copy(base = base) }
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

    private data class ReceiverFix(val gga: GgaFix, val accuracyM: Double?)

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
        const val VIEWPORT_SAVE_MS = 2_000L
    }
}

@Composable
fun ProjectViewRoute(
    projectId: String,
    themeState: ThemeState,
    onToggleSun: () -> Unit,
    navigation: SurveyNavigation,
    viewModel: ProjectViewModel = hiltViewModel(),
    collectorViewModel: CollectorViewModel = hiltViewModel(),
) {
    LaunchedEffect(projectId) {
        viewModel.start(projectId)
        collectorViewModel.start(projectId)
    }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val collector by collectorViewModel.state.collectAsStateWithLifecycle()
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
        collector = collector,
        collectorActions = CollectorActions(
            onName = collectorViewModel::setName,
            onDescription = collectorViewModel::setDescription,
            onPoleHeight = collectorViewModel::setPoleHeight,
            onMode = { mode -> collectorViewModel.setSettings(collector.settings.copy(mode = mode)) },
            onSeconds = collectorViewModel::setAveragingSeconds,
            onSettings = collectorViewModel::setSettings,
            onMeasure = collectorViewModel::measure,
            onCancel = collectorViewModel::cancel,
            onConfirm = collectorViewModel::confirmOutOfTolerance,
            onDiscard = collectorViewModel::discardPending,
            onDismissBaseChange = collectorViewModel::dismissBaseChange,
            onDismissLongBaseline = collectorViewModel::dismissLongBaseline,
        ),
        onObjects = navigation.onOpenObjects,
        onOpenPoint = navigation.onOpenPoint,
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
    collectorActions: CollectorActions = CollectorActions(),
    onObjects: (projectId: String) -> Unit = {},
    onOpenPoint: (projectId: String, pointId: String) -> Unit = { _, _ -> },
) {
    var collectorOpen by rememberSaveable { mutableStateOf(false) }
    val policy = remember { CameraPolicy() }
    val project = state.project
    var controller by remember { mutableStateOf<MapController?>(null) }
    var layersOpen by rememberSaveable { mutableStateOf(false) }
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
                    if (project != null && collector != null && !collectorOpen) {
                        Spacer(Modifier.weight(1f))
                        ExtendedFloatingActionButton(
                            onClick = { collectorOpen = true },
                            modifier = Modifier.heightIn(min = MinTouchTarget).testTag(COLLECT_BUTTON_TAG),
                        ) { Text(stringResource(R.string.collector_collect)) } // S010/RF-1
                    }
                }
                when {
                    project == null -> Unit
                    collector != null && collectorOpen -> CollectorPanel(collector, project.linearUnit, collectorActions, onClose = {
                        collectorOpen =
                            false
                    })
                    else -> PositionPanel(project, state.position, state.accuracyM, onInfo, Modifier)
                }
            }
        }
    }
    BindMap(controller, state)
    LaunchedEffect(controller, bottomInsetPx) { controller?.setBottomInset(bottomInsetPx) } // S010/RF-1
    controller?.let { FollowRover(it, state, policy, onViewportChanged) }
    // S011/RF-5: tocar un punto del mapa abre su ficha.
    val openPoint by rememberUpdatedState(onOpenPoint)
    val projectId = project?.id
    LaunchedEffect(controller, projectId) {
        val map = controller ?: return@LaunchedEffect
        if (projectId != null) map.pointClicks.collect { pointId -> openPoint(projectId, pointId) }
    }
    if (layersOpen) LayersSheet(state.mapSettings, onMapSettings) { layersOpen = false }
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
@Composable
private fun BindMap(controller: MapController?, state: ProjectViewUiState) {
    val unit = state.project?.linearUnit
    val palette = LocalStatusPalette.current
    val marker = state.rover?.let { RoverMarker(it.position, it.accuracyM, palette.colorsFor(it.solution.tone()).container.toArgb()) }
    LaunchedEffect(controller, marker) { controller?.showRover(marker) }
    LaunchedEffect(controller, state.points) { controller?.showPoints(MapPoints.from(state.points)) } // S010/RF-20
    LaunchedEffect(controller, state.base) { controller?.showBase(state.base) } // S015/RF-19
    LaunchedEffect(controller, state.mapSettings) { controller?.setSettings(state.mapSettings) }
    LaunchedEffect(controller, unit) { if (unit != null) controller?.setLinearUnit(unit) }
}

/**
 * Panel de posición: coordenadas y altura del punto del suelo con sus avisos y la precisión horizontal; al
 * desplegarlo, también los factores (S006/RF-29, RF-30; S008/RF-8; S009/RF-2 a RF-4).
 */
@Composable
private fun PositionPanel(project: Project, position: ProjectPosition?, accuracyM: Double?, onInfo: (String) -> Unit, modifier: Modifier) {
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
