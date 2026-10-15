package es.topophone.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.mikepenz.aboutlibraries.ui.compose.android.produceLibraries
import com.mikepenz.aboutlibraries.ui.compose.m3.LibrariesContainer
import es.topophone.app.R
import es.topophone.app.ui.AppActions
import es.topophone.app.ui.AppUiState
import es.topophone.app.ui.SectionScaffold
import es.topophone.core.designsystem.component.MinTouchTarget

const val LICENSES_LIST_TAG = "licenses_list"
const val GEOID_ATTRIBUTION_TAG = "geoid_attribution"
const val CC_BY_LINK_TAG = "cc_by_link"

/** Texto legal de la licencia Creative Commons Atribución 4.0. */
const val CC_BY_4_URL = "https://creativecommons.org/licenses/by/4.0/"

/**
 * Componentes de terceros incluidos en el APK, también los indirectos, con su licencia (S001/RF-23),
 * precedidos de las atribuciones de los datos que lleva la app (S002/RF-45).
 * Los datos de los componentes los genera AboutLibraries al compilar, sin conexión.
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
        LibrariesContainer(
            libraries = libraries,
            modifier = Modifier.fillMaxSize().padding(padding).testTag(LICENSES_LIST_TAG),
            header = { item(key = "data_attributions") { DataAttributions(onOpenUrl = actions.onOpenUrl) } },
        )
    }
}

@Composable
private fun DataAttributions(onOpenUrl: (String) -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.licenses_data_title), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.licenses_geoid_attribution), modifier = Modifier.testTag(GEOID_ATTRIBUTION_TAG)) // S002/RF-45
        TextButton(onClick = { onOpenUrl(CC_BY_4_URL) }, modifier = Modifier.heightIn(min = MinTouchTarget).testTag(CC_BY_LINK_TAG)) {
            Text(stringResource(R.string.licenses_cc_by_open))
        }
        Text(stringResource(R.string.licenses_components_title), style = MaterialTheme.typography.titleMedium)
    }
}
