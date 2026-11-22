package es.topophone.app.ui.crs

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import es.topophone.core.model.preferences.ThemeState
import es.topophone.core.model.project.CustomCrs

/**
 * El editor de SC personalizados con su ViewModel de Hilt (S035/RF-1 a RF-8): [onSaved] recibe el SC una vez guardado en Mis SC. Partir de un SC de
 * la biblioteca abre su selector, sin Mis SC (RF-1).
 */
@Composable
fun CustomCrsEditorRoute(
    themeState: ThemeState,
    onToggleSun: () -> Unit,
    request: CustomCrsEditorRequest,
    onSaved: (CustomCrs) -> Unit,
    onBack: () -> Unit,
    viewModel: CustomCrsEditorViewModel = hiltViewModel(),
) {
    LaunchedEffect(request) { viewModel.open(request) }
    val state by viewModel.state.collectAsState()
    val mine by viewModel.mine.collectAsState()
    var libraryOpen by remember { mutableStateOf(false) }
    LaunchedEffect(state.saved) { state.saved?.let(onSaved) }
    if (libraryOpen) {
        HiltCrsLibraryHost.Flow(
            themeState,
            onToggleSun,
            CrsLibraryRequest(request.corrections, request.centre, null, allowCustom = false),
            onChosen = { chosen ->
                viewModel.startFrom(chosen.asEditorStart())
                libraryOpen = false
            },
            onDismiss = { libraryOpen = false },
        )
    }
    CustomCrsEditorScreen(
        themeState,
        onToggleSun,
        state,
        CustomCrsEditorActions(
            onChange = viewModel::update,
            onStartFrom = viewModel::startFrom,
            onOpenLibrary = { libraryOpen = true },
            onSave = viewModel::save,
            onProbe = viewModel::setProbe,
            onBack = onBack,
        ),
        mine = mine,
        library = HiltCrsLibraryHost,
        onGridsInstalled = viewModel::gridsInstalled,
    )
}
