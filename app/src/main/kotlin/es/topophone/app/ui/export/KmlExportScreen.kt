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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
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
import es.topophone.app.ui.points.fileSizeText
import es.topophone.app.ui.projects.ConfirmDialog
import es.topophone.app.ui.projects.SurveyNavigation
import es.topophone.app.ui.receiver.uiLocale
import es.topophone.core.designsystem.component.MinTouchTarget
import es.topophone.core.formats.csv.ExportFileName
import es.topophone.core.formats.kml.export.KmlFormat
import es.topophone.core.model.preferences.ThemeState
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import kotlinx.coroutines.launch

const val KML_COUNT_TAG = "kml_count"
const val KML_LINES_TAG = "kml_lines"
const val KML_FORMAT_KML_TAG = "kml_format_kml"
const val KML_FORMAT_KMZ_TAG = "kml_format_kmz"
const val KML_HEIGHT_TAG = "kml_height"
const val KML_PHOTOS_TAG = "kml_photos"
const val KML_PHOTOS_INFO_TAG = "kml_photos_info"
const val KML_DATUM_TAG = "kml_datum"
const val KML_FILE_NAME_TAG = "kml_file_name"
const val KML_SAVE_TAG = "kml_save"
const val KML_SHARE_TAG = "kml_share"
const val KML_RESULT_TAG = "kml_result"
const val KML_NO_SPACE_TAG = "kml_no_space"
const val KML_SUMMARY_TAG = "kml_summary"
const val KML_SUMMARY_LINES_TAG = "kml_summary_lines"
const val KML_SUMMARY_FOLDERS_TAG = "kml_summary_folders"
const val KML_NO_POSITION_TAG = "kml_no_position"
const val KML_NO_HEIGHT_TAG = "kml_no_height"
const val KML_PHOTOS_DONE_TAG = "kml_photos_done"
const val KML_PHOTOS_OMITTED_TAG = "kml_photos_omitted"
const val KML_SUMMARY_DATUM_TAG = "kml_summary_datum"

/** El tipo MIME de un KML y el de un KMZ. */
private const val KML_MIME = "application/vnd.google-earth.kml+xml"
private const val KMZ_MIME = "application/vnd.google-earth.kmz"

private const val GIGABYTE = 1_000_000_000L

/** Acciones de la pantalla de exportar a KML o KMZ; guardar y compartir reciben el nombre propuesto (S031/RF-20, RF-26, RF-28, RF-31). */
class KmlExportActions(
    val onFormat: (KmlFormat) -> Unit = {},
    val onIncludeHeight: (Boolean) -> Unit = {},
    val onIncludePhotos: (Boolean) -> Unit = {},
    val onSave: (fileName: String) -> Unit = {},
    val onShare: (fileName: String) -> Unit = {},
)

