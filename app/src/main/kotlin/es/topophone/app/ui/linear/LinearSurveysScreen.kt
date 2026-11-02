package es.topophone.app.ui.linear

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import es.topophone.app.R
import es.topophone.app.ui.SectionScaffold
import es.topophone.app.ui.receiver.uiLocale
import es.topophone.core.designsystem.component.MinTouchTarget
import es.topophone.core.model.alignment.ChainageFormat
import es.topophone.core.model.preferences.ThemeState
import es.topophone.core.model.survey.linear.LinearSurveyKind
import java.io.OutputStream
import java.text.DateFormat
import java.util.Date
import java.util.Locale

const val LSURVEYS_LIST_TAG = "lsurveys_list"
const val LSURVEYS_EMPTY_TAG = "lsurveys_empty"
const val LSURVEYS_DETAIL_TAG = "lsurveys_detail"
const val LSURVEYS_DETAIL_CLOSE_TAG = "lsurveys_detail_close"
const val LSURVEYS_DELETE_CONFIRM_TAG = "lsurveys_delete_confirm"
const val LSURVEYS_DELETE_CANCEL_TAG = "lsurveys_delete_cancel"

fun lsurveyItemTag(id: String) = "lsurvey_item_$id"

fun lsurveyViewTag(id: String) = "lsurvey_view_$id"

fun lsurveyDeleteTag(id: String) = "lsurvey_delete_$id"

/** Lo que la lista de tomas pide a quien la usa (S052/RF-22). */
class LinearSurveysActions(
    val onView: (id: String) -> Unit = {},
    val onCloseDetail: () -> Unit = {},
    val onRequestDelete: (id: String) -> Unit = {},
    val onCancelDelete: () -> Unit = {},
    val onConfirmDelete: () -> Unit = {},
)

/**
 * La lista de tomas del proyecto (S052/RF-22): de cada una, el tipo, el eje, el PK, la fecha y el número de puntos del perfil, con «Ver»
 * (el alzado y exportar el CSV) y «Borrar» con confirmación, que no toca los puntos.
 */
@Composable
fun LinearSurveysScreen(
    themeState: ThemeState,
    onToggleSun: () -> Unit,
    state: LinearSurveysUiState,
    actions: LinearSurveysActions,
    writeCsv: suspend (OutputStream) -> Boolean,
    onBack: () -> Unit,
) {
    SectionScaffold(
        title = stringResource(R.string.lsurveys_title),
        themeState = themeState,
        onToggleSun = onToggleSun,
        onNavigateBack = onBack,
    ) { padding ->
        if (state.loading) return@SectionScaffold
        if (state.items.isEmpty()) {
            Text(
                stringResource(R.string.lsurveys_empty),
                modifier = Modifier.padding(padding).padding(16.dp).testTag(LSURVEYS_EMPTY_TAG),
            )
        } else {
            LazyColumn(
                modifier = Modifier.padding(padding).padding(horizontal = 16.dp).testTag(LSURVEYS_LIST_TAG),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(state.items, key = { it.id }) { item -> ItemCard(item, state, actions) }
            }
        }
    }
    state.detail?.let { Detail(it, state, actions, writeCsv) }
    state.toDelete?.let { item ->
        AlertDialog(
            onDismissRequest = actions.onCancelDelete,
            title = { Text(stringResource(R.string.lsurveys_delete_title)) },
            text = { Text(stringResource(R.string.lsurveys_delete_message, kindLabel(item.kind))) },
            confirmButton = {
                TextButton(
                    onClick = actions.onConfirmDelete,
                    modifier = Modifier.heightIn(min = MinTouchTarget).testTag(LSURVEYS_DELETE_CONFIRM_TAG),
                ) { Text(stringResource(R.string.lsurveys_delete)) }
            },
            dismissButton = {
                TextButton(
                    onClick = actions.onCancelDelete,
                    modifier = Modifier.heightIn(min = MinTouchTarget).testTag(LSURVEYS_DELETE_CANCEL_TAG),
                ) { Text(stringResource(R.string.axis_survey_cancel)) }
            },
        )
    }
}

