package es.topophone.app.experimental

import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ListItem
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import es.topophone.app.R
import es.topophone.core.designsystem.component.MinTouchTarget
import es.topophone.core.designsystem.component.SettingsSectionHeader

const val EXPERIMENTAL_SWITCH_TAG = "experimental_switch"
const val EXPERIMENTAL_NOTICE_TAG = "experimental_notice"
const val EXPERIMENTAL_NOTICE_ACCEPT_TAG = "experimental_notice_accept"
const val EXPERIMENTAL_NOTICE_CANCEL_TAG = "experimental_notice_cancel"

/**
 * La sección «Experimental» de Ajustes con el interruptor del módulo (S044/RF-3). Activarlo abre el aviso legal y solo «Aceptar y activar»
 * llama a [onAccept] (RF-4); desactivarlo llama a [onDeactivate] sin aviso (RF-5). Mientras no se acepta, el interruptor sigue apagado.
 */
@Composable
fun ExperimentalSettingsRow(active: Boolean, onAccept: () -> Unit, onDeactivate: () -> Unit) {
    var showNotice by rememberSaveable { mutableStateOf(false) }
    SettingsSectionHeader(stringResource(R.string.experimental_section))
    ListItem(
        headlineContent = { Text(stringResource(R.string.experimental_settings_title)) },
        supportingContent = {
            Text(stringResource(if (active) R.string.experimental_settings_summary_on else R.string.experimental_settings_summary_off))
        },
        trailingContent = { Switch(checked = active, onCheckedChange = null) },
        modifier = Modifier
            .heightIn(min = MinTouchTarget)
            .testTag(EXPERIMENTAL_SWITCH_TAG)
            .toggleable(value = active, role = Role.Switch) { turnedOn -> if (turnedOn) showNotice = true else onDeactivate() },
    )
    if (showNotice) {
        AlertDialog(
            onDismissRequest = { showNotice = false },
            title = { Text(stringResource(R.string.experimental_notice_title)) },
            text = { Text(stringResource(R.string.experimental_notice_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showNotice = false
                        onAccept()
                    },
                    modifier = Modifier.heightIn(min = MinTouchTarget).testTag(EXPERIMENTAL_NOTICE_ACCEPT_TAG),
                ) { Text(stringResource(R.string.experimental_notice_accept)) }
            },
            dismissButton = {
                TextButton(
                    onClick = { showNotice = false },
                    modifier = Modifier.heightIn(min = MinTouchTarget).testTag(EXPERIMENTAL_NOTICE_CANCEL_TAG),
                ) { Text(stringResource(R.string.experimental_notice_cancel)) }
            },
            modifier = Modifier.testTag(EXPERIMENTAL_NOTICE_TAG),
        )
    }
}
