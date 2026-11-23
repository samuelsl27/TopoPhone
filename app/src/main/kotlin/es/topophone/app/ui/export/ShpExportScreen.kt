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
import es.topophone.core.formats.shp.export.ShpCoordinates
import es.topophone.core.model.preferences.ThemeState
import es.topophone.core.model.project.HeightSystem
import java.io.File
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.launch

const val SHP_COUNT_TAG = "shp_count"
const val SHP_TERRAIN_NOTICE_TAG = "shp_terrain_notice"
const val SHP_LINES_TAG = "shp_lines"
const val SHP_CRS_PROJECT_TAG = "shp_crs_project"
const val SHP_CRS_GEOGRAPHIC_TAG = "shp_crs_geographic"
const val SHP_CRS_FIXED_TAG = "shp_crs_fixed"
const val SHP_UNITS_TAG = "shp_units"
const val SHP_FILE_NAME_TAG = "shp_file_name"
const val SHP_SAVE_TAG = "shp_save"
const val SHP_SHARE_TAG = "shp_share"
const val SHP_RESULT_TAG = "shp_result"
const val SHP_SUMMARY_TAG = "shp_summary"
const val SHP_SUMMARY_LINES_TAG = "shp_summary_lines"
const val SHP_NO_POSITION_TAG = "shp_no_position"
const val SHP_NO_HEIGHT_TAG = "shp_no_height"
const val SHP_TRUNCATED_TAG = "shp_truncated"
const val SHP_RENAMED_TAG = "shp_renamed"
const val SHP_NO_PRJ_TAG = "shp_no_prj"
const val SHP_HEIGHT_SYSTEM_TAG = "shp_height_system"

/** El tipo MIME de un ZIP. */
private const val ZIP_MIME = "application/zip"

/** Acciones de la pantalla de exportar a Shapefile; guardar y compartir reciben el nombre propuesto (S055/RF-17, RF-18). */
class ShpExportActions(
    val onCoordinates: (ShpCoordinates) -> Unit = {},
    val onSave: (fileName: String) -> Unit = {},
    val onShare: (fileName: String) -> Unit = {},
)