@Composable
private fun ItemCard(item: LinearSurveyItem, state: LinearSurveysUiState, actions: LinearSurveysActions) {
    val locale = uiLocale()
    Card(modifier = Modifier.fillMaxWidth().testTag(lsurveyItemTag(item.id))) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(kindLabel(item.kind), style = MaterialTheme.typography.titleMedium)
            Text(item.alignmentName ?: stringResource(R.string.lsurveys_axis_deleted))
            where(item, state, locale)?.let { Text(it) }
            Text(dateText(item.createdAtMs, locale))
            Text(stringResource(R.string.lsurveys_points, item.points))
            if (item.open) Text(stringResource(R.string.lsurveys_unfinished), color = MaterialTheme.colorScheme.error)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (item.kind != LinearSurveyKind.AXIS) {
                    OutlinedButton(
                        onClick = { actions.onView(item.id) },
                        modifier = Modifier.weight(1f).heightIn(min = MinTouchTarget).testTag(lsurveyViewTag(item.id)),
                    ) { Text(stringResource(R.string.lsurveys_view)) }
                }
                OutlinedButton(
                    onClick = { actions.onRequestDelete(item.id) },
                    modifier = Modifier.weight(1f).heightIn(min = MinTouchTarget).testTag(lsurveyDeleteTag(item.id)),
                ) { Text(stringResource(R.string.lsurveys_delete)) }
            }
        }
    }
}

/** El PK de la sección o el rango del guiado; nada en un eje tomado ni en un perfil libre sin rango (RF-22). */
@Composable
private fun where(item: LinearSurveyItem, state: LinearSurveysUiState, locale: Locale): String? {
    val from = item.chainageM ?: return null
    val start = ChainageFormat.format(from, state.unit, locale)
    val to = item.chainageToM ?: return stringResource(R.string.lsurveys_chainage, start)
    return stringResource(R.string.lsurveys_range, start, ChainageFormat.format(to, state.unit, locale))
}

@Composable
private fun kindLabel(kind: LinearSurveyKind): String = stringResource(
    when (kind) {
        LinearSurveyKind.AXIS -> R.string.lsurveys_kind_axis
        LinearSurveyKind.LONGITUDINAL -> R.string.long_open
        LinearSurveyKind.CROSS -> R.string.cross_open
    },
)

private fun dateText(ms: Long, locale: Locale): String =
    DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT, locale).format(Date(ms))

/** Una toma abierta para verla: el alzado de lo guardado y exportar su CSV (RF-22). */
@Composable
private fun Detail(
    detail: LinearSurveyDetail,
    state: LinearSurveysUiState,
    actions: LinearSurveysActions,
    writeCsv: suspend (OutputStream) -> Boolean,
) {
    AlertDialog(
        onDismissRequest = actions.onCloseDetail,
        title = { Text(kindLabel(detail.item.kind)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()).testTag(LSURVEYS_DETAIL_TAG),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ProfileView(detail.chart, state.unit)
                ProfileExportButtons(
                    state.projectName,
                    stringResource(if (detail.item.kind == LinearSurveyKind.CROSS) R.string.cross_file_label else R.string.long_file_label),
                    enabled = detail.rows.any { it.inProfile },
                    write = writeCsv,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = actions.onCloseDetail,
                modifier = Modifier.heightIn(min = MinTouchTarget).testTag(LSURVEYS_DETAIL_CLOSE_TAG),
            ) { Text(stringResource(R.string.collector_close)) }
        },
    )
}

/** La pantalla de las tomas del proyecto [projectId] (S052/RF-22). */
@Composable
fun LinearSurveysRoute(
    projectId: String,
    themeState: ThemeState,
    onToggleSun: () -> Unit,
    onBack: () -> Unit,
    viewModel: LinearSurveysViewModel = hiltViewModel(),
) {
    LaunchedEffect(projectId) { viewModel.start(projectId) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    LinearSurveysScreen(
        themeState,
        onToggleSun,
        state,
        LinearSurveysActions(
            onView = viewModel::view,
            onCloseDetail = viewModel::closeDetail,
            onRequestDelete = viewModel::requestDelete,
            onCancelDelete = viewModel::cancelDelete,
            onConfirmDelete = viewModel::confirmDelete,
        ),
        viewModel::writeCsv,
        onBack,
    )
}
