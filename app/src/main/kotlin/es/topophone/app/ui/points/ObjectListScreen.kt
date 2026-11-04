package es.topophone.app.ui.points

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.Lazy
import dagger.hilt.android.lifecycle.HiltViewModel
import es.topophone.app.R
import es.topophone.app.ui.SectionScaffold
import es.topophone.app.ui.alignment.rememberImportAlignmentLauncher
import es.topophone.app.ui.cad.rememberImportDxfLauncher
import es.topophone.app.ui.collector.CollectorCodeSource
import es.topophone.app.ui.collector.rgbColor
import es.topophone.app.ui.export.ExportSelection
import es.topophone.app.ui.gpkg.rememberImportGpkgLauncher
import es.topophone.app.ui.projects.ConfirmDialog
import es.topophone.app.ui.projects.SurveyNavigation
import es.topophone.app.ui.raster.rememberImportRasterLauncher
import es.topophone.app.ui.surface.rememberImportSurfaceLauncher
import es.topophone.core.designsystem.component.MinTouchTarget
import es.topophone.core.model.codes.CodeKey
import es.topophone.core.model.point.PointChange
import es.topophone.core.model.point.PointCodeFilter
import es.topophone.core.model.point.PointOrigin
import es.topophone.core.model.point.PointSearch
import es.topophone.core.model.point.PointSummary
import es.topophone.core.model.preferences.ThemeState
import es.topophone.core.survey.PointCorrections
import es.topophone.core.survey.ProjectPositionCalculator
import es.topophone.data.point.CodeChangeResult
import es.topophone.data.point.PointRepository
import es.topophone.data.project.ProjectRepository
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

const val OBJECTS_BUTTON_TAG = "objects_button"
const val OBJECT_LIST_TAG = "object_list"
const val ADD_POINT_TAG = "add_point"
const val OBJECTS_MENU_TAG = "objects_menu"
const val OBJECTS_LINE_FROM_POINTS_TAG = "objects_line_from_points"
const val OBJECT_SEARCH_TAG = "object_search"
const val SELECTION_BAR_TAG = "selection_bar"
const val SELECTION_HIDE_TAG = "selection_hide"
const val SELECTION_SHOW_TAG = "selection_show"
const val SELECTION_DELETE_TAG = "selection_delete"
const val SELECTION_CANCEL_TAG = "selection_cancel"
const val SELECTION_EXPORT_TAG = "selection_export"
const val SELECTION_EXPORT_GPKG_TAG = "selection_export_gpkg"
const val SELECTION_STAKEOUT_REPORT_TAG = "selection_stakeout_report"
const val SELECTION_POLE_TAG = "selection_pole"
const val SELECTION_CODE_TAG = "selection_code"
const val OBJECT_CODE_FILTER_ALL_TAG = "object_code_filter_all"
const val OBJECT_CODE_FILTER_NONE_TAG = "object_code_filter_none"

fun objectCodeFilterTag(code: String) = "object_code_filter_$code"

fun objectCodeTag(id: String) = "object_code_$id"
const val SELECTION_STAKEOUT_TAG = "selection_stakeout"
const val POLE_FIELD_TAG = "pole_field"
const val POLE_APPLY_TAG = "pole_apply"
const val POLE_OUTCOME_DISMISS_TAG = "pole_outcome_dismiss"

fun objectRowTag(id: String) = "object_$id"

fun objectVisibilityTag(id: String) = "object_visibility_$id"

/** Lo que muestra la lista de objetos (S011/RF-1 a RF-4): [points] ya filtrados por la búsqueda; [total], todos. */
data class ObjectListUiState(
    val loaded: Boolean = false,
    val query: String = "",
    val points: List<PointSummary> = emptyList(),
    val total: Int = 0,
    val selected: Set<String> = emptySet(),
    val poleOutcome: PoleOutcome? = null,
    /** Los códigos que llevan los puntos del proyecto, si algún punto no lleva ninguno y el filtro elegido (S024/RF-18). */
    val codes: List<String> = emptyList(),
    val hasUncoded: Boolean = false,
    val codeFilter: String? = null,
)

