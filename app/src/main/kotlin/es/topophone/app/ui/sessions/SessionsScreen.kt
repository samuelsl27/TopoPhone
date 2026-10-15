package es.topophone.app.ui.sessions

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import es.topophone.app.R
import es.topophone.app.ui.SectionScaffold
import es.topophone.app.ui.receiver.shareSession
import es.topophone.app.ui.receiver.uiLocale
import es.topophone.core.designsystem.component.MinTouchTarget
import es.topophone.core.model.preferences.ThemeState
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

const val SESSIONS_LIST_TAG = "sessions_list"
const val SHARE_SESSION_TAG = "share_session"
const val IMPORT_SESSION_TAG = "sessions_import"
const val CONFIRM_DELETE_TAG = "sessions_confirm_delete"
const val CANCEL_DELETE_TAG = "sessions_cancel_delete"
const val CONFIRM_REPLAY_TAG = "sessions_confirm_replay"
const val CANCEL_REPLAY_TAG = "sessions_cancel_replay"

fun playSessionTag(id: String) = "session_play_$id"

fun sessionRowTag(id: String) = "session_$id"

fun shareSessionTag(id: String) = "session_share_$id"

fun deleteSessionTag(id: String) = "session_delete_$id"

/** Acciones de la lista de sesiones. */
class SessionsActions(
    val onShare: (String) -> Unit = {},
    val onDelete: (String) -> Unit = {},
    val onImport: () -> Unit = {},
    val onPlay: (String) -> Unit = {},
)

/** Entrada de la pantalla Sesiones: une el ViewModel con compartir por FileProvider. */
@Composable
fun SessionsSection(
    themeState: ThemeState,
    onToggleSun: () -> Unit,
    onNavigateBack: () -> Unit,
    viewModel: SessionsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    // S003/RF-33: cualquier tipo de fichero; se valida por el contenido (RNF-T5).
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val (name, size) = describe(context.contentResolver, uri)
            viewModel.import(name, size) { context.contentResolver.openInputStream(uri) }
        }
    }
    LifecycleResumeEffect(Unit) {
        viewModel.refresh()
        onPauseOrDispose {}
    }
    SessionsScreen(
        themeState = themeState,
        onToggleSun = onToggleSun,
        state = state,
        actions = SessionsActions(
            onShare = { id -> viewModel.filesOf(id).takeIf { it.isNotEmpty() }?.let { shareSession(context, it) } },
            onDelete = viewModel::delete,
            onImport = { importLauncher.launch(arrayOf("*/*")) },
            onPlay = { id -> if (viewModel.play(id)) onNavigateBack() }, // se ve en la tarjeta Conexión de Receptor
        ),
        onNavigateBack = onNavigateBack,
    )
}

