package es.topophone.app.ui.export

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import es.topophone.app.R
import es.topophone.app.ui.SectionScaffold
import es.topophone.app.ui.projects.SurveyNavigation
import es.topophone.core.designsystem.component.MinTouchTarget
import es.topophone.core.formats.csv.ColumnSeparator
import es.topophone.core.formats.csv.DecimalSeparator
import es.topophone.core.formats.csv.ExportColumn
import es.topophone.core.formats.csv.ExportFileName
import es.topophone.core.formats.csv.ExportFormat
import es.topophone.core.model.preferences.ThemeState
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

const val EXPORT_SAVE_TAG = "export_save"
const val EXPORT_SHARE_TAG = "export_share"
private const val CSV_MIME = "text/csv"

fun formatTag(format: ExportFormat) = "export_format_${format.name}"

fun separatorTag(separator: ColumnSeparator) = "export_separator_${separator.name}"

fun decimalTag(decimal: DecimalSeparator) = "export_decimal_${decimal.name}"

fun columnTag(column: ExportColumn) = "export_column_${column.name}"

/** Acciones de la pantalla de exportación; guardar y compartir reciben el nombre propuesto (S012/RF-19). */
class ExportActions(
    val onFormat: (ExportFormat) -> Unit = {},
    val onSeparator: (ColumnSeparator) -> Unit = {},
    val onDecimal: (DecimalSeparator) -> Unit = {},
    val onToggleColumn: (ExportColumn) -> Unit = {},
    val onSave: (fileName: String) -> Unit = {},
    val onShare: (fileName: String) -> Unit = {},
)

@Composable
fun ExportRoute(
    projectId: String,
    selection: Boolean,
    themeState: ThemeState,
    onToggleSun: () -> Unit,
    navigation: SurveyNavigation,
    viewModel: ExportViewModel = hiltViewModel(),
) {
    LaunchedEffect(projectId, selection) { viewModel.start(projectId, selection) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // S012/RF-17: el selector de ficheros del móvil; si el usuario cancela, no se escribe nada.
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(CSV_MIME)) { uri ->
        if (uri != null) {
            scope.launch {
                val ok = writeToUri(context.contentResolver, uri, viewModel::writeTo)
                viewModel.report(if (ok) ExportResult.SAVED else ExportResult.FAILED)
            }
        }
    }
    ExportScreen(
        themeState,
        onToggleSun,
        state,
        ExportActions(
            onFormat = viewModel::setFormat,
            onSeparator = viewModel::setSeparator,
            onDecimal = viewModel::setDecimal,
            onToggleColumn = viewModel::toggleColumn,
            onSave = { name ->
                viewModel.report(null)
                save.launch(name)
            },
            onShare = { name ->
                viewModel.report(null)
                scope.launch {
                    // S012/RF-18: desde la caché de la app, con el FileProvider.
                    val file = ExportFiles.prepare(context.cacheDir, name, System.currentTimeMillis())
                    val ok = withContext(Dispatchers.IO) { file.outputStream() }.use { viewModel.writeTo(it) }
                    if (ok) context.startActivity(shareExportIntent(context, file)) else viewModel.report(ExportResult.FAILED)
                }
            },
        ),
        navigation.onBack,
    )
}

