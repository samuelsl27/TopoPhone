package es.topophone.app.ui.export

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import es.topophone.app.ui.projects.SurveyNavigation
import es.topophone.core.designsystem.component.MinTouchTarget
import es.topophone.core.formats.csv.ExportFileName
import es.topophone.core.model.preferences.ThemeState
import es.topophone.data.gpkg.GpkgExportCrs
import java.io.File
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.launch

const val GPKG_COUNT_TAG = "gpkg_count"
const val GPKG_LINES_TAG = "gpkg_lines"
const val GPKG_CRS_PROJECT_TAG = "gpkg_crs_project"
const val GPKG_CRS_GEOGRAPHIC_TAG = "gpkg_crs_geographic"
const val GPKG_CRS_FIXED_TAG = "gpkg_crs_fixed"
const val GPKG_UNITS_TAG = "gpkg_units"
const val GPKG_FILE_NAME_TAG = "gpkg_file_name"
const val GPKG_SAVE_TAG = "gpkg_save"
const val GPKG_SHARE_TAG = "gpkg_share"
const val GPKG_RESULT_TAG = "gpkg_result"
const val GPKG_SUMMARY_TAG = "gpkg_summary"
const val GPKG_NO_GEOMETRY_TAG = "gpkg_no_geometry"
const val GPKG_CUSTOM_TAG = "gpkg_custom"

/** El tipo MIME de un GeoPackage (OGC GeoPackage, apartado 1.3). */
private const val GPKG_MIME = "application/geopackage+sqlite3"

/** Acciones de la pantalla de exportar a GeoPackage; guardar y compartir reciben el nombre propuesto (S053/RF-9, RF-10). */
class GpkgExportActions(
    val onCrs: (GpkgExportCrs) -> Unit = {},
    val onSave: (fileName: String) -> Unit = {},
    val onShare: (fileName: String) -> Unit = {},
)

