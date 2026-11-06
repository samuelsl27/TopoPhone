package es.topophone.app.ui.export

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
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import es.topophone.app.R
import es.topophone.app.ui.SectionScaffold
import es.topophone.app.ui.projects.SurveyNavigation
import es.topophone.core.designsystem.component.MinTouchTarget
import es.topophone.core.formats.csv.ExportFileName
import es.topophone.core.formats.dxf.export.DxfCoordinates
import es.topophone.core.formats.dxf.export.DxfLabel
import es.topophone.core.formats.dxf.export.DxfUnit
import es.topophone.core.model.preferences.ThemeState
import java.io.File
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.launch

const val DXF_COUNT_TAG = "dxf_count"
const val DXF_LINES_TAG = "dxf_lines"
const val DXF_LABEL_NAME_TAG = "dxf_label_name"
const val DXF_LABEL_ELEVATION_TAG = "dxf_label_elevation"
const val DXF_LABEL_DESCRIPTION_TAG = "dxf_label_description"
const val DXF_TEXT_HEIGHT_TAG = "dxf_text_height"
const val DXF_TEXT_HEIGHT_ERROR_TAG = "dxf_text_height_error"
const val DXF_COORD_LOCAL_TAG = "dxf_coord_local"
const val DXF_COORD_GLOBAL_TAG = "dxf_coord_global"
const val DXF_COORD_FIXED_TAG = "dxf_coord_fixed"
const val DXF_UNIT_TAG = "dxf_unit"
const val DXF_FILE_NAME_TAG = "dxf_file_name"
const val DXF_SAVE_TAG = "dxf_save"
const val DXF_SHARE_TAG = "dxf_share"
const val DXF_RESULT_TAG = "dxf_result"
const val DXF_SUMMARY_TAG = "dxf_summary"
const val DXF_NO_HEIGHT_TAG = "dxf_no_height"
const val DXF_NO_POSITION_TAG = "dxf_no_position"
const val DXF_REPLACED_TAG = "dxf_replaced"

/** El tipo MIME de un DXF. */
private const val DXF_MIME = "application/dxf"

/** Acciones de la pantalla de exportar a DXF; guardar y compartir reciben el nombre propuesto (S029/RF-16, RF-17). */
class DxfExportActions(
    val onLabel: (DxfLabel, Boolean) -> Unit = { _, _ -> },
    val onCoordinates: (DxfCoordinates) -> Unit = {},
    val onTextHeight: (String) -> Unit = {},
    val onSave: (fileName: String) -> Unit = {},
    val onShare: (fileName: String) -> Unit = {},
)

@Composable
fun DxfExportRoute(
    projectId: String,
    selection: Boolean,
    themeState: ThemeState,
    onToggleSun: () -> Unit,
    navigation: SurveyNavigation,
    viewModel: DxfExportViewModel = hiltViewModel(),
) {
    LaunchedEffect(projectId, selection) { viewModel.start(projectId, selection) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val workDir = remember { File(context.cacheDir, "dxf-work") }
    // S029/RF-17: el selector de ficheros del móvil; si el usuario cancela, no se escribe nada.
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(DXF_MIME)) { uri ->
        if (uri != null) {
            scope.launch {
                val ok = writeToUri(context.contentResolver, uri) { viewModel.writeTo(it, workDir) }
                viewModel.report(if (ok) ExportResult.SAVED else ExportResult.FAILED)
            }
        }
    }
    DxfExportScreen(
        themeState,
        onToggleSun,
        state,
        DxfExportActions(
            onLabel = viewModel::setLabel,
            onCoordinates = viewModel::setCoordinates,
            onTextHeight = viewModel::setTextHeight,
            onSave = { name ->
                viewModel.report(null)
                save.launch(name)
            },
            onShare = { name ->
                viewModel.report(null)
                scope.launch {
                    // S029/RF-17: desde la caché de la app, con el FileProvider.
                    val file = ExportFiles.prepare(context.cacheDir, name, System.currentTimeMillis())
                    if (viewModel.writeFile(file)) {
                        context.startActivity(
                            shareExportIntent(context, file, mimeType = DXF_MIME, chooserTitle = R.string.dxf_share_chooser),
                        )
                        viewModel.report(ExportResult.SAVED)
                    } else {
                        viewModel.report(ExportResult.FAILED)
                    }
                }
            },
        ),
        navigation.onBack,
    )
}

