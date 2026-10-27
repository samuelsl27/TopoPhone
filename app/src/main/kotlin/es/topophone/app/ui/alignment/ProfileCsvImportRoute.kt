package es.topophone.app.ui.alignment

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import es.topophone.app.ui.points.importSourceOf
import es.topophone.core.model.preferences.ThemeState

/** La pantalla de asociar a un eje del proyecto [projectId] la rasante del CSV [fileUri] (S049/RF-9). */
@Composable
fun ProfileCsvImportRoute(
    projectId: String,
    fileUri: Uri,
    themeState: ThemeState,
    onToggleSun: () -> Unit,
    onBack: () -> Unit,
    viewModel: ProfileCsvImportViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(projectId, fileUri) { viewModel.start(projectId, importSourceOf(context.contentResolver, fileUri)) }
    ProfileCsvImportScreen(
        themeState = themeState,
        onToggleSun = onToggleSun,
        state = state,
        actions = ProfileCsvImportActions(
            onAlignment = viewModel::selectAlignment,
            onEncoding = viewModel::setEncoding,
            onSeparator = viewModel::setSeparator,
            onDecimal = viewModel::setDecimal,
            onHasHeader = viewModel::setHasHeader,
            onRole = viewModel::setRole,
            onAttach = viewModel::attach,
        ),
        onBack = onBack,
    )
}
