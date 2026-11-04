package es.topophone.app.ui.lines

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import es.topophone.app.R
import es.topophone.app.ui.SectionScaffold
import es.topophone.app.ui.collector.AttributeFields
import es.topophone.app.ui.collector.CodeSelectorRow
import es.topophone.app.ui.collector.CollectorActions
import es.topophone.core.designsystem.component.MinTouchTarget
import es.topophone.core.model.codes.SurveyCode
import es.topophone.core.model.preferences.ThemeState

const val LINE_WIZARD_SEARCH_TAG = "line_wizard_search"
const val LINE_WIZARD_SORT_TAG = "line_wizard_sort"
const val LINE_WIZARD_REVERSE_TAG = "line_wizard_reverse"
const val LINE_WIZARD_CLOSED_TAG = "line_wizard_closed"
const val LINE_WIZARD_NAME_TAG = "line_wizard_name"
const val LINE_WIZARD_CREATE_TAG = "line_wizard_create"
const val LINE_WIZARD_ISSUE_TAG = "line_wizard_issue"
const val LINE_WIZARD_SELECTED_TAG = "line_wizard_selected"

fun wizardPointTag(name: String) = "line_wizard_point_$name"

/** Lo que el usuario hace en el asistente «Línea desde puntos» (S025/RF-12 a RF-14). */
class LineFromPointsActions(
    val onQuery: (String) -> Unit = {},
    val onToggle: (String) -> Unit = {},
    val onSort: () -> Unit = {},
    val onReverse: () -> Unit = {},
    val onSelectCode: (SurveyCode?) -> Unit = {},
    val onCodeQuery: (String) -> Unit = {},
    val onCodeAttribute: (Int, String) -> Unit = { _, _ -> },
    val onClosed: (Boolean) -> Unit = {},
    val onName: (String) -> Unit = {},
    val onCreate: () -> Unit = {},
)

@Composable
fun LineFromPointsRoute(
    projectId: String,
    themeState: ThemeState,
    onToggleSun: () -> Unit,
    viewModel: LineFromPointsViewModel = hiltViewModel(),
    onBack: () -> Unit,
) {
    LaunchedEffect(projectId) { viewModel.start(projectId) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(state.finished) { if (state.finished) onBack() }
    LineFromPointsScreen(
        themeState,
        onToggleSun,
        state,
        LineFromPointsActions(
            onQuery = viewModel::setQuery,
            onToggle = viewModel::toggle,
            onSort = viewModel::sortByName,
            onReverse = viewModel::reverse,
            onSelectCode = viewModel::selectCode,
            onCodeQuery = viewModel::setCodeQuery,
            onCodeAttribute = viewModel::setCodeAttribute,
            onClosed = viewModel::setClosed,
            onName = viewModel::setName,
            onCreate = viewModel::create,
        ),
        onBack,
    )
}

/**
 * «Línea desde puntos» (S025/RF-12 a RF-15): se tocan los puntos del proyecto en el orden de la línea, se ordenan por nombre o se invierten,
 * se elige el código con sus atributos, si queda abierta o cerrada y el nombre, y se crea sin modificar ningún punto.
 */
@Composable
fun LineFromPointsScreen(
    themeState: ThemeState,
    onToggleSun: () -> Unit,
    state: LineFromPointsUi,
    actions: LineFromPointsActions,
    onBack: () -> Unit,
) {
    val codeActions = CollectorActions(
        onSelectCode = actions.onSelectCode,
        onCodeQuery = actions.onCodeQuery,
        onCodeAttribute = actions.onCodeAttribute,
    )
    SectionScaffold(
        title = stringResource(R.string.line_wizard_title),
        themeState = themeState,
        onToggleSun = onToggleSun,
        onNavigateBack = onBack,
    ) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item(key = "controls") {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(top = 12.dp)) {
                    Text(
                        pluralStringResource(R.plurals.line_wizard_selected, state.selected.size, state.selected.size),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.testTag(LINE_WIZARD_SELECTED_TAG),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        OutlinedButton(
                            onClick = actions.onSort,
                            enabled = state.selected.size > 1,
                            modifier = Modifier.weight(1f).heightIn(min = MinTouchTarget).testTag(LINE_WIZARD_SORT_TAG),
                        ) { Text(stringResource(R.string.line_wizard_sort)) }
                        OutlinedButton(
                            onClick = actions.onReverse,
                            enabled = state.selected.size > 1,
                            modifier = Modifier.weight(1f).heightIn(min = MinTouchTarget).testTag(LINE_WIZARD_REVERSE_TAG),
                        ) { Text(stringResource(R.string.line_wizard_reverse)) }
                    }
                    CodeSelectorRow(state.code, enabled = true, actions = codeActions)
                    AttributeFields(state.code, enabled = true, actions = codeActions)
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.fillMaxWidth().heightIn(min = MinTouchTarget),
                    ) {
                        Text(stringResource(R.string.line_wizard_closed), modifier = Modifier.weight(1f))
                        Switch(
                            checked = state.closed,
                            onCheckedChange = actions.onClosed,
                            modifier = Modifier.testTag(LINE_WIZARD_CLOSED_TAG),
                        )
                    }
                    OutlinedTextField(
                        value = state.name,
                        onValueChange = actions.onName,
                        label = { Text(stringResource(R.string.line_wizard_name)) },
                        singleLine = true,
                        isError = state.issue == LineWizardIssue.NAME_TAKEN,
                        modifier = Modifier.fillMaxWidth().testTag(LINE_WIZARD_NAME_TAG),
                    )
                    state.issue?.let {
                        Text(
                            stringResource(it.messageRes()),
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.testTag(LINE_WIZARD_ISSUE_TAG),
                        )
                    }
                    Button(
                        onClick = actions.onCreate,
                        modifier = Modifier.fillMaxWidth().heightIn(min = MinTouchTarget).testTag(LINE_WIZARD_CREATE_TAG),
                    ) { Text(stringResource(R.string.line_wizard_create)) }
                    OutlinedTextField(
                        value = state.query,
                        onValueChange = actions.onQuery,
                        label = { Text(stringResource(R.string.line_wizard_search)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().testTag(LINE_WIZARD_SEARCH_TAG),
                    )
                }
            }
            items(state.points, key = { it.id }) { point ->
                val position = state.positionOf(point.id)
                val description = if (position == null) {
                    point.name
                } else {
                    stringResource(R.string.line_wizard_point_chosen, point.name, position)
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxWidth().heightIn(min = MinTouchTarget).clickable { actions.onToggle(point.id) }
                        .semantics(mergeDescendants = true) {
                            contentDescription = description
                            role = Role.Checkbox
                            selected = position != null
                        }.testTag(wizardPointTag(point.name)),
                ) {
                    Text(
                        text = position?.toString().orEmpty(),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(36.dp),
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(point.name, style = MaterialTheme.typography.titleMedium)
                        if (point.description.isNotBlank()) Text(point.description, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }
}

private fun LineWizardIssue.messageRes(): Int = when (this) {
    LineWizardIssue.TOO_FEW_VERTICES -> R.string.line_wizard_issue_few
    LineWizardIssue.REPEATED_VERTEX -> R.string.line_wizard_issue_repeated
    LineWizardIssue.NAME_TAKEN -> R.string.line_wizard_issue_name
    LineWizardIssue.ATTRIBUTES -> R.string.line_wizard_issue_attributes
    LineWizardIssue.MISSING_POINT -> R.string.line_wizard_issue_missing
}