/** Lo que pasó al corregir el jalón de una selección (S011/RF-14, RF-15). */
data class PoleOutcome(val corrected: Int, val manualSkipped: Int, val keptEdited: Boolean)

/** Acciones de la lista de objetos. */
class ObjectListActions(
    val onQueryChange: (String) -> Unit = {},
    val onOpen: (String) -> Unit = {},
    val onAdd: () -> Unit = {},
    val onOpenTrash: () -> Unit = {},
    val onExport: () -> Unit = {},
    val onExportSelected: () -> Unit = {},
    /** Exportar a GeoPackage todo el proyecto o los puntos seleccionados (S053/RF-1, RF-2). */
    val onExportGeoPackage: () -> Unit = {},
    val onExportGeoPackageSelected: () -> Unit = {},
    /** El informe de replanteo de todo el proyecto y de los puntos seleccionados (S019/RF-17, RF-18). */
    val onStakeoutReport: () -> Unit = {},
    /** Abre el selector de ficheros y, con el fichero, el asistente de importación de puntos (S028/RF-1). */
    val onImportPoints: () -> Unit = {},
    /** Abre el selector de ficheros y, con el fichero, la importación de un DXF (S040/RF-1). */
    val onImportDxf: () -> Unit = {},
    /** Abre el selector de ficheros y, con el fichero, la importación de una superficie LandXML (S042/RF-1). */
    val onImportSurface: () -> Unit = {},
    /** Abre el selector de ficheros y, con el fichero, la importación de un ráster de cotas (S045/RF-1). */
    val onImportRaster: () -> Unit = {},
    val onBaseShift: () -> Unit = {},
    /** El asistente «Línea desde puntos» (S025/RF-12). */
    val onLineFromPoints: () -> Unit = {},
    /** Abre el selector de ficheros y, con el fichero, la importación de un GeoPackage (S048/RF-1). */
    val onImportGpkg: () -> Unit = {},
    /** Pregunta de dónde sale el eje y lo importa (S049/RF-1). */
    val onImportAlignment: () -> Unit = {},
    val onStakeoutReportSelected: () -> Unit = {},
    val onToggleSelection: (String) -> Unit = {},
    val onClearSelection: () -> Unit = {},
    val onSetHidden: (id: String, hidden: Boolean) -> Unit = { _, _ -> },
    val onHideSelected: () -> Unit = {},
    val onShowSelected: () -> Unit = {},
    val onDeleteSelected: () -> Unit = {},
    val onCorrectPole: (poleHeightM: Double) -> Unit = {},
    /** «Cambiar código» de los puntos seleccionados (S024/RF-13). */
    val onChangeCode: () -> Unit = {},
    /** Filtra la lista por código: la clave de un código, [PointCodeFilter.NONE] o null para todos (S024/RF-18). */
    val onCodeFilter: (String?) -> Unit = {},
    val onDismissPoleOutcome: () -> Unit = {},
    /** "Replantear" con un solo punto elegido (S018/RF-1). */
    val onStakeout: (pointId: String) -> Unit = {},
)

