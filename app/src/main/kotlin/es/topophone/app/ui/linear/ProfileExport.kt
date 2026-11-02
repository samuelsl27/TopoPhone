package es.topophone.app.ui.linear

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import es.topophone.app.R
import es.topophone.app.ui.export.ExportFiles
import es.topophone.app.ui.export.ExportResult
import es.topophone.app.ui.export.shareExportIntent
import es.topophone.app.ui.export.writeToUri
import es.topophone.core.designsystem.component.MinTouchTarget
import es.topophone.core.formats.csv.ExportFileName
import java.io.OutputStream
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

const val PROFILE_EXPORT_FILE_TAG = "profile_export_file"
const val PROFILE_EXPORT_SAVE_TAG = "profile_export_save"
const val PROFILE_EXPORT_SHARE_TAG = "profile_export_share"
const val PROFILE_EXPORT_RESULT_TAG = "profile_export_result"

private const val CSV_MIME = "text/csv"

/**
 * «Guardar» y «Compartir» el CSV de un perfil (S052/RF-15, RF-22; RNF-T5): el nombre propuesto es `<proyecto>_<etiqueta>_<AAAAMMDD-HHMM>.csv`,
 * «Guardar» abre el selector de ficheros y «Compartir» lo escribe en la caché y lo manda con el FileProvider, como el resto de
 * exportaciones. [write] escribe el CSV en el flujo y dice si pudo. Si el usuario cancela el selector no se escribe nada.
 */
@Composable
fun ProfileExportButtons(
    projectName: String,
    label: String,
    enabled: Boolean,
    write: suspend (OutputStream) -> Boolean,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var result by remember { mutableStateOf<ExportResult?>(null) }
    val opened = remember { Instant.now() }
    val fileName = ExportFileName.of(projectName, label, opened, ZoneId.systemDefault())
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(CSV_MIME)) { uri ->
        if (uri != null) {
            scope.launch {
                val ok = writeToUri(context.contentResolver, uri, write)
                result = if (ok) ExportResult.SAVED else ExportResult.FAILED
            }
        }
    }
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            stringResource(R.string.export_file_name, fileName),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.testTag(PROFILE_EXPORT_FILE_TAG),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = {
                    result = null
                    save.launch(fileName)
                },
                enabled = enabled,
                modifier = Modifier.weight(1f).heightIn(min = MinTouchTarget).testTag(PROFILE_EXPORT_SAVE_TAG),
            ) { Text(stringResource(R.string.export_save)) }
            OutlinedButton(
                onClick = {
                    result = null
                    scope.launch {
                        // S012/RF-18: desde la caché de la app, con el FileProvider.
                        val file = ExportFiles.prepare(context.cacheDir, fileName, System.currentTimeMillis())
                        val ok = withContext(Dispatchers.IO) { file.outputStream() }.use { write(it) }
                        if (ok) context.startActivity(shareExportIntent(context, file)) else result = ExportResult.FAILED
                    }
                },
                enabled = enabled,
                modifier = Modifier.weight(1f).heightIn(min = MinTouchTarget).testTag(PROFILE_EXPORT_SHARE_TAG),
            ) { Text(stringResource(R.string.export_share)) }
        }
        when (result) {
            ExportResult.SAVED -> Text(
                stringResource(R.string.export_saved),
                modifier = Modifier.testTag(PROFILE_EXPORT_RESULT_TAG).semantics { liveRegion = LiveRegionMode.Polite },
            )
            ExportResult.FAILED -> Text(
                stringResource(R.string.export_failed),
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.testTag(PROFILE_EXPORT_RESULT_TAG).semantics { liveRegion = LiveRegionMode.Polite },
            )
            null -> Unit
        }
    }
}