/**
 * Exportar DXF (S029): cuántos puntos, líneas y polígonos van, las etiquetas, la altura del texto, el sistema de coordenadas con su unidad,
 * el nombre propuesto y "Guardar" y "Compartir"; y, tras exportar, el resumen con lo escrito y los avisos.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DxfExportScreen(
    themeState: ThemeState,
    onToggleSun: () -> Unit,
    state: DxfExportUiState,
    actions: DxfExportActions,
    onBack: () -> Unit,
) {
    SectionScaffold(
        title = stringResource(R.string.dxf_export_title),
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
                if (state.pointCount == 0) {
                    stringResource(R.string.export_none)
                } else {
                    pluralStringResource(R.plurals.export_count, state.pointCount, state.pointCount)
                },
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.testTag(DXF_COUNT_TAG),
            ) // S029/RF-1, RF-18
            if (state.lineCount > 0 || state.polygonCount > 0) {
                Text(
                    listOfNotNull(
                        pluralStringResource(R.plurals.gpkg_export_lines, state.lineCount, state.lineCount).takeIf { state.lineCount > 0 },
                        pluralStringResource(R.plurals.gpkg_export_polygons, state.polygonCount, state.polygonCount)
                            .takeIf { state.polygonCount > 0 },
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.testTag(DXF_LINES_TAG),
                ) // RF-3
            }
            Text(stringResource(R.string.dxf_export_labels), style = MaterialTheme.typography.labelLarge)
            LabelRow(R.string.dxf_label_name, DxfLabel.NAME, DXF_LABEL_NAME_TAG, state, actions)
            LabelRow(R.string.dxf_label_elevation, DxfLabel.ELEVATION, DXF_LABEL_ELEVATION_TAG, state, actions)
            LabelRow(R.string.dxf_label_description, DxfLabel.DESCRIPTION, DXF_LABEL_DESCRIPTION_TAG, state, actions) // RF-7
            OutlinedTextField(
                value = state.heightText,
                onValueChange = actions.onTextHeight,
                label = { Text(stringResource(R.string.dxf_export_text_height)) },
                isError = !state.heightValid,
                supportingText = if (state.heightValid) {
                    null
                } else {
                    { Text(stringResource(R.string.dxf_export_text_height_error), modifier = Modifier.testTag(DXF_TEXT_HEIGHT_ERROR_TAG)) }
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                singleLine = true,
                modifier = Modifier.fillMaxWidth().heightIn(min = MinTouchTarget).testTag(DXF_TEXT_HEIGHT_TAG),
            ) // RF-8
            Text(stringResource(R.string.dxf_export_coordinates), style = MaterialTheme.typography.labelLarge)
            if (state.canChooseCoordinates) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = state.coordinates == DxfCoordinates.LOCAL,
                        onClick = { actions.onCoordinates(DxfCoordinates.LOCAL) },
                        label = { Text(stringResource(R.string.dxf_export_local)) },
                        modifier = Modifier.heightIn(min = MinTouchTarget).testTag(DXF_COORD_LOCAL_TAG),
                    )
                    FilterChip(
                        selected = state.coordinates == DxfCoordinates.GLOBAL,
                        onClick = { actions.onCoordinates(DxfCoordinates.GLOBAL) },
                        label = { Text(stringResource(R.string.dxf_export_global)) },
                        modifier = Modifier.heightIn(min = MinTouchTarget).testTag(DXF_COORD_GLOBAL_TAG),
                    )
                }
            } else {
                Text(stringResource(R.string.dxf_export_global_fixed), modifier = Modifier.testTag(DXF_COORD_FIXED_TAG))
            } // RF-11, RF-12, RF-13
            Text(
                stringResource(R.string.dxf_export_unit, stringResource(unitName(state.unit))),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.testTag(DXF_UNIT_TAG),
            ) // RF-14
            val opened = remember { Instant.now() }
            val fileName = ExportFileName.of(state.projectName, "dxf", opened, ZoneId.systemDefault(), "dxf") // RF-16
            Text(
                stringResource(R.string.export_file_name, fileName),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.testTag(DXF_FILE_NAME_TAG),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    onClick = { actions.onSave(fileName) },
                    enabled = state.canExport,
                    modifier = Modifier.weight(1f).heightIn(min = MinTouchTarget).testTag(DXF_SAVE_TAG),
                ) { Text(stringResource(R.string.export_save)) } // RF-17
                OutlinedButton(
                    onClick = { actions.onShare(fileName) },
                    enabled = state.canExport,
                    modifier = Modifier.weight(1f).heightIn(min = MinTouchTarget).testTag(DXF_SHARE_TAG),
                ) { Text(stringResource(R.string.export_share)) }
            }
            when (state.result) {
                ExportResult.SAVED -> Text(stringResource(R.string.export_saved), modifier = Modifier.testTag(DXF_RESULT_TAG))
                ExportResult.FAILED -> Text(
                    stringResource(R.string.export_failed),
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.testTag(DXF_RESULT_TAG),
                ) // RF-19
                null -> Unit
            }
            state.summary?.let { summary ->
                Text(
                    stringResource(R.string.dxf_export_done, summary.points, summary.lines, summary.polygons, summary.layers),
                    modifier = Modifier.testTag(DXF_SUMMARY_TAG),
                ) // RF-20
                if (summary.pointsWithoutHeight > 0 || summary.verticesWithoutHeight > 0) {
                    Text(
                        stringResource(R.string.dxf_export_no_height, summary.pointsWithoutHeight, summary.verticesWithoutHeight),
                        modifier = Modifier.testTag(DXF_NO_HEIGHT_TAG),
                    ) // RF-9
                }
                if (summary.pointsWithoutPosition > 0) {
                    Text(
                        stringResource(R.string.dxf_export_no_position, summary.pointsWithoutPosition),
                        modifier = Modifier.testTag(DXF_NO_POSITION_TAG),
                    )
                }
                if (summary.replacedChars > 0) {
                    Text(
                        stringResource(R.string.dxf_export_replaced, summary.replacedChars),
                        modifier = Modifier.testTag(DXF_REPLACED_TAG),
                    ) // RF-15
                }
            }
        }
    }
}

/** Una casilla de etiqueta de 56 dp de alto en toda la fila, que se marca tocando el texto o la casilla (RNF-T8). */
@Composable
private fun LabelRow(labelRes: Int, label: DxfLabel, tag: String, state: DxfExportUiState, actions: DxfExportActions) {
    val checked = label in state.options.labels
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = MinTouchTarget).testTag(tag).toggleable(
            value = checked,
            role = Role.Checkbox,
            onValueChange = { actions.onLabel(label, it) },
        ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Checkbox(checked = checked, onCheckedChange = null)
        Text(stringResource(labelRes))
    }
}

private fun unitName(unit: DxfUnit): Int = when (unit) {
    DxfUnit.METRE -> R.string.dxf_unit_metre
    DxfUnit.INTERNATIONAL_FOOT -> R.string.dxf_unit_foot
    DxfUnit.US_SURVEY_FOOT -> R.string.dxf_unit_us_foot
    DxfUnit.DEGREE -> R.string.dxf_unit_degree
}
