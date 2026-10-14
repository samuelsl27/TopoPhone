package es.topophone.app.ui.settings

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.mikepenz.aboutlibraries.ui.compose.android.produceLibraries
import com.mikepenz.aboutlibraries.ui.compose.m3.LibrariesContainer
import es.topophone.app.R
import es.topophone.app.ui.AppActions
import es.topophone.app.ui.AppUiState
import es.topophone.app.ui.SectionScaffold

const val LICENSES_LIST_TAG = "licenses_list"

/**
 * Componentes de terceros incluidos en el APK, también los indirectos, con su licencia (S001/RF-23).
 * Los datos los genera AboutLibraries al compilar, sin conexión.
 */
@Composable
fun LicensesScreen(uiState: AppUiState, actions: AppActions, onNavigateBack: () -> Unit) {
    SectionScaffold(
        title = stringResource(R.string.licenses_title),
        themeState = uiState.themeState,
        onToggleSun = actions.onToggleSun,
        onNavigateBack = onNavigateBack,
    ) { padding ->
        val libraries by produceLibraries(R.raw.aboutlibraries)
        LibrariesContainer(libraries = libraries, modifier = Modifier.fillMaxSize().padding(padding).testTag(LICENSES_LIST_TAG))
    }
}