/**
 * Puntos de un proyecto, la búsqueda (en segundo plano), la selección múltiple y la corrección del jalón de la
 * selección (S011/RF-1 a RF-4, RF-14, RF-15, RF-22).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ObjectListViewModel @Inject constructor(
    private val points: PointRepository,
    private val projects: ProjectRepository,
    private val exportSelection: ExportSelection,
    codeSource: CollectorCodeSource = CollectorCodeSource.NONE,
    private val calculator: Lazy<ProjectPositionCalculator>,
) : ViewModel() {
    private val projectId = MutableStateFlow<String?>(null)
    private val query = MutableStateFlow("")
    private val selected = MutableStateFlow<Set<String>>(emptySet())
    private val poleOutcome = MutableStateFlow<PoleOutcome?>(null)
    private val codeFilter = MutableStateFlow<String?>(null)

    private val summaries = projectId.flatMapLatest { id -> if (id == null) flowOf(null) else points.summaries(id) }

    private val filters = combine(query, codeFilter, ::Pair)

    val state: StateFlow<ObjectListUiState> = combine(summaries, filters, selected, poleOutcome) { all, (text, code), chosen, outcome ->
        if (all == null) {
            ObjectListUiState()
        } else {
            val searched = if (text.isBlank()) all else all.filter { PointSearch.matches(it, text) } // S011/RF-2
            val visible = if (code == null) searched else searched.filter { PointCodeFilter.matches(it, code) } // S024/RF-18
            ObjectListUiState(
                loaded = true,
                query = text,
                points = visible,
                total = all.size,
                selected = chosen,
                poleOutcome = outcome,
                codes = PointCodeFilter.codesOf(all),
                hasUncoded = all.any { it.code == null },
                codeFilter = code,
            )
        }
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), ObjectListUiState())

    fun start(id: String) {
        projectId.value = id
    }

    /** Cambiar el código de los puntos seleccionados (S024/RF-13). */
    val changeCode = ChangeCode(viewModelScope, codeSource, points)

    init {
        // Aplicado el cambio, la selección ya no hace falta.
        viewModelScope.launch {
            changeCode.ui.map { it.result }.collect { result ->
                if (result is CodeChangeResult.Changed) selected.value = emptySet()
                if (result != null) changeCode.dismissResult()
            }
        }
    }

    fun openChangeCode() {
        val project = projectId.value ?: return
        changeCode.start(project, selected.value.toList())
    }

    fun search(text: String) {
        query.value = text
    }

    /** Solo los puntos de un código, o sin código, o todos con null (S024/RF-18). */
    fun filterByCode(key: String?) {
        codeFilter.value = key
    }

    fun toggleSelection(id: String) {
        selected.value = if (id in selected.value) selected.value - id else selected.value + id
    }

    fun clearSelection() {
        selected.value = emptySet()
    }

    /** S011/RF-4. */
    fun setHidden(id: String, hidden: Boolean) {
        viewModelScope.launch { points.setHidden(listOf(id), hidden) }
    }

    /** S011/RF-4: oculta o muestra los seleccionados y quita la selección. */
    fun setSelectedHidden(hidden: Boolean) {
        val ids = selected.value.toList()
        selected.value = emptySet()
        viewModelScope.launch { points.setHidden(ids, hidden) }
    }

    /** S012/RF-2: los elegidos pasan a la exportación y la selección se quita. */
    fun exportSelected() {
        exportSelection.ids = selected.value
        selected.value = emptySet()
    }

    /** S011/RF-22: la confirmación la pide la pantalla. */
    fun deleteSelected() {
        val ids = selected.value.toList()
        selected.value = emptySet()
        viewModelScope.launch { points.moveToTrash(ids) }
    }

    /**
     * S011/RF-14, RF-15: el jalón de los puntos medidos de la selección se corrige desde su antena, todos o
     * ninguno; los manuales no cambian y se cuentan.
     */
    fun correctSelectedPole(poleHeightM: Double) {
        val ids = selected.value
        val id = projectId.value ?: return
        selected.value = emptySet()
        viewModelScope.launch {
            val project = projects.project(id) ?: return@launch
            val chosen = ids.mapNotNull { points.point(it).first() }
            val corrections = withContext(Dispatchers.Default) {
                val tool = PointCorrections(calculator.get())
                chosen.mapNotNull { tool.withPoleHeight(it, poleHeightM, project.crs, project.heightSystem) }
            }
            if (corrections.isNotEmpty()) points.updateAll(corrections.map { it.point }, PointChange.POLE)
            poleOutcome.value = PoleOutcome(corrections.size, chosen.size - corrections.size, corrections.any { it.keptEditedCoordinates })
        }
    }

    fun dismissPoleOutcome() {
        poleOutcome.value = null
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}