@Composable
fun KmlExportRoute(
    projectId: String,
    selection: Boolean,
    themeState: ThemeState,
    onToggleSun: () -> Unit,
    navigation: SurveyNavigation,
    viewModel: KmlExportViewModel = hiltViewModel(),
) {
    LaunchedEffect(projectId, selection) { viewModel.start(projectId, selection) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val workDir = remember { File(context.cacheDir, "kml-work") }
    val mime = if (state.format == KmlFormat.KMZ) KMZ_MIME else KML_MIME
    // S031/RF-31: el selector de ficheros del móvil; si el usuario cancela, no se escribe nada.
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(mime)) { uri ->
        if (uri != null) {
            scope.launch {
                val ok = writeToUri(context.contentResolver, uri) { viewModel.writeTo(it, workDir) }
                viewModel.report(if (ok) ExportResult.SAVED else ExportResult.FAILED)
            }
        }
    }
    KmlExportScreen(
        themeState,
        onToggleSun,
        state,
        KmlExportActions(
            onFormat = viewModel::setFormat,
            onIncludeHeight = viewModel::setIncludeHeight,
            onIncludePhotos = viewModel::setIncludePhotos,
            onSave = { name ->
                viewModel.report(null)
                save.launch(name)
            },
            onShare = { name ->
                viewModel.report(null)
                scope.launch {
                    // S031/RF-31: desde la caché de la app, con el FileProvider.
                    val file = ExportFiles.prepare(context.cacheDir, name, System.currentTimeMillis())
                    if (viewModel.writeFile(file)) {
                        context.startActivity(shareExportIntent(context, file, mimeType = mime, chooserTitle = R.string.kml_share_chooser))
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
 * Exportar KML o KMZ (S031): cuántos puntos, líneas y polígonos van, el formato, «Incluir altura» y, con KMZ, «Incluir fotos» con lo que
 * ocupan; el aviso de datum, el nombre propuesto y «Guardar» y «Compartir» (con confirmación si el KMZ pasa de 1 GB); y, tras exportar, el
 * resumen con lo escrito, los puntos sin posición, las marcas sin altura y las fotos incluidas y omitidas.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun KmlExportScreen(
    themeState: ThemeState,
    onToggleSun: () -> Unit,
    state: KmlExportUiState,
    actions: KmlExportActions,
    onBack: () -> Unit,
) {
    SectionScaffold(
        title = stringResource(R.string.kml_export_title),
        themeState = themeState,
        onToggleSun = onToggleSun,
        onNavigateBack = onBack,
    ) { padding ->
        if (!state.loaded) return@SectionScaffold
        var confirm by remember { mutableStateOf<(() -> Unit)?>(null) }
        val locale = uiLocale()
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
                modifier = Modifier.testTag(KML_COUNT_TAG),
            ) // S031/RF-20, RF-31
            if (state.lineCount > 0 || state.polygonCount > 0) {
                Text(
                    linesText(state.lineCount, state.polygonCount),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.testTag(KML_LINES_TAG),
                ) // S031/RF-22
            }
            Text(stringResource(R.string.kml_export_format), style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = state.format == KmlFormat.KML,
                    onClick = { actions.onFormat(KmlFormat.KML) },
                    label = { Text(stringResource(R.string.kml_export_format_kml)) },
                    modifier = Modifier.heightIn(min = MinTouchTarget).testTag(KML_FORMAT_KML_TAG),
                )
                FilterChip(
                    selected = state.format == KmlFormat.KMZ,
                    onClick = { actions.onFormat(KmlFormat.KMZ) },
                    label = { Text(stringResource(R.string.kml_export_format_kmz)) },
                    modifier = Modifier.heightIn(min = MinTouchTarget).testTag(KML_FORMAT_KMZ_TAG),
                )
            } // S031/RF-20
            CheckRow(R.string.kml_export_height, state.includeHeight, KML_HEIGHT_TAG, actions.onIncludeHeight) // S031/RF-26
            Text(stringResource(R.string.kml_export_height_hint), style = MaterialTheme.typography.bodySmall)
            if (state.format == KmlFormat.KMZ) {
                CheckRow(R.string.kml_export_photos, state.includePhotos, KML_PHOTOS_TAG, actions.onIncludePhotos) // S031/RF-28
                Text(
                    if (state.photoCount == 0) {
                        stringResource(R.string.kml_export_photos_none)
                    } else {
                        pluralStringResource(
                            R.plurals.kml_export_photos_info,
                            state.photoCount,
                            state.photoCount,
                            sizeText(state.photoBytes, locale),
                        )
                    },
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.testTag(KML_PHOTOS_INFO_TAG),
                )
            }
            Text(
                stringResource(R.string.kml_export_datum),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.testTag(KML_DATUM_TAG),
            ) // S031/RF-24
            val opened = remember { Instant.now() }
            val extension = if (state.format == KmlFormat.KMZ) "kmz" else "kml"
            val fileName = ExportFileName.of(state.projectName, "kml", opened, ZoneId.systemDefault(), extension) // S031/RF-31
            Text(
                stringResource(R.string.export_file_name, fileName),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.testTag(KML_FILE_NAME_TAG),
            )
            // S031/RF-30: un KMZ de más de 1 GB se avisa antes de escribirlo y se puede continuar o cancelar.
            fun ask(action: () -> Unit) {
                if (state.needsSizeConfirmation) confirm = action else action()
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    onClick = { ask { actions.onSave(fileName) } },
                    enabled = state.canExport,
                    modifier = Modifier.weight(1f).heightIn(min = MinTouchTarget).testTag(KML_SAVE_TAG),
                ) { Text(stringResource(R.string.export_save)) } // S031/RF-31
                OutlinedButton(
                    onClick = { ask { actions.onShare(fileName) } },
                    enabled = state.canExport,
                    modifier = Modifier.weight(1f).heightIn(min = MinTouchTarget).testTag(KML_SHARE_TAG),
                ) { Text(stringResource(R.string.export_share)) }
            }
            when {
                state.noSpace -> Text(
                    stringResource(R.string.kml_export_no_space, sizeText(state.estimatedBytes, locale)),
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.testTag(KML_NO_SPACE_TAG),
                ) // S031/RF-30
                state.result == ExportResult.SAVED ->
                    Text(stringResource(R.string.export_saved), modifier = Modifier.testTag(KML_RESULT_TAG))
                state.result == ExportResult.FAILED -> Text(
                    stringResource(R.string.export_failed),
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.testTag(KML_RESULT_TAG),
                ) // S031/RF-31
            }
            state.report?.let { report -> Summary(report) }
        }
        confirm?.let { action ->
            ConfirmDialog(
                message = stringResource(R.string.kml_export_large_message, sizeText(state.estimatedBytes, locale)),
                confirm = stringResource(R.string.kml_export_large_continue),
                onConfirm = {
                    confirm = null
                    action()
                },
                onDismiss = { confirm = null },
            )
        }
    }
}

/** Una casilla con su texto de 56 dp de alto en toda la fila, que se marca tocando el texto o la casilla (RNF-T8). */
@Composable
private fun CheckRow(labelRes: Int, checked: Boolean, tag: String, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = MinTouchTarget).testTag(tag).toggleable(
            value = checked,
            role = Role.Checkbox,
            onValueChange = onChange,
        ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Checkbox(checked = checked, onCheckedChange = null)
        Text(stringResource(labelRes))
    }
}

/** El resumen de la última exportación y sus avisos (S031/RF-32), en texto para que TalkBack lo lea. */
@Composable
private fun Summary(report: KmlExportReport) {
    val summary = report.summary
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            pluralStringResource(R.plurals.kml_export_done, summary.points, summary.points),
            modifier = Modifier.testTag(KML_SUMMARY_TAG),
        )
        if (summary.lines > 0 || summary.polygons > 0) {
            Text(linesText(summary.lines, summary.polygons), modifier = Modifier.testTag(KML_SUMMARY_LINES_TAG))
        }
        Text(
            pluralStringResource(R.plurals.kml_export_folders, summary.folders, summary.folders),
            modifier = Modifier.testTag(KML_SUMMARY_FOLDERS_TAG),
        )
        if (summary.pointsWithoutPosition > 0) {
            Text(
                pluralStringResource(R.plurals.kml_export_no_position, summary.pointsWithoutPosition, summary.pointsWithoutPosition),
                modifier = Modifier.testTag(KML_NO_POSITION_TAG),
            )
        }
        if (summary.marksWithoutHeight > 0) {
            Text(
                pluralStringResource(R.plurals.kml_export_no_height, summary.marksWithoutHeight, summary.marksWithoutHeight),
                modifier = Modifier.testTag(KML_NO_HEIGHT_TAG),
            )
        }
        if (report.withPhotos) {
            Text(
                pluralStringResource(R.plurals.kml_export_photos_done, report.photosIncluded, report.photosIncluded),
                modifier = Modifier.testTag(KML_PHOTOS_DONE_TAG),
            )
            if (report.photosOmitted > 0) {
                Text(
                    pluralStringResource(R.plurals.kml_export_photos_omitted, report.photosOmitted, report.photosOmitted),
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.testTag(KML_PHOTOS_OMITTED_TAG),
                )
            }
        }
        Text(stringResource(R.string.kml_result_datum), modifier = Modifier.testTag(KML_SUMMARY_DATUM_TAG)) // S031/RF-32
    }
}

@Composable
private fun linesText(lines: Int, polygons: Int): String = listOfNotNull(
    pluralStringResource(R.plurals.gpkg_export_lines, lines, lines).takeIf { lines > 0 },
    pluralStringResource(R.plurals.gpkg_export_polygons, polygons, polygons).takeIf { polygons > 0 },
).joinToString(" · ")

/** El tamaño de un fichero: «850 kB», «1,2 MB» o, desde 1 GB, «1,1 GB». */
private fun sizeText(bytes: Long, locale: Locale): String =
    if (bytes >= GIGABYTE) String.format(locale, "%.1f GB", bytes / GIGABYTE.toDouble()) else fileSizeText(bytes, locale)
