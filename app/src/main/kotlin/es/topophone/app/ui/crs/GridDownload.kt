package es.topophone.app.ui.crs

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import es.topophone.app.R
import es.topophone.core.designsystem.component.MinTouchTarget
import es.topophone.core.geodesy.catalog.GridEntry
import es.topophone.data.grids.DownloadResult
import es.topophone.data.grids.GridDownloader
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

const val GRID_DOWNLOAD_CONFIRM_TAG = "grid_download_confirm"
const val GRID_DOWNLOAD_CANCEL_TAG = "grid_download_cancel"
const val GRID_DOWNLOAD_PROGRESS_TAG = "grid_download_progress"
const val GRID_DOWNLOAD_RESULT_TAG = "grid_download_result"
const val GRID_DOWNLOAD_LICENSE_WARNING_TAG = "grid_download_license_warning"

/** En qué punto está la descarga de una rejilla (S034/RF-19, RF-21, RF-22). */
enum class DownloadPhase { CONFIRM, DOWNLOADING, DONE, INTERRUPTED, DAMAGED, SERVER_ERROR }

data class GridDownloadUiState(
    val entry: GridEntry? = null,
    val phase: DownloadPhase = DownloadPhase.CONFIRM,
    val downloadedBytes: Long = 0,
) {
    val progress: Float get() = entry?.let { (downloadedBytes.toFloat() / it.sizeBytes).coerceIn(0f, 1f) } ?: 0f
}

/**
 * La descarga de una rejilla: pide confirmación con su tamaño y su licencia, la baja y dice cómo acabó (S034/RF-19 a RF-22). Nunca descarga sin
 * que el usuario confirme, ni con datos móviles ni con Wi-Fi.
 */
@HiltViewModel
class GridDownloadViewModel @Inject constructor(private val downloader: GridDownloader) : ViewModel() {
    private val mutableState = MutableStateFlow(GridDownloadUiState())
    val state: StateFlow<GridDownloadUiState> = mutableState.asStateFlow()

    private var job: Job? = null

    /** Abre la confirmación de [entry]; no baja nada todavía (RF-19). */
    fun ask(entry: GridEntry) {
        job?.cancel()
        mutableState.value = GridDownloadUiState(entry, DownloadPhase.CONFIRM)
    }

    /** El usuario confirma: empieza la descarga, que se puede reintentar tras un fallo (RF-22). */
    fun confirm(onDone: () -> Unit = {}) {
        val entry = mutableState.value.entry ?: return
        if (mutableState.value.phase == DownloadPhase.DOWNLOADING) return
        mutableState.value = GridDownloadUiState(entry, DownloadPhase.DOWNLOADING)
        job = viewModelScope.launch {
            val result = downloader.download(entry) { bytes -> mutableState.update { it.copy(downloadedBytes = bytes) } }
            mutableState.update {
                it.copy(
                    phase = when (result) {
                        DownloadResult.Done -> DownloadPhase.DONE
                        is DownloadResult.Interrupted -> DownloadPhase.INTERRUPTED
                        DownloadResult.Damaged -> DownloadPhase.DAMAGED
                        is DownloadResult.ServerError -> DownloadPhase.SERVER_ERROR
                    },
                )
            }
            if (result == DownloadResult.Done) onDone()
        }
    }

    /** Cancela la descarga en curso (no deja ningún fichero a medias) o cierra la confirmación. */
    fun dismiss() {
        job?.cancel()
        job = null
        mutableState.value = GridDownloadUiState()
    }
}

/** El diálogo de descarga de una rejilla (S034/RF-19 a RF-22); no se ve si no hay rejilla que descargar. */
@Composable
fun GridDownloadDialog(state: GridDownloadUiState, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val entry = state.entry ?: return
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.grid_download_title)) },
        text = {
            Column {
                Text(entry.name, style = MaterialTheme.typography.titleSmall)
                Text(stringResource(R.string.grid_download_size, formatSize(entry.sizeBytes)))
                Text(stringResource(R.string.grid_download_license, entry.license.name))
                Text(stringResource(R.string.grid_download_source, entry.source))
                if (!entry.license.open) {
                    Text(
                        stringResource(R.string.grid_download_license_unclear, entry.sourceUrl),
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.testTag(GRID_DOWNLOAD_LICENSE_WARNING_TAG),
                    )
                }
                when (state.phase) {
                    DownloadPhase.CONFIRM -> Text(stringResource(R.string.grid_download_confirm_text))
                    DownloadPhase.DOWNLOADING -> LinearProgressIndicator(
                        progress = { state.progress },
                        modifier = Modifier.fillMaxWidth().testTag(GRID_DOWNLOAD_PROGRESS_TAG),
                    )
                    else -> Text(
                        stringResource(resultText(state.phase)),
                        color = if (state.phase ==
                            DownloadPhase.DONE
                        ) {
                            MaterialTheme.colorScheme.onSurface
                        } else {
                            MaterialTheme.colorScheme.error
                        },
                        modifier = Modifier.testTag(GRID_DOWNLOAD_RESULT_TAG),
                    )
                }
            }
        },
        confirmButton = {
            if (state.phase != DownloadPhase.DOWNLOADING && state.phase != DownloadPhase.DONE) {
                TextButton(onClick = onConfirm, modifier = Modifier.heightIn(min = MinTouchTarget).testTag(GRID_DOWNLOAD_CONFIRM_TAG)) {
                    Text(
                        stringResource(
                            if (state.phase ==
                                DownloadPhase.CONFIRM
                            ) {
                                R.string.grid_download_start
                            } else {
                                R.string.grid_download_retry
                            },
                        ),
                    )
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = MinTouchTarget).testTag(GRID_DOWNLOAD_CANCEL_TAG)) {
                Text(stringResource(if (state.phase == DownloadPhase.DONE) R.string.grid_download_close else R.string.grid_download_cancel))
            }
        },
    )
}

private fun resultText(phase: DownloadPhase): Int = when (phase) {
    DownloadPhase.DONE -> R.string.grid_download_done
    DownloadPhase.INTERRUPTED -> R.string.grid_download_interrupted
    DownloadPhase.DAMAGED -> R.string.grid_download_damaged
    DownloadPhase.SERVER_ERROR -> R.string.grid_download_server_error
    else -> R.string.grid_download_confirm_text
}