@Composable
fun ObjectListRoute(
    projectId: String,
    themeState: ThemeState,
    onToggleSun: () -> Unit,
    navigation: SurveyNavigation,
    viewModel: ObjectListViewModel = hiltViewModel(),
) {
    LaunchedEffect(projectId) { viewModel.start(projectId) }
    val importPoints = rememberImportPointsLauncher(navigation.onImportPoints) // S028/RF-1
    val importDxf = rememberImportDxfLauncher(navigation.onImportDxf) // S040/RF-1
    val importSurface = rememberImportSurfaceLauncher(navigation.onImportSurface) // S042/RF-1
    val importRaster = rememberImportRasterLauncher(navigation.onImportRaster) // S045/RF-1
    val importGpkg = rememberImportGpkgLauncher(navigation.onImportGpkg) // S048/RF-1
    val importAlignment = rememberImportAlignmentLauncher(navigation) // S049/RF-1
    val state by viewModel.state.collectAsStateWithLifecycle()
    ObjectListScreen(
        themeState,
        onToggleSun,
        state,
        ObjectListActions(
            onQueryChange = viewModel::search,
            onOpen = { pointId -> navigation.onOpenPoint(projectId, pointId) }, // S011/RF-5
            onAdd = { navigation.onNewPoint(projectId) }, // S011/RF-16
            onOpenTrash = { navigation.onOpenPointTrash(projectId) }, // S011/RF-23
            onExport = { navigation.onExport(projectId, false) }, // S012/RF-1
            onStakeoutReport = { navigation.onStakeoutReport(projectId, false) }, // S019/RF-17
            onImportPoints = { importPoints(projectId) },
            onImportDxf = { importDxf(projectId) },
            onImportSurface = { importSurface(projectId) },
            onImportRaster = { importRaster(projectId) },
            onBaseShift = { navigation.onBaseShift(projectId) }, // S020/RF-1
            onLineFromPoints = { navigation.onLineFromPoints(projectId) }, // S025/RF-12
            onImportGpkg = { importGpkg(projectId) },
            onImportAlignment = { importAlignment(projectId) },
            onStakeoutReportSelected = {
                viewModel.exportSelected()
                navigation.onStakeoutReport(projectId, true) // S019/RF-18
            },
            onExportSelected = {
                viewModel.exportSelected()
                navigation.onExport(projectId, true) // S012/RF-2
            },
            onExportGeoPackage = { navigation.onExportGeoPackage(projectId, false) }, // S053/RF-1
            onExportGeoPackageSelected = {
                viewModel.exportSelected()
                navigation.onExportGeoPackage(projectId, true) // S053/RF-2
            },
            onToggleSelection = viewModel::toggleSelection,
            onClearSelection = viewModel::clearSelection,
            onSetHidden = viewModel::setHidden,
            onHideSelected = { viewModel.setSelectedHidden(true) },
            onShowSelected = { viewModel.setSelectedHidden(false) },
            onDeleteSelected = viewModel::deleteSelected,
            onCorrectPole = viewModel::correctSelectedPole,
            onChangeCode = viewModel::openChangeCode, // S024/RF-13
            onCodeFilter = viewModel::filterByCode, // S024/RF-18
            onDismissPoleOutcome = viewModel::dismissPoleOutcome,
            onStakeout = { pointId -> navigation.onStakeout(projectId, pointId, state.query) }, // S018/RF-1
        ),
        onBack = navigation.onBack,
    )
    val changeCode by viewModel.changeCode.ui.collectAsStateWithLifecycle()
    ChangeCodeDialogs(
        changeCode,
        ChangeCodeActions(
            onQuery = viewModel.changeCode::setQuery,
            onChoose = viewModel.changeCode::choose,
            onConfirm = viewModel.changeCode::confirm,
            onCancel = viewModel.changeCode::cancel,
        ),
    ) // S024/RF-13, RF-15
}