@Composable
fun ShpExportRoute(
    projectId: String,
    selection: Boolean,
    themeState: ThemeState,
    onToggleSun: () -> Unit,
    navigation: SurveyNavigation,
    viewModel: ShpExportViewModel = hiltViewModel(),
) {
    LaunchedEffect(projectId, selection) { viewModel.start(projectId, selection) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val workDir = remember { File(context.cacheDir, "shp-work") }
    // S055/RF-18: el selector de ficheros del móvil; si el usuario cancela, no se escribe nada.
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(ZIP_MIME)) { uri ->
        if (uri != null) {
            scope.launch {
                val ok = writeToUri(context.contentResolver, uri) { viewModel.writeTo(it, workDir) }
                viewModel.report(if (ok) ExportResult.SAVED else ExportResult.FAILED)
            }
        }
    }
    ShpExportScreen(
        themeState,
        onToggleSun,
        state,
        ShpExportActions(
            onCoordinates = viewModel::setCoordinates,
            onSave = { name ->
                viewModel.report(null)
                save.launch(name)
            },
            onShare = { name ->
                viewModel.report(null)
                scope.launch {
                    // S055/RF-18: desde la caché de la app, con el FileProvider.
                    val file = ExportFiles.prepare(context.cacheDir, name, System.currentTimeMillis())
                    if (viewModel.writeFile(file)) {
                        context.startActivity(
                            shareExportIntent(context, file, mimeType = ZIP_MIME, chooserTitle = R.string.shp_share_chooser),
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
 * Exportar Shapefile (S055): cuántos puntos, líneas y polígonos van, el sistema de coordenadas (el del proyecto o las geográficas de su
 * datum, con su código; no se ofrece elegir en un proyecto en geográficas), el nombre propuesto y "Guardar" y "Compartir"; y, tras exportar,
 * el resumen con lo escrito y los avisos: puntos sin posición, vértices sin altura, textos recortados, atributos renombrados, el `.prj` que
 * falta y el sistema de alturas de la Z.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ShpExportScreen(
    themeState: ThemeState,
    onToggleSun: () -> Unit,
    state: ShpExportUiState,
    actions: ShpExportActions,
    onBack: () -> Unit,
) {
    SectionScaffold(
        title = stringResource(R.string.shp_export_title),
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
                modifier = Modifier.testTag(SHP_COUNT_TAG),
            ) // S055/RF-1, RF-19
            if (state.lineCount > 0 || state.polygonCount > 0) {
                Text(
                    linesText(state.lineCount, state.polygonCount),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.testTag(SHP_LINES_TAG),
                ) // S055/RF-3
            }
            if (state.terrain) {
                Text(
                    stringResource(R.string.terrain_export_grid_notice),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.testTag(SHP_TERRAIN_NOTICE_TAG),
                ) // S058/RF-15
            }
            Text(stringResource(R.string.shp_export_system), style = MaterialTheme.typography.labelLarge)
            if (state.canChooseCoordinates) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = state.coordinates == ShpCoordinates.PROJECTED,
                        onClick = { actions.onCoordinates(ShpCoordinates.PROJECTED) },
                        label = {
                            Text(
                                if (state.projectEpsg == 0) {
                                    stringResource(R.string.shp_export_system_project_custom)
                                } else {
                                    stringResource(R.string.shp_export_system_project, state.projectEpsg)
                                },
                            )
                        },
                        modifier = Modifier.heightIn(min = MinTouchTarget).testTag(SHP_CRS_PROJECT_TAG),
                    )
                    FilterChip(
                        selected = state.coordinates == ShpCoordinates.GEOGRAPHIC,
                        onClick = { actions.onCoordinates(ShpCoordinates.GEOGRAPHIC) },
                        label = { Text(stringResource(R.string.shp_export_system_geographic, state.geographicEpsg)) },
                        modifier = Modifier.heightIn(min = MinTouchTarget).testTag(SHP_CRS_GEOGRAPHIC_TAG),
                    )
                }
            } else {
                Text(stringResource(R.string.shp_export_system_fixed, state.epsg), modifier = Modifier.testTag(SHP_CRS_FIXED_TAG))
            } // S055/RF-12, RF-13
            Text(
                stringResource(R.string.shp_export_units),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.testTag(SHP_UNITS_TAG),
            )
            val opened = remember { Instant.now() }
            val fileName = ExportFileName.of(state.projectName, "shapefile", opened, ZoneId.systemDefault(), "zip") // S055/RF-17
            Text(
                stringResource(R.string.export_file_name, fileName),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.testTag(SHP_FILE_NAME_TAG),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    onClick = { actions.onSave(fileName) },
                    enabled = state.canExport,
                    modifier = Modifier.weight(1f).heightIn(min = MinTouchTarget).testTag(SHP_SAVE_TAG),
                ) { Text(stringResource(R.string.export_save)) } // S055/RF-18
                OutlinedButton(
                    onClick = { actions.onShare(fileName) },
                    enabled = state.canExport,
                    modifier = Modifier.weight(1f).heightIn(min = MinTouchTarget).testTag(SHP_SHARE_TAG),
                ) { Text(stringResource(R.string.export_share)) }
            }
            when (state.result) {
                ExportResult.SAVED -> Text(stringResource(R.string.export_saved), modifier = Modifier.testTag(SHP_RESULT_TAG))
                ExportResult.FAILED -> Text(
                    stringResource(R.string.export_failed),
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.testTag(SHP_RESULT_TAG),
                ) // S055/RF-20
                null -> Unit
            }
            state.summary?.let { summary -> Summary(summary) }
        }
    }
}

/** El resumen de la última exportación y sus avisos (S055/RF-22), en texto para que TalkBack lo lea. */
@Composable
private fun Summary(summary: es.topophone.core.formats.shp.export.ShpExportSummary) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            if (summary.epsg == 0) {
                pluralStringResource(R.plurals.shp_export_done_custom, summary.points, summary.points)
            } else {
                pluralStringResource(R.plurals.shp_export_done, summary.points, summary.points, summary.epsg)
            },
            modifier = Modifier.testTag(SHP_SUMMARY_TAG),
        )
        if (summary.lines > 0 || summary.polygons > 0) {
            Text(linesText(summary.lines, summary.polygons), modifier = Modifier.testTag(SHP_SUMMARY_LINES_TAG))
        }
        if (summary.pointsWithoutPosition > 0) {
            Text(
                pluralStringResource(R.plurals.shp_export_no_position, summary.pointsWithoutPosition, summary.pointsWithoutPosition),
                modifier = Modifier.testTag(SHP_NO_POSITION_TAG),
            )
        }
        if (summary.verticesWithoutHeight > 0) {
            Text(
                pluralStringResource(R.plurals.shp_export_no_height, summary.verticesWithoutHeight, summary.verticesWithoutHeight),
                modifier = Modifier.testTag(SHP_NO_HEIGHT_TAG),
            )
        }
        if (summary.truncatedTexts > 0) {
            Text(
                pluralStringResource(R.plurals.shp_export_truncated, summary.truncatedTexts, summary.truncatedTexts),
                modifier = Modifier.testTag(SHP_TRUNCATED_TAG),
            )
        }
        if (summary.renamedAttributes.isNotEmpty()) {
            val names = summary.renamedAttributes.joinToString(", ") { "${it.original} → ${it.name}" }
            Text(
                pluralStringResource(R.plurals.shp_export_renamed, summary.renamedAttributes.size, summary.renamedAttributes.size, names),
                modifier = Modifier.testTag(SHP_RENAMED_TAG),
            )
        }
        if (summary.prjMissing) {
            Text(stringResource(R.string.shp_export_no_prj), modifier = Modifier.testTag(SHP_NO_PRJ_TAG))
        }
        val heightText = if (summary.heightSystem == HeightSystem.ORTHOMETRIC) {
            R.string.shp_export_height_orthometric
        } else {
            R.string.shp_export_height_ellipsoidal
        }
        Text(
            stringResource(heightText),
            modifier = Modifier.testTag(SHP_HEIGHT_SYSTEM_TAG),
        ) // S055/RF-15: el .prj no puede declararlo
    }
}

@Composable
private fun linesText(lines: Int, polygons: Int): String = listOfNotNull(
    pluralStringResource(R.plurals.gpkg_export_lines, lines, lines).takeIf { lines > 0 },
    pluralStringResource(R.plurals.gpkg_export_polygons, polygons, polygons).takeIf { polygons > 0 },
).joinToString(" · ")
