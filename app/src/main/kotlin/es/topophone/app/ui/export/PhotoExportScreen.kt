package es.topophone.app.ui.export

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
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
import es.topophone.app.ui.projects.ConfirmDialog
import es.topophone.app.ui.projects.SurveyNavigation
import es.topophone.app.ui.receiver.uiLocale
import es.topophone.core.designsystem.component.MinTouchTarget
import es.topophone.core.formats.csv.ExportFileName
import es.topophone.core.model.preferences.ThemeState
import java.io.File
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.launch

const val PHOTO_COUNT_TAG = "photo_count"
const val PHOTO_HINT_TAG = "photo_hint"
const val PHOTO_FILE_NAME_TAG = "photo_file_name"
const val PHOTO_SAVE_TAG = "photo_save"
const val PHOTO_SHARE_TAG = "photo_share"
const val PHOTO_RESULT_TAG = "photo_result"
const val PHOTO_NO_SPACE_TAG = "photo_no_space"
const val PHOTO_SUMMARY_TAG = "photo_summary"
const val PHOTO_SUMMARY_SIZE_TAG = "photo_summary_size"
const val PHOTO_OMITTED_TAG = "photo_omitted"

/** El tipo MIME de un ZIP. */
private const val ZIP_MIME = "application/zip"

/** Acciones de la pantalla de exportar las fotos; guardar y compartir reciben el nombre propuesto (S056/RF-12). */
class PhotoExportActions(val onSave: (fileName: String) -> Unit = {}, val onShare: (fileName: String) -> Unit = {})

@Composable
fun PhotoExportRoute(
    projectId: String,
    selection: Boolean,
    themeState: ThemeState,
    onToggleSun: () -> Unit,
    navigation: SurveyNavigation,
    viewModel: PhotoExportViewModel = hiltViewModel(),
) {
    LaunchedEffect(projectId, selection) { viewModel.start(projectId, selection) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val workDir = remember { File(context.cacheDir, "photos-work") }
    // S056/RF-12: el selector de ficheros del móvil; si el usuario cancela, no se escribe nada.
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(ZIP_MIME)) { uri ->
        if (uri != null) {
            scope.launch {
                val ok = writeToUri(context.contentResolver, uri) { viewModel.writeTo(it, workDir) }
                viewModel.report(if (ok) ExportResult.SAVED else ExportResult.FAILED)
            }
        }
    }
    PhotoExportScreen(
        themeState,
        onToggleSun,
        state,
        PhotoExportActions(
            onSave = { name ->
                viewModel.report(null)
                save.launch(name)
            },
            onShare = { name ->
                viewModel.report(null)
                scope.launch {
                    // S056/RF-12: desde la caché de la app, con el FileProvider.
                    val file = ExportFiles.prepare(context.cacheDir, name, System.currentTimeMillis())
                    if (viewModel.writeFile(file)) {
                        val chooser = R.string.photo_share_chooser
                        context.startActivity(shareExportIntent(context, file, mimeType = ZIP_MIME, chooserTitle = chooser))
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
 * Exportar las fotos (S056): cuántas fotos de cuántos puntos van y lo que ocupan, el nombre propuesto, «Guardar» y «Compartir» (con
 * confirmación si el ZIP pasa de 1 GB) y, tras exportar, el resumen con las fotos y los puntos escritos, las fotos omitidas y el peso del ZIP.
 */
@Composable
fun PhotoExportScreen(
    themeState: ThemeState,
    onToggleSun: () -> Unit,
    state: PhotoExportUiState,
    actions: PhotoExportActions,
    onBack: () -> Unit,
) {
    SectionScaffold(
        title = stringResource(R.string.photo_export_title),
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
                if (state.canExport) {
                    stringResource(
                        R.string.photo_export_count,
                        pluralStringResource(R.plurals.photo_export_photos, state.photoCount, state.photoCount),
                        pluralStringResource(R.plurals.photo_export_of_points, state.pointCount, state.pointCount),
                        sizeText(state.photoBytes, locale),
                    )
                } else {
                    stringResource(R.string.photo_export_none)
                },
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.testTag(PHOTO_COUNT_TAG),
            ) // S056/RF-3, RF-8
            Text(
                stringResource(R.string.photo_export_hint),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.testTag(PHOTO_HINT_TAG),
            ) // S056/RF-4, RF-6
            val opened = remember { Instant.now() }
            val fileName = ExportFileName.of(state.projectName, "fotos", opened, ZoneId.systemDefault(), "zip") // S056/RF-12
            if (state.canExport) { // sin fotos no se crea ningún ZIP (RF-8), así que tampoco se propone nombre
                Text(
                    stringResource(R.string.export_file_name, fileName),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.testTag(PHOTO_FILE_NAME_TAG),
                )
            }
            // S056/RF-10: un ZIP de más de 1 GB se avisa antes de escribirlo y se puede continuar o cancelar.
            fun ask(action: () -> Unit) {
                if (state.needsSizeConfirmation) confirm = action else action()
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    onClick = { ask { actions.onSave(fileName) } },
                    enabled = state.canExport,
                    modifier = Modifier.weight(1f).heightIn(min = MinTouchTarget).testTag(PHOTO_SAVE_TAG),
                ) { Text(stringResource(R.string.export_save)) } // S056/RF-12
                OutlinedButton(
                    onClick = { ask { actions.onShare(fileName) } },
                    enabled = state.canExport,
                    modifier = Modifier.weight(1f).heightIn(min = MinTouchTarget).testTag(PHOTO_SHARE_TAG),
                ) { Text(stringResource(R.string.export_share)) }
            }
            when {
                state.noSpace -> Text(
                    stringResource(R.string.photo_export_no_space, sizeText(state.estimatedBytes, locale)),
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.testTag(PHOTO_NO_SPACE_TAG),
                ) // S056/RF-11
                state.result == ExportResult.SAVED ->
                    Text(stringResource(R.string.export_saved), modifier = Modifier.testTag(PHOTO_RESULT_TAG))
                state.result == ExportResult.FAILED -> Text(
                    stringResource(R.string.export_failed),
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.testTag(PHOTO_RESULT_TAG),
                ) // S056/RF-11
            }
            state.report?.let { report -> Summary(report) }
        }
        confirm?.let { action ->
            ConfirmDialog(
                message = stringResource(R.string.photo_export_large_message, sizeText(state.estimatedBytes, locale)),
                confirm = stringResource(R.string.photo_export_large_continue),
                onConfirm = {
                    confirm = null
                    action()
                },
                onDismiss = { confirm = null },
            )
        }
    }
}

/** El resumen de la última exportación (S056/RF-13), en texto para que TalkBack lo lea. */
@Composable
private fun Summary(report: PhotoExportReport) {
    val locale = uiLocale()
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (report.photos > 0) {
            Text(
                stringResource(
                    R.string.photo_export_done,
                    pluralStringResource(R.plurals.photo_export_done_photos, report.photos, report.photos),
                    pluralStringResource(R.plurals.photo_export_of_points, report.points, report.points),
                ),
                modifier = Modifier.testTag(PHOTO_SUMMARY_TAG),
            )
            Text(
                stringResource(R.string.photo_export_zip_size, sizeText(report.zipBytes, locale)),
                modifier = Modifier.testTag(PHOTO_SUMMARY_SIZE_TAG),
            )
        }
        if (report.omitted > 0) {
            Text(
                pluralStringResource(R.plurals.photo_export_omitted, report.omitted, report.omitted),
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.testTag(PHOTO_OMITTED_TAG),
            ) // S056/RF-9
        }
    }
}