/**
 * Lista de objetos del proyecto (S011/RF-1 a RF-4, RF-22): buscador, una fila por punto con sus marcas y su ojo,
 * y, con una pulsación larga, selección múltiple con una barra abajo para ocultar, mostrar o borrar.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ObjectListScreen(
    themeState: ThemeState,
    onToggleSun: () -> Unit,
    state: ObjectListUiState,
    actions: ObjectListActions,
    onBack: () -> Unit,
) {
    SectionScaffold(
        title = stringResource(R.string.objects_title),
        themeState = themeState,
        onToggleSun = onToggleSun,
        onNavigateBack = onBack,
        actions = {
            ObjectsMenu(
                actions.onOpenTrash,
                actions.onExport,
                actions.onExportGeoPackage,
                actions.onStakeoutReport,
                actions.onImportPoints,
                actions.onImportDxf,
                actions.onImportSurface,
                actions.onImportRaster,
                actions.onImportGpkg,
                actions.onImportAlignment,
                actions.onBaseShift,
                actions.onLineFromPoints,
            )
        },
        floatingActionButton = {
            if (state.selected.isEmpty()) {
                ExtendedFloatingActionButton(
                    onClick = actions.onAdd,
                    modifier = Modifier.heightIn(min = MinTouchTarget).testTag(ADD_POINT_TAG),
                ) { Text(stringResource(R.string.point_add)) } // S011/RF-16
            }
        },
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            // Lista perezosa: con miles de puntos solo se pintan los que se ven (RNF de la spec).
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth().testTag(OBJECT_LIST_TAG),
                contentPadding = PaddingValues(16.dp),
            ) {
                if (state.loaded && state.total == 0) {
                    item { Text(stringResource(R.string.objects_empty), style = MaterialTheme.typography.bodyLarge) } // S011/RF-1
                    return@LazyColumn
                }
                item {
                    OutlinedTextField(
                        value = state.query,
                        onValueChange = actions.onQueryChange,
                        label = { Text(stringResource(R.string.objects_search)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp).testTag(OBJECT_SEARCH_TAG),
                    ) // S011/RF-2
                }
                if (state.codes.isNotEmpty()) item { CodeFilter(state, actions.onCodeFilter) } // S024/RF-18
                state.poleOutcome?.let { outcome -> item { PoleOutcomeNotice(outcome, actions.onDismissPoleOutcome) } }
                if (state.points.isEmpty()) {
                    item { Text(stringResource(R.string.objects_search_empty, state.query)) }
                }
                items(state.points, key = { it.id }) { point ->
                    val selecting = state.selected.isNotEmpty()
                    PointRow(
                        point = point,
                        selected = point.id in state.selected,
                        onClick = { if (selecting) actions.onToggleSelection(point.id) else actions.onOpen(point.id) },
                        onLongClick = { actions.onToggleSelection(point.id) },
                        onToggleHidden = { actions.onSetHidden(point.id, !point.hidden) },
                    )
                    HorizontalDivider()
                }
            }
            if (state.selected.isNotEmpty()) SelectionBar(state.selected.size, state.selected.singleOrNull(), actions)
        }
    }
}

/** Menú "⋮" de la lista de objetos: la papelera de puntos (S011/RF-23). */
@Composable
private fun ObjectsMenu(
    onOpenTrash: () -> Unit,
    onExport: () -> Unit,
    onExportGeoPackage: () -> Unit,
    onStakeoutReport: () -> Unit,
    onImportPoints: () -> Unit,
    onImportDxf: () -> Unit,
    onImportSurface: () -> Unit,
    onImportRaster: () -> Unit,
    onImportGpkg: () -> Unit,
    onImportAlignment: () -> Unit,
    onBaseShift: () -> Unit,
    onLineFromPoints: () -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    IconButton(onClick = { open = true }, modifier = Modifier.size(MinTouchTarget).testTag(OBJECTS_MENU_TAG)) {
        Icon(painterResource(R.drawable.ic_more_vert), contentDescription = stringResource(R.string.projects_menu))
    }
    DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
        DropdownMenuItem(
            text = { Text(stringResource(R.string.export_csv)) },
            onClick = {
                open = false
                onExport()
            },
            modifier = Modifier.heightIn(min = MinTouchTarget),
        ) // S012/RF-1
        DropdownMenuItem(
            text = { Text(stringResource(R.string.gpkg_export_title)) },
            onClick = {
                open = false
                onExportGeoPackage()
            },
            modifier = Modifier.heightIn(min = MinTouchTarget),
        ) // S053/RF-1
        DropdownMenuItem(
            text = { Text(stringResource(R.string.import_title)) },
            onClick = {
                open = false
                onImportPoints()
            },
            modifier = Modifier.heightIn(min = MinTouchTarget),
        ) // S028/RF-1
        DropdownMenuItem(
            text = { Text(stringResource(R.string.cad_import_title)) },
            onClick = {
                open = false
                onImportDxf()
            },
            modifier = Modifier.heightIn(min = MinTouchTarget),
        ) // S040/RF-1
        DropdownMenuItem(
            text = { Text(stringResource(R.string.surface_import_title)) },
            onClick = {
                open = false
                onImportSurface()
            },
            modifier = Modifier.heightIn(min = MinTouchTarget),
        ) // S042/RF-1
        DropdownMenuItem(
            text = { Text(stringResource(R.string.raster_import_title)) },
            onClick = {
                open = false
                onImportRaster()
            },
            modifier = Modifier.heightIn(min = MinTouchTarget),
        ) // S045/RF-1
        DropdownMenuItem(
            text = { Text(stringResource(R.string.gpkg_import_title)) },
            onClick = {
                open = false
                onImportGpkg()
            },
            modifier = Modifier.heightIn(min = MinTouchTarget),
        ) // S048/RF-1
        DropdownMenuItem(
            text = { Text(stringResource(R.string.alignment_import_title)) },
            onClick = {
                open = false
                onImportAlignment()
            },
            modifier = Modifier.heightIn(min = MinTouchTarget),
        ) // S049/RF-1
        DropdownMenuItem(
            text = { Text(stringResource(R.string.line_wizard_title)) },
            onClick = {
                open = false
                onLineFromPoints()
            },
            modifier = Modifier.heightIn(min = MinTouchTarget).testTag(OBJECTS_LINE_FROM_POINTS_TAG),
        ) // S025/RF-12
        DropdownMenuItem(
            text = { Text(stringResource(R.string.base_shift_title)) },
            onClick = {
                open = false
                onBaseShift()
            },
            modifier = Modifier.heightIn(min = MinTouchTarget),
        ) // S020/RF-1
        DropdownMenuItem(
            text = { Text(stringResource(R.string.stakeout_report_title)) },
            onClick = {
                open = false
                onStakeoutReport()
            },
            modifier = Modifier.heightIn(min = MinTouchTarget),
        ) // S019/RF-17
        DropdownMenuItem(
            text = { Text(stringResource(R.string.point_trash_title)) },
            onClick = {
                open = false
                onOpenTrash()
            },
            modifier = Modifier.heightIn(min = MinTouchTarget),
        )
    }
}

