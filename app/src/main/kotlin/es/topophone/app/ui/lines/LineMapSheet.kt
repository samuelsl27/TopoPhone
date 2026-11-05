package es.topophone.app.ui.lines

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import es.topophone.app.R
import es.topophone.core.designsystem.component.MinTouchTarget

const val LINE_SHEET_TAG = "line_map_sheet"
const val LINE_SHEET_OPEN_TAG = "line_map_sheet_open"
const val LINE_SHEET_STAKEOUT_TAG = "line_map_sheet_stakeout"
const val LINE_SHEET_INFO_TAG = "line_map_sheet_info"
const val LINE_SHEET_AXIS_TAG = "line_map_sheet_axis"

/**
 * Lo que se puede hacer con una línea o un polígono del proyecto que se ha tocado en el mapa (S027/RF-1, S026/RF-1, S027/RF-17): abrir su
 * ficha (lo que hacía el toque en S025), replantearla, ver su información y, si es una línea abierta, crearle un eje.
 * [onCreateAxis] es null cuando no se ofrece.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LineMapSheet(
    name: String,
    onOpen: () -> Unit,
    onStakeout: () -> Unit,
    onInformation: () -> Unit,
    onCreateAxis: (() -> Unit)? = null,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            modifier = Modifier.verticalScroll(
                rememberScrollState(),
            ).padding(horizontal = 16.dp).padding(bottom = 24.dp).testTag(LINE_SHEET_TAG),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(name, style = MaterialTheme.typography.titleLarge)
            Button(
                onClick = onStakeout,
                modifier = Modifier.fillMaxWidth().heightIn(min = MinTouchTarget).testTag(LINE_SHEET_STAKEOUT_TAG),
            ) { Text(stringResource(R.string.design_element_stakeout)) }
            OutlinedButton(
                onClick = onOpen,
                modifier = Modifier.fillMaxWidth().heightIn(min = MinTouchTarget).testTag(LINE_SHEET_OPEN_TAG),
            ) { Text(stringResource(R.string.line_sheet_open)) }
            OutlinedButton(
                onClick = onInformation,
                modifier = Modifier.fillMaxWidth().heightIn(min = MinTouchTarget).testTag(LINE_SHEET_INFO_TAG),
            ) { Text(stringResource(R.string.geometry_button)) }
            if (onCreateAxis != null) {
                OutlinedButton(
                    onClick = onCreateAxis,
                    modifier = Modifier.fillMaxWidth().heightIn(min = MinTouchTarget).testTag(LINE_SHEET_AXIS_TAG),
                ) { Text(stringResource(R.string.line_create_axis)) }
            }
        }
    }
}
