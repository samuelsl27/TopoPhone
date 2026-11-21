package es.topophone.app.ui.alignment

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import es.topophone.app.R
import es.topophone.app.ui.points.importSourceOf
import es.topophone.app.ui.projects.SurveyNavigation
import es.topophone.core.designsystem.component.MinTouchTarget
import es.topophone.core.model.preferences.ThemeState

const val ALIGNMENT_SOURCE_LANDXML_TAG = "alignment_source_landxml"
const val ALIGNMENT_SOURCE_DRAWING_TAG = "alignment_source_drawing"
const val ALIGNMENT_SOURCE_DRAWING_OPEN_TAG = "alignment_source_drawing_open"
const val ALIGNMENT_SOURCE_CANCEL_TAG = "alignment_source_cancel"
const val ALIGNMENT_SOURCE_CSV_TAG = "alignment_source_csv"
const val ALIGNMENT_SOURCE_MANUAL_TAG = "alignment_source_manual"

/** La pantalla de importar los ejes del LandXML [fileUri] al proyecto [projectId] (S049/RF-1, RF-2). */
@Composable
fun AlignmentImportRoute(
    projectId: String,
    fileUri: Uri,
    themeState: ThemeState,
    onToggleSun: () -> Unit,
    onBack: () -> Unit,
    viewModel: AlignmentImportViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(projectId, fileUri) { viewModel.start(projectId, importSourceOf(context.contentResolver, fileUri)) }
    AlignmentImportScreen(
        themeState = themeState,
        onToggleSun = onToggleSun,
        state = state,
        actions = AlignmentImportActions(
            onToggle = viewModel::toggle,
            onSource = viewModel::setSource,
            onChooseLibrary = viewModel::chooseLibrary,
            onConfirmLibrary = viewModel::confirmLibrary,
            onRefreshLibrary = viewModel::refreshLibrary,
            onUnit = viewModel::setUnit,
            onImport = viewModel::import,
            onCancel = viewModel::cancel,
        ),
        onBack = onBack,
    )
}

/**
 * «Importar eje» (S049/RF-1): pregunta de dónde sale el eje. Con un LandXML abre el selector de ficheros y, con el fichero, la pantalla de
 * importación; con una polilínea de un dibujo explica cómo convertirla en el mapa del proyecto y lo abre. El proyecto pendiente sobrevive
 * al giro y a la muerte del proceso mientras el diálogo o el selector están abiertos.
 */
@Composable
fun rememberImportAlignmentLauncher(navigation: SurveyNavigation): (projectId: String) -> Unit {
    var pending by rememberSaveable { mutableStateOf<String?>(null) }
    var picking by rememberSaveable { mutableStateOf<String?>(null) }
    var pickingCsv by rememberSaveable { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val projectId = picking
        val csv = pickingCsv
        picking = null
        pickingCsv = false
        if (uri != null && projectId != null) {
            if (csv) navigation.onImportProfileCsv(projectId, uri.toString()) else navigation.onImportAlignment(projectId, uri.toString())
        }
    }
    pending?.let { projectId ->
        ImportAlignmentDialog(
            onLandXml = {
                pending = null
                picking = projectId
                picker.launch(arrayOf("*/*")) // RNF-T5: cualquier tipo; el contenido decide si es un LandXML
            },
            onCsv = {
                pending = null
                picking = projectId
                pickingCsv = true
                picker.launch(arrayOf("*/*")) // RNF-T5: cualquier tipo; el contenido decide si es un CSV
            },
            onManual = {
                pending = null
                navigation.onDefineAlignment(projectId)
            },
            onOpenMap = {
                pending = null
                navigation.onOpenProject(projectId)
            },
            onDismiss = { pending = null },
        )
    }
    return { projectId -> pending = projectId }
}

@Composable
private fun ImportAlignmentDialog(
    onLandXml: () -> Unit,
    onCsv: () -> Unit,
    onManual: () -> Unit,
    onOpenMap: () -> Unit,
    onDismiss: () -> Unit,
) {
    var showDrawingHelp by rememberSaveable { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.alignment_source_title)) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = onLandXml,
                    modifier = Modifier.fillMaxWidth().heightIn(min = MinTouchTarget).testTag(ALIGNMENT_SOURCE_LANDXML_TAG),
                ) {
                    Text(stringResource(R.string.alignment_source_landxml))
                }
                OutlinedButton(
                    onClick = onCsv,
                    modifier = Modifier.fillMaxWidth().heightIn(min = MinTouchTarget).testTag(ALIGNMENT_SOURCE_CSV_TAG),
                ) { Text(stringResource(R.string.alignment_source_csv)) }
                OutlinedButton(
                    onClick = onManual,
                    modifier = Modifier.fillMaxWidth().heightIn(min = MinTouchTarget).testTag(ALIGNMENT_SOURCE_MANUAL_TAG),
                ) { Text(stringResource(R.string.alignment_source_manual)) }
                OutlinedButton(
                    onClick = { showDrawingHelp = true },
                    modifier = Modifier.fillMaxWidth().heightIn(min = MinTouchTarget).testTag(ALIGNMENT_SOURCE_DRAWING_TAG),
                ) { Text(stringResource(R.string.alignment_source_drawing)) }
                if (showDrawingHelp) {
                    Text(stringResource(R.string.alignment_source_drawing_help), style = MaterialTheme.typography.bodyMedium)
                    Button(
                        onClick = onOpenMap,
                        modifier = Modifier.fillMaxWidth().heightIn(min = MinTouchTarget).testTag(ALIGNMENT_SOURCE_DRAWING_OPEN_TAG),
                    ) { Text(stringResource(R.string.alignment_source_drawing_open)) }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = MinTouchTarget).testTag(ALIGNMENT_SOURCE_CANCEL_TAG)) {
                Text(stringResource(R.string.alignment_source_cancel))
            }
        },
    )
}