/** Un punto: nombre, origen y descripción, sus marcas con icono y texto (S011/RF-3) y el ojo del mapa (S011/RF-4). */
@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
private fun PointRow(point: PointSummary, selected: Boolean, onClick: () -> Unit, onLongClick: () -> Unit, onToggleHidden: () -> Unit) {
    val origin = stringResource(point.origin.labelRes())
    ListItem(
        headlineContent = { Text(point.name, style = MaterialTheme.typography.titleMedium) },
        supportingContent = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(if (point.description.isEmpty()) origin else "$origin · ${point.description}")
                point.code?.let { code ->
                    // S024/RF-18: el código con su color y su texto, nunca solo el color.
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.testTag(objectCodeTag(point.id)),
                    ) {
                        point.codeColor?.let { Box(modifier = Modifier.size(12.dp).background(rgbColor(it), CircleShape)) }
                        Text(stringResource(R.string.objects_code_row, code), style = MaterialTheme.typography.labelLarge)
                    }
                }
                val marks = buildList {
                    if (point.outOfTolerance) add(R.drawable.ic_warning to R.string.point_mark_out_of_tolerance)
                    if (point.edited) add(R.drawable.ic_edit to R.string.point_mark_edited)
                    if (!point.hasHeight) add(R.drawable.ic_no_height to R.string.point_mark_no_height)
                }
                if (marks.isNotEmpty() || point.stakeCount > 0 || point.photoCount > 0) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        marks.forEach { (icon, text) -> Mark(icon, stringResource(text)) }
                        // S018/RF-33: los puntos de diseño con replanteos, y cuántos
                        if (point.stakeCount > 0) {
                            val count = point.stakeCount
                            Mark(R.drawable.ic_stakeout, pluralStringResource(R.plurals.objects_stake_count, count, count))
                        }
                        // S017b/RF-26: los puntos con fotos, y cuántas, con icono y texto
                        if (point.photoCount > 0) {
                            val count = point.photoCount
                            Mark(R.drawable.ic_photo, pluralStringResource(R.plurals.point_photos_count, count, count))
                        }
                    }
                }
            }
        },
        trailingContent = {
            val description = stringResource(if (point.hidden) R.string.objects_show_point else R.string.objects_hide_point, point.name)
            IconButton(onClick = onToggleHidden, modifier = Modifier.size(MinTouchTarget).testTag(objectVisibilityTag(point.id))) {
                Icon(
                    painterResource(if (point.hidden) R.drawable.ic_visibility_off else R.drawable.ic_visibility),
                    contentDescription = description,
                )
            }
        },
        colors = ListItemDefaults.colors(
            containerColor = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface,
        ),
        modifier = Modifier
            .heightIn(min = MinTouchTarget)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .testTag(objectRowTag(point.id)),
    )
}

