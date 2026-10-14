package es.topophone.app.ui.settings

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import es.topophone.app.R
import es.topophone.app.ui.SectionScaffold
import es.topophone.core.designsystem.component.EmptyState
import es.topophone.core.model.preferences.ThemeState

/** Sección Ajustes. Los ajustes de tema, idioma y "Acerca de" llegan en T14–T18. */
@Composable
fun SettingsScreen(themeState: ThemeState, onToggleSun: () -> Unit) {
    SectionScaffold(title = stringResource(R.string.nav_settings), themeState = themeState, onToggleSun = onToggleSun) { padding ->
        EmptyState(
            title = stringResource(R.string.nav_settings),
            description = stringResource(R.string.settings_empty_description),
            modifier = Modifier.padding(padding),
        )
    }
}
