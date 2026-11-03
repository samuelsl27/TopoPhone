package es.topophone.app.ui.codes

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import es.topophone.app.R
import es.topophone.app.ui.SectionScaffold
import es.topophone.app.ui.points.ImportFileProblem
import es.topophone.app.ui.points.importSourceOf
import es.topophone.core.designsystem.component.MinTouchTarget
import es.topophone.core.formats.codes.InvalidRow
import es.topophone.core.formats.codes.RowProblem
import es.topophone.core.model.codes.CodeError
import es.topophone.core.model.codes.DuplicatePolicy
import es.topophone.core.model.point.ImportDecimal
import es.topophone.core.model.point.ImportEncoding
import es.topophone.core.model.point.ImportSeparator
import es.topophone.core.model.preferences.ThemeState

const val CODE_IMPORT_LOADING_TAG = "code_import_loading"
const val CODE_IMPORT_PROBLEM_TAG = "code_import_problem"
const val CODE_IMPORT_MISSING_TAG = "code_import_missing"
const val CODE_IMPORT_CONFIRM_TAG = "code_import_confirm"
const val CODE_IMPORT_PREVIEW_TAG = "code_import_preview"
const val CODE_IMPORT_INVALID_TAG = "code_import_invalid"
const val CODE_IMPORT_CONFLICTS_TAG = "code_import_conflicts"
const val CODE_IMPORT_RESULT_TAG = "code_import_result"
const val CODE_IMPORT_FAILED_TAG = "code_import_failed"
const val CODE_IMPORT_OPEN_TAG = "code_import_open"
const val CODE_IMPORT_TARGET_NEW_TAG = "code_import_target_new"
const val CODE_IMPORT_DECIMAL_CONFLICT_TAG = "code_import_decimal_conflict"

fun codeImportEncodingTag(encoding: ImportEncoding) = "code_import_encoding_${encoding.name}"

fun codeImportSeparatorTag(separator: ImportSeparator) = "code_import_separator_${separator.name}"

fun codeImportDecimalTag(decimal: ImportDecimal) = "code_import_decimal_${decimal.name}"

fun codeImportPolicyTag(policy: DuplicatePolicy) = "code_import_policy_${policy.name}"

fun codeImportTargetTag(libraryId: String) = "code_import_target_$libraryId"

/** Lo que el usuario hace en el asistente de importación de bibliotecas (S023/RF-12 a RF-16). */
data class CodeImportActions(
    val onEncoding: (ImportEncoding) -> Unit = {},
    val onSeparator: (ImportSeparator) -> Unit = {},
    val onDecimal: (ImportDecimal) -> Unit = {},
    val onPolicy: (DuplicatePolicy) -> Unit = {},
    val onTarget: (CodeImportTarget) -> Unit = {},
    val onConfirm: () -> Unit = {},
    val onOpenLibrary: (String) -> Unit = {},
)

/**
 * Abre el selector de ficheros del sistema y, con el fichero elegido, llama a [onPicked] con su dirección (S023/RF-12, RNF-T5). Si el
 * usuario cancela, no pasa nada. Cualquier tipo vale: el contenido decide si es texto.
 */
@Composable
fun rememberCodeImportLauncher(onPicked: (fileUri: String) -> Unit): () -> Unit {
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) onPicked(uri.toString())
    }
    return { picker.launch(arrayOf("*/*")) }
}

@Composable
fun CodeImportRoute(
    fileUri: String,
    libraryId: String?,
    themeState: ThemeState,
    onToggleSun: () -> Unit,
    onOpenLibrary: (String) -> Unit,
    onBack: () -> Unit,
    viewModel: CodeImportViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val fallbackName = stringResource(R.string.codes_import_fallback_name)
    LaunchedEffect(fileUri, libraryId) {
        viewModel.start(importSourceOf(context.contentResolver, Uri.parse(fileUri)), fallbackName, libraryId)
    }
    val state by viewModel.state.collectAsStateWithLifecycle()
    CodeImportScreen(
        themeState,
        onToggleSun,
        state,
        CodeImportActions(
            onEncoding = viewModel::setEncoding,
            onSeparator = viewModel::setSeparator,
            onDecimal = viewModel::setDecimal,
            onPolicy = viewModel::setPolicy,
            onTarget = viewModel::setTarget,
            onConfirm = viewModel::confirm,
            onOpenLibrary = onOpenLibrary,
        ),
        onBack,
    )
}