/**
 * Exportar CSV (S012): cuántos puntos van, formato (sin PENZD ni PNEZD en un SC geográfico), columnas del
 * personalizado, separador y signo decimal, el nombre propuesto y "Guardar" y "Compartir".
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ExportScreen(themeState: ThemeState, onToggleSun: () -> Unit, state: ExportUiState, actions: ExportActions, onBack: () -> Unit) {
    SectionScaffold(
        title = stringResource(R.string.export_csv),
        themeState = themeState,
        onToggleSun = onToggleSun,
        onNavigateBack = onBack,
    ) { padding ->
        if (!state.loaded) return@SectionScaffold
        Column(
            modifier = Modifier.padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                if (state.pointCount ==
                    0
                ) {
                    stringResource(R.string.export_none)
                } else {
                    pluralStringResource(R.plurals.export_count, state.pointCount, state.pointCount)
                },
                style = MaterialTheme.typography.titleMedium,
            ) // S012/RF-4
            Section(R.string.export_format) {
                state.formats.forEach { format ->
                    Choice(formatLabel(format), formatTag(format), state.options.format == format) { actions.onFormat(format) }
                }
            } // S012/RF-5, RF-10
            if (state.options.format == ExportFormat.CUSTOM) {
                Text(stringResource(R.string.export_columns), style = MaterialTheme.typography.labelLarge)
                ExportColumn.entries.forEach { column ->
                    ColumnRow(column, column in state.options.customColumns) { actions.onToggleColumn(column) }
                } // S012/RF-8
            }
            Section(R.string.export_separator) {
                ColumnSeparator.entries.forEach { separator ->
                    Choice(separatorLabel(separator), separatorTag(separator), state.options.separator == separator) {
                        actions.onSeparator(separator)
                    }
                }
            } // S012/RF-13
            Section(R.string.export_decimal) {
                DecimalSeparator.entries.forEach { decimal ->
                    Choice(decimalLabel(decimal), decimalTag(decimal), state.options.decimal == decimal) { actions.onDecimal(decimal) }
                }
            }
            if (!state.options.valid) Text(stringResource(R.string.export_invalid), color = MaterialTheme.colorScheme.error) // S012/RF-14
            val opened = remember { Instant.now() }
            val label = if (state.options.format ==
                ExportFormat.ALL
            ) {
                stringResource(R.string.export_file_all)
            } else {
                formatLabel(state.options.format)
            }
            val fileName = ExportFileName.of(state.projectName, label, opened, ZoneId.systemDefault()) // S012/RF-19
            Text(stringResource(R.string.export_file_name, fileName), style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    onClick = { actions.onSave(fileName) },
                    enabled = state.canExport,
                    modifier = Modifier.weight(1f).heightIn(min = MinTouchTarget).testTag(EXPORT_SAVE_TAG),
                ) { Text(stringResource(R.string.export_save)) } // S012/RF-17
                OutlinedButton(
                    onClick = { actions.onShare(fileName) },
                    enabled = state.canExport,
                    modifier = Modifier.weight(1f).heightIn(min = MinTouchTarget).testTag(EXPORT_SHARE_TAG),
                ) { Text(stringResource(R.string.export_share)) } // S012/RF-18
            }
            when (state.result) {
                ExportResult.SAVED -> Text(stringResource(R.string.export_saved))
                ExportResult.FAILED -> Text(stringResource(R.string.export_failed), color = MaterialTheme.colorScheme.error) // S012/RF-20
                null -> Unit
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Section(@StringRes title: Int, content: @Composable () -> Unit) {
    Text(stringResource(title), style = MaterialTheme.typography.labelLarge)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { content() }
}

@Composable
private fun Choice(label: String, tag: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(selected = selected, onClick = onClick, label = {
        Text(label)
    }, modifier = Modifier.heightIn(min = MinTouchTarget).testTag(tag))
}

@Composable
private fun ColumnRow(column: ExportColumn, checked: Boolean, onToggle: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = MinTouchTarget)
            .toggleable(value = checked, role = Role.Checkbox, onValueChange = { onToggle() })
            .testTag(columnTag(column)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = null)
        Text(stringResource(column.labelRes()), modifier = Modifier.padding(start = 12.dp))
    }
}

@Composable
private fun formatLabel(format: ExportFormat): String = stringResource(
    when (format) {
        ExportFormat.ALL -> R.string.export_format_all
        ExportFormat.PENZD -> R.string.export_format_penzd
        ExportFormat.PNEZD -> R.string.export_format_pnezd
        ExportFormat.CUSTOM -> R.string.export_format_custom
    },
)

@Composable
private fun separatorLabel(separator: ColumnSeparator): String = stringResource(
    when (separator) {
        ColumnSeparator.COMMA -> R.string.export_separator_comma
        ColumnSeparator.SEMICOLON -> R.string.export_separator_semicolon
        ColumnSeparator.TAB -> R.string.export_separator_tab
    },
)

@Composable
private fun decimalLabel(decimal: DecimalSeparator): String = stringResource(
    when (decimal) {
        DecimalSeparator.POINT -> R.string.export_decimal_point
        DecimalSeparator.COMMA -> R.string.export_decimal_comma
    },
)

private fun ExportColumn.labelRes(): Int = when (this) {
    ExportColumn.NAME -> R.string.export_column_name
    ExportColumn.DESCRIPTION -> R.string.export_column_description
    ExportColumn.ORIGIN -> R.string.export_column_origin
    ExportColumn.EASTING -> R.string.export_column_easting
    ExportColumn.NORTHING -> R.string.export_column_northing
    ExportColumn.ELEVATION -> R.string.export_column_elevation
    ExportColumn.LATITUDE -> R.string.export_column_latitude
    ExportColumn.LONGITUDE -> R.string.export_column_longitude
    ExportColumn.ELLIPSOIDAL_HEIGHT -> R.string.export_column_ellipsoidal_height
    ExportColumn.CRS -> R.string.export_column_crs
    ExportColumn.HEIGHT_SYSTEM -> R.string.export_column_height_system
    ExportColumn.UNITS -> R.string.export_column_units
    ExportColumn.POLE_HEIGHT -> R.string.export_column_pole_height
    ExportColumn.SOLUTION -> R.string.export_column_solution
    ExportColumn.SATELLITES -> R.string.export_column_satellites
    ExportColumn.HDOP -> R.string.export_column_hdop
    ExportColumn.CORRECTION_AGE -> R.string.export_column_correction_age
    ExportColumn.HORIZONTAL_ACCURACY -> R.string.export_column_horizontal_accuracy
    ExportColumn.VERTICAL_ACCURACY -> R.string.export_column_vertical_accuracy
    ExportColumn.MODE -> R.string.export_column_mode
    ExportColumn.EPOCHS_USED -> R.string.export_column_epochs_used
    ExportColumn.EPOCHS_DISCARDED -> R.string.export_column_epochs_discarded
    ExportColumn.REPLAY -> R.string.export_column_replay
    ExportColumn.OUT_OF_TOLERANCE -> R.string.export_column_out_of_tolerance
    ExportColumn.MEASURED_FROM -> R.string.export_column_measured_from
    ExportColumn.MEASURED_TO -> R.string.export_column_measured_to
    ExportColumn.BASE_LATITUDE -> R.string.export_column_base_latitude // S015/RF-22
    ExportColumn.BASE_LONGITUDE -> R.string.export_column_base_longitude
    ExportColumn.BASE_ELLIPSOIDAL_HEIGHT -> R.string.export_column_base_ellipsoidal_height
    ExportColumn.BASE_EASTING -> R.string.export_column_base_easting
    ExportColumn.BASE_NORTHING -> R.string.export_column_base_northing
    ExportColumn.BASE_ELEVATION -> R.string.export_column_base_elevation
    ExportColumn.BASELINE -> R.string.export_column_baseline
    ExportColumn.TILT_MODE -> R.string.export_column_tilt_mode // S016/RF-24
    ExportColumn.TILT_MODE_VERIFIED -> R.string.export_column_tilt_mode_verified
    ExportColumn.TILT -> R.string.export_column_tilt
    ExportColumn.TILT_DIRECTION -> R.string.export_column_tilt_direction
    ExportColumn.HEADING -> R.string.export_column_heading
    ExportColumn.MAX_TILT -> R.string.export_column_max_tilt
    ExportColumn.TILT_HORIZONTAL_ACCURACY -> R.string.export_column_tilt_horizontal_accuracy
    ExportColumn.TILT_VERTICAL_ACCURACY -> R.string.export_column_tilt_vertical_accuracy
    ExportColumn.AVERAGED_TIME -> R.string.export_column_averaged_time // S017/RF-14
    ExportColumn.DEVIATION_HORIZONTAL -> R.string.export_column_deviation_horizontal
    ExportColumn.DEVIATION_VERTICAL -> R.string.export_column_deviation_vertical
    ExportColumn.AUTOMATIC -> R.string.export_column_automatic
    ExportColumn.EDITED -> R.string.export_column_edited
    ExportColumn.COPY_OF -> R.string.export_column_copy_of
    ExportColumn.STAKE_DESIGN -> R.string.export_column_stake_design // S019/RF-25
    ExportColumn.STAKE_DESIGN_ELEVATION -> R.string.export_column_stake_design_elevation
    ExportColumn.STAKE_D_NORTH -> R.string.export_column_stake_d_north
    ExportColumn.STAKE_D_EAST -> R.string.export_column_stake_d_east
    ExportColumn.STAKE_D_HORIZONTAL -> R.string.export_column_stake_d_horizontal
    ExportColumn.STAKE_D_ELEVATION -> R.string.export_column_stake_d_elevation
    ExportColumn.STAKE_WITHIN -> R.string.export_column_stake_within
}
