package es.topophone.app.ui.gpkg

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import es.topophone.app.R
import es.topophone.app.ui.SectionScaffold
import es.topophone.core.designsystem.component.MinTouchTarget
import es.topophone.core.model.cad.GpkgLayerKind
import es.topophone.core.model.preferences.ThemeState
import java.text.NumberFormat

const val GPKG_LOADING_TAG = "gpkg_loading"
const val GPKG_PROBLEM_TAG = "gpkg_problem"
const val GPKG_LAYERS_TAG = "gpkg_layers"
const val GPKG_LEFT_OUT_TAG = "gpkg_left_out"
const val GPKG_NO_LAYER_TAG = "gpkg_no_layer"
const val GPKG_IMPORT_BUTTON_TAG = "gpkg_import_button"

fun gpkgLayerTag(table: String) = "gpkg_layer_$table"

/** Acciones de la pantalla de importar un GeoPackage (S048). */
class GpkgImportActions(val onMarked: (table: String, marked: Boolean) -> Unit = { _, _ -> }, val onImport: () -> Unit = {})

/**
 * La importación de un GeoPackage a un proyecto (S048): el fichero (o por qué no se abre) y la lista de sus capas con geometría para
 * marcar cuáles se importan (RF-2 a RF-7).
 */
@Composable
fun GpkgImportScreen(
    themeState: ThemeState,
    onToggleSun: () -> Unit,
    state: GpkgImportUiState,
    actions: GpkgImportActions,
    onBack: () -> Unit,
) {
    SectionScaffold(
        title = stringResource(R.string.gpkg_import_title),
        themeState = themeState,
        onToggleSun = onToggleSun,
        onNavigateBack = onBack,
    ) { padding ->
        when (state.stage) {
            GpkgImportStage.LOADING -> Column(
                modifier = Modifier.padding(padding).padding(16.dp).testTag(GPKG_LOADING_TAG),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                CircularProgressIndicator()
                Text(stringResource(R.string.gpkg_import_loading))
            }
            GpkgImportStage.PROBLEM -> Column(
                modifier = Modifier.padding(padding).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(state.fileName, style = MaterialTheme.typography.titleMedium)
                Text(
                    problemText(state.problem),
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.testTag(GPKG_PROBLEM_TAG),
                ) // RF-3, RF-4
                Button(onClick = onBack, modifier = Modifier.heightIn(min = MinTouchTarget)) {
                    Text(stringResource(R.string.gpkg_import_close))
                }
            }
            else -> Column(
                modifier = Modifier.padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                ConfigSections(state, actions)
            }
        }
    }
}

@Composable
private fun problemText(problem: GpkgFileProblem?): String = when (problem) {
    GpkgFileProblem.EMPTY -> stringResource(R.string.gpkg_problem_empty)
    GpkgFileProblem.NOT_GEOPACKAGE -> stringResource(R.string.gpkg_problem_not_gpkg)
    GpkgFileProblem.DAMAGED -> stringResource(R.string.gpkg_problem_damaged)
    GpkgFileProblem.NO_LAYERS -> stringResource(R.string.gpkg_problem_no_layers)
    GpkgFileProblem.TOO_BIG -> stringResource(R.string.gpkg_problem_too_big)
    GpkgFileProblem.NO_SPACE -> stringResource(R.string.gpkg_problem_no_space)
    GpkgFileProblem.UNREADABLE, null -> stringResource(R.string.gpkg_problem_unreadable)
}

@Composable
private fun ConfigSections(state: GpkgImportUiState, actions: GpkgImportActions) {
    Text(stringResource(R.string.gpkg_section_file), style = MaterialTheme.typography.titleSmall)
    Text(state.fileName, style = MaterialTheme.typography.titleMedium)
    Text(pluralStringResource(R.plurals.gpkg_layer_count, state.layers.size, state.layers.size))
    if (state.leftOutTables.isNotEmpty()) {
        Text(
            pluralStringResource(
                R.plurals.gpkg_left_out,
                state.leftOutTables.size,
                state.leftOutTables.size,
                state.leftOutTables.joinToString(", "),
            ),
            modifier = Modifier.testTag(GPKG_LEFT_OUT_TAG),
        ) // RF-6
    }
    Text(stringResource(R.string.gpkg_section_layers), style = MaterialTheme.typography.titleSmall)
    Column(modifier = Modifier.testTag(GPKG_LAYERS_TAG), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        state.layers.forEach { row -> LayerRow(row, actions) }
    }
    if (state.markedLayers.isEmpty()) {
        Text(
            stringResource(R.string.gpkg_no_layer_marked),
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.testTag(GPKG_NO_LAYER_TAG),
        )
    }
    Button(
        onClick = actions.onImport,
        enabled = state.canImport,
        modifier = Modifier.fillMaxWidth().heightIn(min = MinTouchTarget).testTag(GPKG_IMPORT_BUTTON_TAG),
    ) { Text(stringResource(R.string.gpkg_import_button)) }
}

@Composable
private fun LayerRow(row: GpkgLayerRow, actions: GpkgImportActions) {
    val info = row.info
    val kind = stringResource(
        when (info.kind) {
            GpkgLayerKind.POINTS -> R.string.gpkg_kind_points
            GpkgLayerKind.LINES -> R.string.gpkg_kind_lines
            GpkgLayerKind.POLYGONS -> R.string.gpkg_kind_polygons
            GpkgLayerKind.MIXED -> R.string.gpkg_kind_mixed
        },
    )
    val count = pluralStringResource(
        R.plurals.gpkg_object_count,
        info.featureCount.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
        NumberFormat.getIntegerInstance().format(info.featureCount),
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = MinTouchTarget)
            .toggleable(value = row.marked, role = Role.Checkbox, onValueChange = { actions.onMarked(info.table, it) })
            .testTag(gpkgLayerTag(info.table)),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Checkbox(checked = row.marked, onCheckedChange = null)
        Column(modifier = Modifier.weight(1f)) {
            Text(info.table, style = MaterialTheme.typography.bodyLarge)
            Text(stringResource(R.string.gpkg_layer_detail, kind, count), style = MaterialTheme.typography.bodySmall)
        }
    }
}