/** El asistente de importación de una biblioteca desde un CSV (S023/RF-12 a RF-16). */
@Composable
fun CodeImportScreen(
    themeState: ThemeState,
    onToggleSun: () -> Unit,
    state: CodeImportUiState,
    actions: CodeImportActions,
    onBack: () -> Unit,
) {
    SectionScaffold(
        title = stringResource(R.string.codes_import_csv),
        themeState = themeState,
        onToggleSun = onToggleSun,
        onNavigateBack = onBack,
    ) { padding ->
        when (state.stage) {
            CodeImportStage.LOADING, CodeImportStage.IMPORTING -> Column(
                modifier = Modifier.padding(padding).padding(16.dp).testTag(CODE_IMPORT_LOADING_TAG),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                CircularProgressIndicator()
                Text(stringResource(R.string.codes_import_working))
            }
            CodeImportStage.PROBLEM -> Column(
                modifier = Modifier.padding(padding).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(state.fileName, style = MaterialTheme.typography.titleMedium)
                Text(
                    stringResource(problemText(state.fileProblem)),
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.testTag(CODE_IMPORT_PROBLEM_TAG),
                )
                Button(onClick = onBack, modifier = Modifier.heightIn(min = MinTouchTarget)) {
                    Text(stringResource(R.string.codes_import_close))
                }
            }
            CodeImportStage.RESULT -> Column(
                modifier = Modifier.padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(stringResource(R.string.import_file_name, state.fileName), style = MaterialTheme.typography.titleSmall)
                state.outcome?.let { Result(it, actions, onBack) }
            }
            CodeImportStage.WIZARD -> Column(
                modifier = Modifier.padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(stringResource(R.string.import_file_name, state.fileName), style = MaterialTheme.typography.titleMedium)
                Reading(state, actions)
                if (state.missingCode) {
                    Text(
                        stringResource(R.string.codes_import_missing_code),
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.testTag(CODE_IMPORT_MISSING_TAG),
                    )
                } else {
                    Counts(state)
                    Target(state, actions)
                    Conflicts(state, actions)
                    Preview(state)
                    Invalid(state)
                }
                if (state.writeFailed) {
                    Text(
                        stringResource(R.string.codes_import_write_failed),
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.testTag(CODE_IMPORT_FAILED_TAG),
                    )
                }
                Button(
                    onClick = actions.onConfirm,
                    enabled = state.canImport,
                    modifier = Modifier.fillMaxWidth().heightIn(min = MinTouchTarget).testTag(CODE_IMPORT_CONFIRM_TAG),
                ) { Text(stringResource(R.string.codes_import_confirm)) }
            }
        }
    }
}