/** Sesiones grabadas e importadas, de la más reciente a la más antigua (S003/RF-29 a RF-31). */
@Composable
fun SessionsScreen(
    themeState: ThemeState,
    onToggleSun: () -> Unit,
    state: SessionsUiState,
    actions: SessionsActions,
    onNavigateBack: () -> Unit,
) {
    var pendingDelete by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingReplay by rememberSaveable { mutableStateOf<String?>(null) }
    SectionScaffold(
        title = stringResource(R.string.sessions_title),
        themeState = themeState,
        onToggleSun = onToggleSun,
        onNavigateBack = onNavigateBack,
    ) { padding ->
        Column(
            modifier = Modifier.padding(padding).verticalScroll(rememberScrollState()).padding(16.dp).testTag(SESSIONS_LIST_TAG),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // S002/RF-43: compartir la última sesión, la primera de la lista; sin sesiones lo dice la lista vacía.
            val latest = state.rows.firstOrNull()
            OutlinedButton(
                onClick = { latest?.let { actions.onShare(it.id) } },
                enabled = latest != null,
                modifier = Modifier.heightIn(min = MinTouchTarget).testTag(SHARE_SESSION_TAG),
            ) { Text(stringResource(R.string.receiver_share_session)) }
            OutlinedButton(onClick = actions.onImport, modifier = Modifier.heightIn(min = MinTouchTarget).testTag(IMPORT_SESSION_TAG)) {
                Text(stringResource(R.string.sessions_import))
            } // S003/RF-33
            state.message?.let { message ->
                Text(
                    stringResource(
                        if (message ==
                            ImportMessage.NO_NMEA
                        ) {
                            R.string.sessions_import_no_nmea
                        } else {
                            R.string.sessions_import_too_large
                        },
                    ),
                    color = MaterialTheme.colorScheme.error,
                )
            } // S003/RF-34
            if (state.rows.isEmpty()) Text(stringResource(R.string.receiver_no_session))
            state.rows.forEach { row ->
                SessionCard(
                    row,
                    onPlay = { if (state.receiverConnected) pendingReplay = row.id else actions.onPlay(row.id) },
                    onShare = { actions.onShare(row.id) },
                    onDelete = { pendingDelete = row.id },
                )
            }
        }
    }
    pendingReplay?.let { id ->
        // S003/RF-42: reproducir cierra la conexión con el receptor y la NTRIP.
        AlertDialog(
            onDismissRequest = { pendingReplay = null },
            title = { Text(stringResource(R.string.sessions_replay_confirm_title)) },
            text = { Text(stringResource(R.string.sessions_replay_confirm_text)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingReplay = null
                        actions.onPlay(id)
                    },
                    modifier = Modifier.heightIn(min = MinTouchTarget).testTag(CONFIRM_REPLAY_TAG),
                ) { Text(stringResource(R.string.sessions_play)) }
            },
            dismissButton = {
                TextButton(onClick = {
                    pendingReplay = null
                }, modifier = Modifier.heightIn(min = MinTouchTarget).testTag(CANCEL_REPLAY_TAG)) {
                    Text(stringResource(R.string.sessions_cancel))
                }
            },
        )
    }
    pendingDelete?.let { id ->
        // S003/RF-31: borrar pide confirmación.
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.sessions_delete_confirm_title)) },
            text = { Text(stringResource(R.string.sessions_delete_confirm_text)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingDelete = null
                        actions.onDelete(id)
                    },
                    modifier = Modifier.heightIn(min = MinTouchTarget).testTag(CONFIRM_DELETE_TAG),
                ) { Text(stringResource(R.string.sessions_delete)) }
            },
            dismissButton = {
                TextButton(onClick = {
                    pendingDelete = null
                }, modifier = Modifier.heightIn(min = MinTouchTarget).testTag(CANCEL_DELETE_TAG)) {
                    Text(stringResource(R.string.sessions_cancel))
                }
            },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SessionCard(row: SessionRow, onPlay: () -> Unit, onShare: () -> Unit, onDelete: () -> Unit) {
    val context = LocalContext.current
    val locale = uiLocale()
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(modifier = Modifier.semantics(mergeDescendants = true) {}.testTag(sessionRowTag(row.id))) {
                Text(
                    row.startedAt.atZone(
                        ZoneId.systemDefault(),
                    ).format(DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM).withLocale(locale)),
                    style = MaterialTheme.typography.titleMedium,
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(durationText(row.durationS))
                    Text(Formatter.formatShortFileSize(context, row.sizeBytes))
                    Text(if (row.imported) stringResource(R.string.sessions_imported) else row.receiver)
                    if (row.inProgress) Text(stringResource(R.string.sessions_in_progress), color = MaterialTheme.colorScheme.primary)
                }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!row.inProgress) {
                    OutlinedButton(onClick = onPlay, modifier = Modifier.heightIn(min = MinTouchTarget).testTag(playSessionTag(row.id))) {
                        Text(stringResource(R.string.sessions_play))
                    }
                } // S003/RF-32
                OutlinedButton(onClick = onShare, modifier = Modifier.heightIn(min = MinTouchTarget).testTag(shareSessionTag(row.id))) {
                    Text(stringResource(R.string.sessions_share))
                } // S003/RF-30
                if (!row.inProgress) {
                    OutlinedButton(
                        onClick = onDelete,
                        modifier = Modifier.heightIn(min = MinTouchTarget).testTag(deleteSessionTag(row.id)),
                    ) {
                        Text(stringResource(R.string.sessions_delete))
                    }
                } // S003/RF-31
            }
        }
    }
}

@Composable
private fun durationText(seconds: Long?): String = when {
    seconds == null -> stringResource(R.string.sessions_duration_unknown)
    seconds >= SECONDS_PER_HOUR -> stringResource(
        R.string.sessions_duration_hours,
        seconds / SECONDS_PER_HOUR,
        seconds % SECONDS_PER_HOUR / 60,
    )
    else -> stringResource(R.string.sessions_duration_minutes, seconds / 60, seconds % 60)
}

private const val SECONDS_PER_HOUR = 3_600L

/** Nombre y tamaño del documento elegido, si el proveedor los da. */
private fun describe(resolver: ContentResolver, uri: Uri): Pair<String, Long?> {
    resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) {
            val name = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 }?.let(cursor::getString)
            val size = cursor.getColumnIndex(OpenableColumns.SIZE).takeIf { it >= 0 && !cursor.isNull(it) }?.let(cursor::getLong)
            return (name ?: uri.lastPathSegment.orEmpty()) to size
        }
    }
    return uri.lastPathSegment.orEmpty() to null
}
