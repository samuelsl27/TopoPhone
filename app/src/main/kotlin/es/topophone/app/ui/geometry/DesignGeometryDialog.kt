package es.topophone.app.ui.geometry

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import es.topophone.app.R
import es.topophone.core.designsystem.component.MinTouchTarget

const val DESIGN_GEOMETRY_TAG = "design_geometry"
const val DESIGN_GEOMETRY_CLOSE_TAG = "design_geometry_close"

/**
 * La información geométrica de una línea de un dibujo, a pantalla completa sobre el mapa (S026/RF-1, RF-4): se abre desde la ficha
 * del elemento. Solo lee el dibujo.
 */
@Composable
fun DesignGeometryDialog(input: DesignGeometryInput, onDismiss: () -> Unit, viewModel: DesignGeometryViewModel = hiltViewModel()) {
    LaunchedEffect(input) { viewModel.start(input) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(modifier = Modifier.fillMaxSize().testTag(DESIGN_GEOMETRY_TAG)) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth().padding(start = 16.dp, top = 8.dp),
                ) {
                    Text(
                        state.lineName.ifBlank { stringResource(R.string.geometry_title) },
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(
                        onClick = onDismiss,
                        modifier = Modifier.heightIn(min = MinTouchTarget).testTag(DESIGN_GEOMETRY_CLOSE_TAG),
                    ) { Text(stringResource(R.string.geometry_close)) }
                }
                GeometryInfoList(state, GeometryInfoActions(onMode = viewModel::setMode), Modifier.weight(1f))
            }
        }
    }
}
