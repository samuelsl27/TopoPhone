package es.topophone.app.ui.receiver

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import es.topophone.app.R
import es.topophone.core.designsystem.R as DsR
import es.topophone.core.designsystem.component.MinTouchTarget
import es.topophone.core.ntrip.RankedMountpoint
import es.topophone.core.ntrip.SourcetableFailure
import java.text.NumberFormat

const val MOUNTPOINT_PICKER_TAG = "mountpoint_picker"
const val MOUNTPOINT_LIST_TAG = "mountpoint_list"
const val MOUNTPOINT_SEARCH_TAG = "mountpoint_search"
const val MOUNTPOINT_FAILURE_TAG = "mountpoint_failure"
const val MOUNTPOINT_MANUAL_TAG = "mountpoint_manual"

fun mountpointTag(name: String) = "mountpoint_$name"

/** Puntos de montaje de la tabla de fuentes, a pantalla completa sobre el formulario (S007/RF-14 a RF-20). */
@Composable
internal fun MountpointPicker(picker: MountpointPickerUi, actions: NtripProfileFormActions) {
    Dialog(onDismissRequest = actions.onClosePicker, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(modifier = Modifier.fillMaxSize().testTag(MOUNTPOINT_PICKER_TAG)) {
            Column {
                Row(modifier = Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = actions.onClosePicker, modifier = Modifier.size(MinTouchTarget)) {
                        Icon(painterResource(DsR.drawable.ds_ic_back), contentDescription = stringResource(DsR.string.ds_navigate_back))
                    }
                    Text(stringResource(R.string.mountpoint_picker_title), style = MaterialTheme.typography.titleLarge)
                }
                Column(modifier = Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    val failure = picker.failure
                    when {
                        picker.loading -> Text(stringResource(R.string.mountpoint_loading))
                        failure != null -> Failure(failure, actions.onClosePicker)
                        else -> {
                            OutlinedTextField(
                                value = picker.query,
                                onValueChange = actions.onPickerQuery,
                                label = { Text(stringResource(R.string.mountpoint_search)) },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth().testTag(MOUNTPOINT_SEARCH_TAG),
                            ) // S007/RF-18
                            if (picker.shown.isEmpty()) Text(stringResource(R.string.mountpoint_none))
                        }
                    }
                }
                // Lista perezosa: hay tablas con miles de puntos de montaje (RNF de la spec).
                LazyColumn(modifier = Modifier.fillMaxWidth().testTag(MOUNTPOINT_LIST_TAG)) {
                    items(picker.shown, key = {
                        it.stream.mountpoint
                    }) { ranked -> MountpointRow(ranked) { actions.onChooseMountpoint(ranked.stream) } }
                }
            }
        }
    }
}

/** S007/RF-20: el motivo, y escribir el punto de montaje a mano. */
@Composable
private fun Failure(failure: SourcetableFailure, onManual: () -> Unit) {
    val reason = stringResource(
        when (failure) {
            SourcetableFailure.UNREACHABLE -> R.string.sourcetable_unreachable
            SourcetableFailure.UNAUTHORIZED -> R.string.sourcetable_unauthorized
            SourcetableFailure.NOT_A_SOURCETABLE -> R.string.sourcetable_not_table
            SourcetableFailure.EMPTY -> R.string.sourcetable_empty
            SourcetableFailure.TLS_INVALID -> R.string.sourcetable_tls
        },
    )
    Text(
        stringResource(R.string.mountpoint_failure, reason),
        color = MaterialTheme.colorScheme.error,
        modifier = Modifier.testTag(MOUNTPOINT_FAILURE_TAG),
    )
    Button(onClick = onManual, modifier = Modifier.heightIn(min = MinTouchTarget).testTag(MOUNTPOINT_MANUAL_TAG)) {
        Text(stringResource(R.string.mountpoint_manual))
    }
}

/** Nombre, identificador, formato, constelaciones, si necesita GGA y distancia (S007/RF-15). */
@Composable
private fun MountpointRow(ranked: RankedMountpoint, onClick: () -> Unit) {
    val stream = ranked.stream
    val distance = ranked.distanceKm?.let { km ->
        val format = NumberFormat.getNumberInstance(uiLocale()).apply {
            minimumFractionDigits = 1
            maximumFractionDigits = 1
        }
        stringResource(R.string.mountpoint_distance, format.format(km))
    }
    ListItem(
        headlineContent = { Text(stream.mountpoint) },
        supportingContent = {
            Column {
                Text(stringResource(R.string.mountpoint_summary, stream.identifier, stream.format, stream.navSystems))
                if (stream.needsGga == true) Text(stringResource(R.string.mountpoint_needs_gga), style = MaterialTheme.typography.bodySmall)
            }
        },
        trailingContent = distance?.let { { Text(it) } },
        modifier = Modifier.heightIn(min = MinTouchTarget).clickable(onClick = onClick).testTag(mountpointTag(stream.mountpoint)),
    )
}