/** Los puntos de un código, sin código o todos (S024/RF-18): un chip por cada código del proyecto. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CodeFilter(state: ObjectListUiState, onFilter: (String?) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 12.dp)) {
        FilterChip(
            selected = state.codeFilter == null,
            onClick = { onFilter(null) },
            label = { Text(stringResource(R.string.objects_code_filter_all)) },
            modifier = Modifier.heightIn(min = MinTouchTarget).testTag(OBJECT_CODE_FILTER_ALL_TAG),
        )
        state.codes.forEach { code ->
            val key = CodeKey.of(code)
            FilterChip(
                selected = state.codeFilter == key,
                onClick = { onFilter(key) },
                label = { Text(code) },
                modifier = Modifier.heightIn(min = MinTouchTarget).testTag(objectCodeFilterTag(code)),
            )
        }
        if (state.hasUncoded) {
            FilterChip(
                selected = state.codeFilter == PointCodeFilter.NONE,
                onClick = { onFilter(PointCodeFilter.NONE) },
                label = { Text(stringResource(R.string.collector_code_none)) },
                modifier = Modifier.heightIn(min = MinTouchTarget).testTag(OBJECT_CODE_FILTER_NONE_TAG),
            )
        }
    }
}

@Composable
private fun Mark(@DrawableRes icon: Int, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Icon(painterResource(icon), contentDescription = null, modifier = Modifier.size(16.dp))
        Text(text, style = MaterialTheme.typography.labelMedium)
    }
}

/** Cuántos puntos han cambiado al corregir el jalón y cuántos no (S011/RF-14, RF-15). */
@Composable
private fun PoleOutcomeNotice(outcome: PoleOutcome, onDismiss: () -> Unit) {
    Surface(tonalElevation = 2.dp, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (outcome.corrected > 0) Text(pluralStringResource(R.plurals.objects_pole_done, outcome.corrected, outcome.corrected))
            if (outcome.manualSkipped > 0) {
                Text(pluralStringResource(R.plurals.objects_pole_manual_skipped, outcome.manualSkipped, outcome.manualSkipped))
            }
            if (outcome.keptEdited) Text(stringResource(R.string.objects_pole_kept_edited))
            TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = MinTouchTarget).testTag(POLE_OUTCOME_DISMISS_TAG)) {
                Text(stringResource(R.string.objects_dismiss))
            }
        }
    }
}

