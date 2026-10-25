package es.topophone.app.ui.points

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import es.topophone.app.R
import es.topophone.app.ui.SectionScaffold
import es.topophone.core.designsystem.component.MinTouchTarget
import es.topophone.core.model.point.ImportDecimal
import es.topophone.core.model.point.ImportEncoding
import es.topophone.core.model.point.ImportSeparator
import es.topophone.core.model.preferences.ThemeState

const val IMPORT_PROBLEM_TAG = "import_problem"
const val IMPORT_HEADER_TAG = "import_header"
const val IMPORT_COMMA_CONFLICT_TAG = "import_comma_conflict"
const val IMPORT_LOADING_TAG = "import_loading"

fun importEncodingTag(encoding: ImportEncoding) = "import_encoding_${encoding.name}"

fun importSeparatorTag(separator: ImportSeparator) = "import_separator_${separator.name}"

fun importDecimalTag(decimal: ImportDecimal) = "import_decimal_${decimal.name}"

/** Acciones del asistente de importación (S028). */
class PointImportActions(
    val onEncoding: (ImportEncoding) -> Unit = {},
    val onSeparator: (ImportSeparator) -> Unit = {},
    val onDecimal: (ImportDecimal) -> Unit = {},
    val onHasHeader: (Boolean) -> Unit = {},
)

/**
 * El asistente de importación de puntos de un CSV o TXT (S028), en una sola pantalla con secciones: el fichero, la lectura
 * (codificación, separador, signo decimal y cabecera)… Si el fichero no se puede abrir, dice el motivo (RF-2, RF-3).
 */
@Composable
fun PointImportScreen(
    themeState: ThemeState,
    onToggleSun: () -> Unit,
    state: PointImportUiState,
    actions: PointImportActions,
    onBack: () -> Unit,
) {
    SectionScaffold(
        title = stringResource(R.string.import_title),
        themeState = themeState,
        onToggleSun = onToggleSun,
        onNavigateBack = onBack,
    ) { padding ->
        when (state.stage) {
            ImportStage.LOADING, ImportStage.IMPORTING -> Column(
                modifier = Modifier.padding(padding).padding(16.dp).testTag(IMPORT_LOADING_TAG),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                CircularProgressIndicator()
                Text(stringResource(R.string.import_loading))
            }
            ImportStage.PROBLEM -> Column(
                modifier = Modifier.padding(padding).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(state.fileName, style = MaterialTheme.typography.titleMedium)
                Text(
                    stringResource(problemText(state.fileProblem)),
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.testTag(IMPORT_PROBLEM_TAG),
                ) // S028/RF-2, RF-3
                Button(onClick = onBack, modifier = Modifier.heightIn(min = MinTouchTarget)) { Text(stringResource(R.string.import_close)) }
            }
            ImportStage.WIZARD, ImportStage.RESULT -> Column(
                modifier = Modifier.padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(stringResource(R.string.import_file_name, state.fileName), style = MaterialTheme.typography.titleMedium)
                ReadingSection(state, actions)
            }
        }
    }
}

@StringRes
private fun problemText(problem: ImportFileProblem?): Int = when (problem) {
    ImportFileProblem.EMPTY -> R.string.import_problem_empty
    ImportFileProblem.NOT_TEXT -> R.string.import_problem_not_text
    ImportFileProblem.NO_COLUMNS -> R.string.import_problem_no_columns
    ImportFileProblem.TOO_BIG -> R.string.import_problem_too_big
    ImportFileProblem.TOO_MANY_ROWS -> R.string.import_problem_too_many_rows
    ImportFileProblem.UNREADABLE, null -> R.string.import_problem_unreadable
}

/** Codificación, separador, signo decimal y cabecera (S028/RF-4 a RF-9). */
@Composable
private fun ReadingSection(state: PointImportUiState, actions: PointImportActions) {
    val options = state.options
    Text(stringResource(R.string.import_section_reading), style = MaterialTheme.typography.titleSmall)
    ChoiceGroup(R.string.import_encoding) {
        ImportEncoding.entries.forEach { encoding ->
            Choice(stringResource(encodingLabel(encoding)), importEncodingTag(encoding), options.encoding == encoding) {
                actions.onEncoding(encoding)
            }
        }
    } // RF-4
    ChoiceGroup(R.string.import_separator) {
        ImportSeparator.entries.forEach { separator ->
            Choice(stringResource(separatorLabel(separator)), importSeparatorTag(separator), options.separator == separator) {
                actions.onSeparator(separator)
            }
        }
    } // RF-5
    ChoiceGroup(R.string.import_decimal) {
        ImportDecimal.entries.forEach { decimal ->
            Choice(stringResource(decimalLabel(decimal)), importDecimalTag(decimal), options.decimal == decimal) {
                actions.onDecimal(decimal)
            }
        }
    } // RF-7
    if (options.decimalConflict) {
        Text(
            stringResource(R.string.import_comma_conflict),
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.testTag(IMPORT_COMMA_CONFLICT_TAG),
        ) // RF-8
    }
    CheckRow(stringResource(R.string.import_has_header), options.hasHeader, IMPORT_HEADER_TAG) { actions.onHasHeader(it) } // RF-9
}

@StringRes
private fun encodingLabel(encoding: ImportEncoding) = when (encoding) {
    ImportEncoding.UTF8 -> R.string.import_encoding_utf8
    ImportEncoding.WINDOWS_1252 -> R.string.import_encoding_windows1252
}

@StringRes
private fun separatorLabel(separator: ImportSeparator) = when (separator) {
    ImportSeparator.COMMA -> R.string.import_separator_comma
    ImportSeparator.SEMICOLON -> R.string.import_separator_semicolon
    ImportSeparator.TAB -> R.string.import_separator_tab
    ImportSeparator.SPACES -> R.string.import_separator_spaces
}

@StringRes
private fun decimalLabel(decimal: ImportDecimal) = when (decimal) {
    ImportDecimal.POINT -> R.string.import_decimal_point
    ImportDecimal.COMMA -> R.string.import_decimal_comma
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChoiceGroup(@StringRes title: Int, content: @Composable () -> Unit) {
    Text(stringResource(title), style = MaterialTheme.typography.labelLarge)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { content() }
}

@Composable
private fun Choice(label: String, tag: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) },
        modifier = Modifier.heightIn(min = MinTouchTarget).testTag(tag),
    )
}

@Composable
private fun CheckRow(label: String, checked: Boolean, tag: String, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = MinTouchTarget)
            .toggleable(value = checked, role = Role.Checkbox, onValueChange = onChange)
            .testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = null)
        Text(label, modifier = Modifier.padding(start = 12.dp))
    }
}
