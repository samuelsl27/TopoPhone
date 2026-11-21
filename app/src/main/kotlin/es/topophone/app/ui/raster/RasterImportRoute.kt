package es.topophone.app.ui.raster

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import es.topophone.app.ui.points.importSourceOf
import es.topophone.core.model.preferences.ThemeState

/** La pantalla de importar el ráster [fileUri] al proyecto [projectId] (S045/RF-1). */
@Composable
fun RasterImportRoute(
    projectId: String,
    fileUri: Uri,
    themeState: ThemeState,
    onToggleSun: () -> Unit,
    onBack: () -> Unit,
    viewModel: RasterImportViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(projectId, fileUri) { viewModel.start(projectId, importSourceOf(context.contentResolver, fileUri)) }
    RasterImportScreen(
        themeState = themeState,
        onToggleSun = onToggleSun,
        state = state,
        actions = RasterImportActions(
            onSource = viewModel::setSource,
            onChooseLibrary = viewModel::chooseLibrary,
            onConfirmLibrary = viewModel::confirmLibrary,
            onRefreshLibrary = viewModel::refreshLibrary,
            onUnit = viewModel::setUnit,
            onHeightSystem = viewModel::setHeightSystem,
            onConfirmFar = viewModel::confirmFar,
            onConfirmDuplicate = viewModel::confirmDuplicate,
            onImport = viewModel::import,
            onCancel = viewModel::cancel,
        ),
        onBack = onBack,
    )
}

/**
 * Abre el selector de ficheros del móvil para importar un ráster al proyecto que se le pase y, con el fichero elegido, pide la
 * pantalla de importación con [onPicked] (S045/RF-1). Si el usuario cancela el selector, no pasa nada. El proyecto pendiente sobrevive
 * al giro y a la muerte del proceso mientras el selector está abierto.
 */
@Composable
fun rememberImportRasterLauncher(onPicked: (projectId: String, fileUri: String) -> Unit): (projectId: String) -> Unit {
    var pending by rememberSaveable { mutableStateOf<String?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val projectId = pending
        pending = null
        if (uri != null && projectId != null) onPicked(projectId, uri.toString())
    }
    return { projectId ->
        pending = projectId
        picker.launch(arrayOf("*/*")) // RF-2: cualquier tipo; el contenido decide si es un GeoTIFF o un ASCII Grid
    }
}
