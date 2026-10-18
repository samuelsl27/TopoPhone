package es.topophone.app.ui.points

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.Lazy
import dagger.hilt.android.lifecycle.HiltViewModel
import es.topophone.app.R
import es.topophone.app.ui.SectionScaffold
import es.topophone.app.ui.projects.ConfirmDialog
import es.topophone.app.ui.projects.SurveyNavigation
import es.topophone.core.designsystem.component.MinTouchTarget
import es.topophone.core.model.point.PointChange
import es.topophone.core.model.point.PointOrigin
import es.topophone.core.model.point.PointSearch
import es.topophone.core.model.point.PointSummary
import es.topophone.core.model.preferences.ThemeState
import es.topophone.core.survey.PointCorrections
import es.topophone.core.survey.PoleHeight
import es.topophone.core.survey.ProjectPositionCalculator
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
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

const val OBJECTS_BUTTON_TAG = "objects_button"
const val OBJECT_LIST_TAG = "object_list"
const val OBJECT_SEARCH_TAG = "object_search"
const val SELECTION_BAR_TAG = "selection_bar"
const val SELECTION_HIDE_TAG = "selection_hide"
const val SELECTION_SHOW_TAG = "selection_show"
const val SELECTION_DELETE_TAG = "selection_delete"
const val SELECTION_CANCEL_TAG = "selection_cancel"
const val SELECTION_POLE_TAG = "selection_pole"
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
)

/** Lo que pasó al corregir el jalón de una selección (S011/RF-14, RF-15). */
data class PoleOutcome(val corrected: Int, val manualSkipped: Int, val keptEdited: Boolean)

/** Acciones de la lista de objetos. */
class ObjectListActions(
    val onQueryChange: (String) -> Unit = {},
    val onOpen: (String) -> Unit = {},
    val onToggleSelection: (String) -> Unit = {},
    val onClearSelection: () -> Unit = {},
    val onSetHidden: (id: String, hidden: Boolean) -> Unit = { _, _ -> },
    val onHideSelected: () -> Unit = {},
    val onShowSelected: () -> Unit = {},
    val onDeleteSelected: () -> Unit = {},
    val onCorrectPole: (poleHeightM: Double) -> Unit = {},
    val onDismissPoleOutcome: () -> Unit = {},
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
    private val calculator: Lazy<ProjectPositionCalculator>,
) : ViewModel() {
    private val projectId = MutableStateFlow<String?>(null)
    private val query = MutableStateFlow("")
    private val selected = MutableStateFlow<Set<String>>(emptySet())
    private val poleOutcome = MutableStateFlow<PoleOutcome?>(null)

    private val summaries = projectId.flatMapLatest { id -> if (id == null) flowOf(null) else points.summaries(id) }

    val state: StateFlow<ObjectListUiState> = combine(summaries, query, selected, poleOutcome) { all, text, chosen, outcome ->
        if (all == null) {
            ObjectListUiState()
        } else {
            val visible = if (text.isBlank()) all else all.filter { PointSearch.matches(it, text) } // S011/RF-2
            ObjectListUiState(loaded = true, query = text, points = visible, total = all.size, selected = chosen, poleOutcome = outcome)
        }
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), ObjectListUiState())

    fun start(id: String) {
        projectId.value = id
    }

    fun search(text: String) {
        query.value = text
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
    val state by viewModel.state.collectAsStateWithLifecycle()
    ObjectListScreen(
        themeState,
        onToggleSun,
        state,
        ObjectListActions(
            onQueryChange = viewModel::search,
            onToggleSelection = viewModel::toggleSelection,
            onClearSelection = viewModel::clearSelection,
            onSetHidden = viewModel::setHidden,
            onHideSelected = { viewModel.setSelectedHidden(true) },
            onShowSelected = { viewModel.setSelectedHidden(false) },
            onDeleteSelected = viewModel::deleteSelected,
            onCorrectPole = viewModel::correctSelectedPole,
            onDismissPoleOutcome = viewModel::dismissPoleOutcome,
        ),
        onBack = navigation.onBack,
    )
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
            if (state.selected.isNotEmpty()) SelectionBar(state.selected.size, actions)
        }
    }
}

/** Un punto: nombre, origen y descripción, sus marcas con icono y texto (S011/RF-3) y el ojo del mapa (S011/RF-4). */
@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
private fun PointRow(point: PointSummary, selected: Boolean, onClick: () -> Unit, onLongClick: () -> Unit, onToggleHidden: () -> Unit) {
    val origin = stringResource(if (point.origin == PointOrigin.MEASURED) R.string.point_origin_measured else R.string.point_origin_manual)
    ListItem(
        headlineContent = { Text(point.name, style = MaterialTheme.typography.titleMedium) },
        supportingContent = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(if (point.description.isEmpty()) origin else "$origin · ${point.description}")
                val marks = buildList {
                    if (point.outOfTolerance) add(R.drawable.ic_warning to R.string.point_mark_out_of_tolerance)
                    if (point.edited) add(R.drawable.ic_edit to R.string.point_mark_edited)
                    if (!point.hasHeight) add(R.drawable.ic_no_height to R.string.point_mark_no_height)
                }
                if (marks.isNotEmpty()) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) { marks.forEach { (icon, text) -> Mark(icon, text) } }
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

@Composable
private fun Mark(@DrawableRes icon: Int, @StringRes text: Int) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Icon(painterResource(icon), contentDescription = null, modifier = Modifier.size(16.dp))
        Text(stringResource(text), style = MaterialTheme.typography.labelMedium)
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

/** La altura del jalón para la selección, en metros como en Coordenadas (S011/RF-14; S002/RF-29). */
@Composable
private fun PoleDialog(onApply: (Double) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf("") }
    val value = PoleHeight.parse(text)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.objects_pole)) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text(stringResource(R.string.pole_height_label)) },
                isError = text.isNotEmpty() && value == null,
                supportingText = if (text.isNotEmpty() && value == null) ({ Text(stringResource(R.string.pole_height_invalid)) }) else null,
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth().testTag(POLE_FIELD_TAG),
            )
        },
        confirmButton = {
            TextButton(
                onClick = { value?.let(onApply) },
                enabled = value != null,
                modifier = Modifier.heightIn(min = MinTouchTarget).testTag(POLE_APPLY_TAG),
            ) { Text(stringResource(R.string.objects_pole_apply)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = MinTouchTarget)) {
                Text(stringResource(R.string.dialog_cancel))
            }
        },
    )
}

/** Barra de la selección múltiple, abajo y a mano con el pulgar (S011/RF-4, RF-14, RF-22). */
@Composable
private fun SelectionBar(count: Int, actions: ObjectListActions) {
    var confirmDelete by remember { mutableStateOf(false) }
    var editPole by remember { mutableStateOf(false) }
    Surface(tonalElevation = 3.dp, modifier = Modifier.fillMaxWidth().testTag(SELECTION_BAR_TAG)) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            Text(pluralStringResource(R.plurals.objects_selected, count, count), style = MaterialTheme.typography.titleMedium)
            FlowRowButtons(
                listOf(
                    Triple(R.string.objects_hide, SELECTION_HIDE_TAG, actions.onHideSelected),
                    Triple(R.string.objects_show, SELECTION_SHOW_TAG, actions.onShowSelected),
                    Triple(R.string.objects_pole, SELECTION_POLE_TAG) { editPole = true },
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