/** Codificación, separador y signo decimal (S023/RF-12, con los de S028/RF-4 a RF-9). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Reading(state: CodeImportUiState, actions: CodeImportActions) {
    Text(stringResource(R.string.import_encoding), style = MaterialTheme.typography.titleSmall)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Choice(
            stringResource(R.string.import_encoding_utf8),
            codeImportEncodingTag(ImportEncoding.UTF8),
            state.encoding == ImportEncoding.UTF8,
        ) {
            actions.onEncoding(ImportEncoding.UTF8)
        }
        Choice(
            stringResource(R.string.import_encoding_windows1252),
            codeImportEncodingTag(ImportEncoding.WINDOWS_1252),
            state.encoding == ImportEncoding.WINDOWS_1252,
        ) { actions.onEncoding(ImportEncoding.WINDOWS_1252) }
    }
    Text(stringResource(R.string.import_separator), style = MaterialTheme.typography.titleSmall)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(
            ImportSeparator.COMMA to R.string.import_separator_comma,
            ImportSeparator.SEMICOLON to R.string.import_separator_semicolon,
            ImportSeparator.TAB to R.string.import_separator_tab,
        ).forEach { (separator, label) ->
            Choice(stringResource(label), codeImportSeparatorTag(separator), state.separator == separator) {
                actions.onSeparator(separator)
            }
        }
    }
    Text(stringResource(R.string.import_decimal), style = MaterialTheme.typography.titleSmall)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Choice(
            stringResource(R.string.import_decimal_point),
            codeImportDecimalTag(ImportDecimal.POINT),
            state.decimal == ImportDecimal.POINT,
        ) {
            actions.onDecimal(ImportDecimal.POINT)
        }
        Choice(
            stringResource(R.string.import_decimal_comma),
            codeImportDecimalTag(ImportDecimal.COMMA),
            state.decimal == ImportDecimal.COMMA,
        ) {
            actions.onDecimal(ImportDecimal.COMMA)
        }
    }
    if (state.decimalConflict) {
        Text(
            stringResource(R.string.codes_import_decimal_conflict),
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.testTag(CODE_IMPORT_DECIMAL_CONFLICT_TAG),
        )
    }
}

@Composable
private fun Counts(state: CodeImportUiState) {
    Column {
        Text(
            pluralStringResource(R.plurals.codes_import_valid, state.validCount, state.validCount),
            style = MaterialTheme.typography.titleSmall,
        )
        if (state.invalidCount > 0) {
            Text(
                pluralStringResource(R.plurals.codes_import_discarded, state.invalidCount, state.invalidCount),
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

/** Biblioteca nueva con el nombre del fichero o una del usuario que ya existe (S023/RF-14). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Target(state: CodeImportUiState, actions: CodeImportActions) {
    Text(stringResource(R.string.codes_import_target), style = MaterialTheme.typography.titleSmall)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Choice(stringResource(R.string.codes_import_target_new), CODE_IMPORT_TARGET_NEW_TAG, state.target == CodeImportTarget.New) {
            actions.onTarget(CodeImportTarget.New)
        }
        state.libraries.forEach { library ->
            val target = CodeImportTarget.Existing(library.id)
            Choice(library.name, codeImportTargetTag(library.id), state.target == target) { actions.onTarget(target) }
        }
    }
}

/** Los repetidos con la biblioteca y entre sí, y qué hacer con ellos (S023/RF-16). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Conflicts(state: CodeImportUiState, actions: CodeImportActions) {
    if (state.conflicts.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.testTag(CODE_IMPORT_CONFLICTS_TAG)) {
        Text(stringResource(R.string.codes_import_conflicts_title), style = MaterialTheme.typography.titleSmall)
        if (state.conflicts.withLibrary.isNotEmpty()) {
            Text(stringResource(R.string.codes_import_conflicts_library, listed(state.conflicts.withLibrary)))
        }
        if (state.conflicts.inFile.isNotEmpty()) {
            Text(stringResource(R.string.codes_import_conflicts_file, listed(state.conflicts.inFile)))
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Choice(
                stringResource(R.string.codes_import_policy_skip),
                codeImportPolicyTag(DuplicatePolicy.SKIP),
                state.policy == DuplicatePolicy.SKIP,
            ) {
                actions.onPolicy(DuplicatePolicy.SKIP)
            }
            Choice(
                stringResource(R.string.codes_import_policy_replace),
                codeImportPolicyTag(DuplicatePolicy.REPLACE),
                state.policy == DuplicatePolicy.REPLACE,
            ) { actions.onPolicy(DuplicatePolicy.REPLACE) }
        }
    }
}

private fun listed(codes: List<String>): String =
    if (codes.size <= MAX_LISTED_CODES) codes.joinToString(", ") else codes.take(MAX_LISTED_CODES).joinToString(", ") + ", …"

private const val MAX_LISTED_CODES = 20

/** Las primeras filas válidas, cada una con su color junto al código, su geometría y sus atributos en texto (S023/RF-12, RNF-T8). */
@Composable
private fun Preview(state: CodeImportUiState) {
    if (state.preview.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.testTag(CODE_IMPORT_PREVIEW_TAG)) {
        Text(stringResource(R.string.codes_import_preview), style = MaterialTheme.typography.titleSmall)
        state.preview.forEach { row ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ColorSwatch(row.color)
                Column {
                    Text(row.code, style = MaterialTheme.typography.titleMedium)
                    val attributes = pluralStringResource(R.plurals.codes_attributes_count, row.attributes, row.attributes)
                    val description = if (row.description.isBlank()) "" else row.description + " · "
                    Text(
                        description + stringResource(geometryLabel(row.geometry)) + " · " + attributes,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}

/** Las filas descartadas con su línea y su motivo (S023/RF-15). */
@Composable
private fun Invalid(state: CodeImportUiState) {
    if (state.invalid.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.testTag(CODE_IMPORT_INVALID_TAG)) {
        Text(stringResource(R.string.codes_import_invalid_title), style = MaterialTheme.typography.titleSmall)
        state.invalid.forEach { row ->
            Text(stringResource(R.string.codes_import_invalid_line, row.line, reasonText(row)))
        }
        val more = state.invalidCount - state.invalid.size
        if (more > 0) Text(stringResource(R.string.codes_import_invalid_more, more))
    }
}

@Composable
private fun Result(outcome: CodeImportOutcome, actions: CodeImportActions, onBack: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.testTag(CODE_IMPORT_RESULT_TAG)) {
        Text(
            pluralStringResource(R.plurals.codes_import_result, outcome.entered, outcome.entered),
            style = MaterialTheme.typography.titleMedium,
        )
        if (outcome.skipped > 0) Text(pluralStringResource(R.plurals.codes_import_result_skipped, outcome.skipped, outcome.skipped))
        if (outcome.invalid > 0) Text(pluralStringResource(R.plurals.codes_import_result_invalid, outcome.invalid, outcome.invalid))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = { actions.onOpenLibrary(outcome.libraryId) },
                modifier = Modifier.heightIn(min = MinTouchTarget).testTag(CODE_IMPORT_OPEN_TAG),
            ) { Text(stringResource(R.string.codes_import_open)) }
            OutlinedButton(onClick = onBack, modifier = Modifier.heightIn(min = MinTouchTarget)) {
                Text(stringResource(R.string.codes_import_close))
            }
        }
    }
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

private fun problemText(problem: ImportFileProblem?): Int = when (problem) {
    ImportFileProblem.EMPTY -> R.string.import_problem_empty
    ImportFileProblem.NOT_TEXT -> R.string.import_problem_not_text
    ImportFileProblem.NO_COLUMNS -> R.string.import_problem_no_columns
    ImportFileProblem.TOO_BIG -> R.string.import_problem_too_big
    ImportFileProblem.TOO_MANY_ROWS -> R.string.import_problem_too_many_rows
    ImportFileProblem.UNREADABLE, null -> R.string.import_problem_unreadable
}

/** El texto del motivo por el que no se importa una fila; los números de atributo son los de su cabecera `Attribute_N_*` (S023/RF-15). */
@Composable
private fun reasonText(row: InvalidRow): String = when (val problem = row.problem) {
    is RowProblem.Code -> stringResource(
        when (problem.error) {
            CodeError.TooLong -> R.string.codes_reason_too_long
            CodeError.NewLine -> R.string.codes_reason_newline
            else -> R.string.codes_reason_empty
        },
    )
    is RowProblem.UnknownType -> stringResource(R.string.codes_reason_unknown_type, problem.text)
    is RowProblem.UnknownColor -> stringResource(R.string.codes_reason_unknown_color, problem.text)
    is RowProblem.UnknownAttributeType -> stringResource(R.string.codes_reason_unknown_attribute_type, problem.number, problem.text)
    is RowProblem.Attribute -> when (problem.error) {
        is CodeError.ListOptions -> stringResource(R.string.codes_reason_attribute_list, problem.number)
        is CodeError.DefaultValue -> stringResource(R.string.codes_reason_attribute_default, problem.number)
        CodeError.TooManyAttributes -> stringResource(R.string.codes_reason_too_many_attributes)
        else -> stringResource(R.string.codes_reason_attribute_name, problem.number)
    }
}