/** Barra de la selección múltiple, abajo y a mano con el pulgar (S011/RF-4, RF-14, RF-22). */
@Composable
private fun SelectionBar(count: Int, single: String?, actions: ObjectListActions) {
    var confirmDelete by remember { mutableStateOf(false) }
    var editPole by remember { mutableStateOf(false) }
    Surface(tonalElevation = 3.dp, modifier = Modifier.fillMaxWidth().testTag(SELECTION_BAR_TAG)) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            Text(pluralStringResource(R.plurals.objects_selected, count, count), style = MaterialTheme.typography.titleMedium)
            FlowRowButtons(
                listOfNotNull(
                    // S018/RF-1: replantear es de un punto cada vez.
                    single?.let { id -> Triple(R.string.objects_stakeout, SELECTION_STAKEOUT_TAG) { actions.onStakeout(id) } },
                    Triple(R.string.objects_hide, SELECTION_HIDE_TAG, actions.onHideSelected),
                    Triple(R.string.objects_show, SELECTION_SHOW_TAG, actions.onShowSelected),
                    Triple(R.string.objects_pole, SELECTION_POLE_TAG) { editPole = true },
                    Triple(R.string.change_code_button, SELECTION_CODE_TAG, actions.onChangeCode), // S024/RF-13
                    Triple(R.string.export_selected, SELECTION_EXPORT_TAG, actions.onExportSelected),
                    Triple(R.string.gpkg_export_selected, SELECTION_EXPORT_GPKG_TAG, actions.onExportGeoPackageSelected), // S053/RF-2
                    Triple(R.string.objects_stakeout_report, SELECTION_STAKEOUT_REPORT_TAG, actions.onStakeoutReportSelected), // S019/RF-18
                    Triple(R.string.objects_delete, SELECTION_DELETE_TAG) { confirmDelete = true },
                    Triple(R.string.objects_cancel_selection, SELECTION_CANCEL_TAG, actions.onClearSelection),
                ),
            )
        }
    }
    if (editPole) {
        PoleDialog(
            onApply = { pole ->
                editPole = false
                actions.onCorrectPole(pole)
            },
            onDismiss = { editPole = false },
        )
    }
    if (confirmDelete) {
        ConfirmDialog(
            message = pluralStringResource(R.plurals.objects_delete_confirm, count, count),
            confirm = stringResource(R.string.objects_move_to_trash),
            onConfirm = {
                confirmDelete = false
                actions.onDeleteSelected()
            },
            onDismiss = { confirmDelete = false },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FlowRowButtons(buttons: List<Triple<Int, String, () -> Unit>>) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        buttons.forEach { (text, tag, onClick) ->
            TextButton(onClick = onClick, modifier = Modifier.heightIn(min = MinTouchTarget).testTag(tag)) { Text(stringResource(text)) }
        }
    }
}

/** El nombre del origen de un punto en la lista de objetos y en la ficha (S011/RF-3, S018/RF-32, S028/RF-31). */
@StringRes
internal fun PointOrigin.labelRes(): Int = when (this) {
    PointOrigin.MEASURED -> R.string.point_origin_measured
    PointOrigin.STAKED -> R.string.point_origin_staked
    PointOrigin.MANUAL -> R.string.point_origin_manual
    PointOrigin.IMPORTED -> R.string.point_origin_imported
    PointOrigin.ON_ALIGNMENT -> R.string.point_origin_on_alignment
}
