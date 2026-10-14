package es.topophone.app.ui.settings

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import es.topophone.app.R
import es.topophone.app.ui.AppActions
import es.topophone.app.ui.AppUiState
import es.topophone.app.ui.SectionScaffold
import es.topophone.core.designsystem.component.SettingsItem
import es.topophone.core.designsystem.component.SettingsSectionHeader
import es.topophone.core.designsystem.component.SingleChoiceDialog
import es.topophone.core.model.preferences.ThemeMode

const val THEME_SETTING_TAG = "setting_theme"

fun themeOptionTag(mode: ThemeMode) = "theme_option_${mode.name}"

@StringRes
fun ThemeMode.labelRes(): Int = when (this) {
    ThemeMode.SYSTEM -> R.string.theme_system
    ThemeMode.LIGHT -> R.string.theme_light
    ThemeMode.DARK -> R.string.theme_dark
    ThemeMode.SUN -> R.string.theme_sun
}

/**
 * Sección Ajustes. Cada spec añade aquí sus ajustes.
 * El diálogo abierto y el desplazamiento sobreviven al giro (S001/RF-7).
 */
@Composable
fun SettingsScreen(uiState: AppUiState, actions: AppActions) {
    var showThemeDialog by rememberSaveable { mutableStateOf(false) }
    val listState = rememberLazyListState()

    SectionScaffold(
        title = stringResource(R.string.nav_settings),
        themeState = uiState.themeState,
        onToggleSun = actions.onToggleSun,
    ) { padding ->
        LazyColumn(state = listState, modifier = Modifier.padding(padding)) {
            item { SettingsSectionHeader(stringResource(R.string.settings_section_appearance)) }
            item {
                SettingsItem(
                    title = stringResource(R.string.settings_theme),
                    value = stringResource(uiState.themeState.mode.labelRes()),
                    onClick = { showThemeDialog = true },
                    modifier = Modifier.testTag(THEME_SETTING_TAG),
                )
            }
        }
    }

    if (showThemeDialog) {
        // S001/RF-9: cuatro temas en orden; elegir uno lo aplica al momento.
        SingleChoiceDialog(
            title = stringResource(R.string.settings_theme_dialog_title),
            options = ThemeMode.entries.map { it to stringResource(it.labelRes()) },
            selected = uiState.themeState.mode,
            onSelect = { mode ->
                actions.onSelectTheme(mode)
                showThemeDialog = false
            },
            onDismiss = { showThemeDialog = false },
            optionTag = ::themeOptionTag,
        )
    }
}