@Composable
fun GpkgExportRoute(
    projectId: String,
    selection: Boolean,
    themeState: ThemeState,
    onToggleSun: () -> Unit,
    navigation: SurveyNavigation,
    viewModel: GpkgExportViewModel = hiltViewModel(),
) {
    LaunchedEffect(projectId, selection) { viewModel.start(projectId, selection) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val workDir = remember { File(context.cacheDir, "gpkg-work") }
    // S053/RF-10: el selector de ficheros del móvil; si el usuario cancela, no se escribe nada.
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(GPKG_MIME)) { uri ->
        if (uri != null) {
            scope.launch {
                val ok = writeToUri(context.contentResolver, uri) { viewModel.writeTo(it, workDir) }
                viewModel.report(if (ok) ExportResult.SAVED else ExportResult.FAILED)
            }
        }
    }
    GpkgExportScreen(
        themeState,
        onToggleSun,
        state,
        GpkgExportActions(
            onCrs = viewModel::setCrs,
            onSave = { name ->
                viewModel.report(null)
                save.launch(name)
            },
            onShare = { name ->
                viewModel.report(null)
                scope.launch {
                    // S053/RF-10: desde la caché de la app, con el FileProvider.
                    val file = ExportFiles.prepare(context.cacheDir, name, System.currentTimeMillis())
                    if (viewModel.writeFile(file)) {
                        context.startActivity(
                            shareExportIntent(context, file, mimeType = GPKG_MIME, chooserTitle = R.string.gpkg_share_chooser),
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
 * Exportar GeoPackage (S053): cuántos puntos van, el sistema de coordenadas (el del proyecto o las geográficas de su datum, con su
 * código), el nombre propuesto y "Guardar" y "Compartir"; y, tras exportar, el resumen con lo que va sin geometría o con el sistema
 * completo.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun GpkgExportScreen(
    themeState: ThemeState,
    onToggleSun: () -> Unit,
    state: GpkgExportUiState,
    actions: GpkgExportActions,
    onBack: () -> Unit,
) {
    SectionScaffold(
        title = stringResource(R.string.gpkg_export_title),
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
                modifier = Modifier.testTag(GPKG_COUNT_TAG),
            ) // S053/RF-1, RF-11
            if (state.lineCount > 0 || state.polygonCount > 0) {
                // S025/RF-26: las líneas y los polígonos del proyecto van en sus propias capas.
                Text(
                    listOfNotNull(
                        pluralStringResource(R.plurals.gpkg_export_lines, state.lineCount, state.lineCount).takeIf { state.lineCount > 0 },
                        pluralStringResource(R.plurals.gpkg_export_polygons, state.polygonCount, state.polygonCount).takeIf {
                            state.polygonCount >
                                0
                        },
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.testTag(GPKG_LINES_TAG),
                )
            }
            Text(stringResource(R.string.gpkg_export_system), style = MaterialTheme.typography.labelLarge)
            if (state.canChooseCrs) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = state.crs == GpkgExportCrs.PROJECT,
                        onClick = { actions.onCrs(GpkgExportCrs.PROJECT) },
                        label = { Text(stringResource(R.string.gpkg_export_system_project, state.projectSrsId)) },
                        modifier = Modifier.heightIn(min = MinTouchTarget).testTag(GPKG_CRS_PROJECT_TAG),
                    )
                    FilterChip(
                        selected = state.crs == GpkgExportCrs.GEOGRAPHIC,
                        onClick = { actions.onCrs(GpkgExportCrs.GEOGRAPHIC) },
                        label = { Text(stringResource(R.string.gpkg_export_system_geographic, state.geographicSrsId)) },
                        modifier = Modifier.heightIn(min = MinTouchTarget).testTag(GPKG_CRS_GEOGRAPHIC_TAG),
                    )
                }
            } else {
                Text(stringResource(R.string.gpkg_export_system_fixed, state.srsId), modifier = Modifier.testTag(GPKG_CRS_FIXED_TAG))
            } // S053/RF-6, RF-7
            Text(
                stringResource(R.string.gpkg_export_units),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.testTag(GPKG_UNITS_TAG),
            )
            val opened = remember { Instant.now() }
            val fileName = ExportFileName.of(state.projectName, "geopackage", opened, ZoneId.systemDefault(), "gpkg") // S053/RF-9
            Text(
                stringResource(R.string.export_file_name, fileName),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.testTag(GPKG_FILE_NAME_TAG),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    onClick = { actions.onSave(fileName) },
                    enabled = state.canExport,
                    modifier = Modifier.weight(1f).heightIn(min = MinTouchTarget).testTag(GPKG_SAVE_TAG),
                ) { Text(stringResource(R.string.export_save)) } // S053/RF-10
                OutlinedButton(
                    onClick = { actions.onShare(fileName) },
                    enabled = state.canExport,
                    modifier = Modifier.weight(1f).heightIn(min = MinTouchTarget).testTag(GPKG_SHARE_TAG),
                ) { Text(stringResource(R.string.export_share)) }
            }
            when (state.result) {
                ExportResult.SAVED -> Text(stringResource(R.string.export_saved), modifier = Modifier.testTag(GPKG_RESULT_TAG))
                ExportResult.FAILED -> Text(
                    stringResource(R.string.export_failed),
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.testTag(GPKG_RESULT_TAG),
                ) // S053/RF-12
                null -> Unit
            }
            state.summary?.let { summary ->
                Text(
                    pluralStringResource(R.plurals.gpkg_export_done, summary.points, summary.points, summary.srsId),
                    modifier = Modifier.testTag(GPKG_SUMMARY_TAG),
                )
                if (summary.withoutGeometry > 0) {
                    Text(
                        pluralStringResource(R.plurals.gpkg_export_no_geometry, summary.withoutGeometry, summary.withoutGeometry),
                        modifier = Modifier.testTag(GPKG_NO_GEOMETRY_TAG),
                    )
                }
                if (summary.customDefinition) {
                    Text(
                        stringResource(R.string.gpkg_export_custom),
                        modifier = Modifier.testTag(GPKG_CUSTOM_TAG),
                    ) // RF-8
                }
            }
        }
    }
}
