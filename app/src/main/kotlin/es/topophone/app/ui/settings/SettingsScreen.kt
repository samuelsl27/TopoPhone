package es.topophone.app.ui.settings

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import es.topophone.core.model.log.LogSize
import es.topophone.core.model.preferences.AppLanguage
import es.topophone.core.model.preferences.ThemeMode
import es.topophone.data.receiver.SessionSpace

const val THEME_SETTING_TAG = "setting_theme"
const val LANGUAGE_SETTING_TAG = "setting_language"
const val ABOUT_SETTING_TAG = "setting_about"
const val LICENSES_SETTING_TAG = "setting_licenses"
const val PROJECT_DEFAULTS_SETTING_TAG = "setting_project_defaults"
const val BACKUPS_SETTING_TAG = "setting_backups"
const val BACKGROUND_HELP_SETTING_TAG = "setting_background_help"

fun themeOptionTag(mode: ThemeMode) = "theme_option_${mode.name}"

fun languageOptionTag(language: AppLanguage) = "language_option_${language.name}"

@StringRes
fun ThemeMode.labelRes(): Int = when (this) {
    ThemeMode.SYSTEM -> R.string.theme_system
    ThemeMode.LIGHT -> R.string.theme_light
    ThemeMode.DARK -> R.string.theme_dark
    ThemeMode.SUN -> R.string.theme_sun
}

/** Español e English se muestran siempre en su propio idioma (S001/RF-16). */
@StringRes
fun AppLanguage.labelRes(): Int = when (this) {
    AppLanguage.SYSTEM -> R.string.language_system
    AppLanguage.SPANISH -> R.string.language_spanish
    AppLanguage.ENGLISH -> R.string.language_english
}

/**
 * Sección Ajustes. Cada spec añade aquí sus ajustes.
 * El diálogo abierto y el desplazamiento sobreviven al giro (S001/RF-7).
 */
@Composable
fun SettingsScreen(
    uiState: AppUiState,
    actions: AppActions,
    onOpenAbout: () -> Unit,
    onOpenLicenses: () -> Unit,
    onOpenBackgroundHelp: () -> Unit = {},
    onOpenProjectDefaults: () -> Unit = {},
    onOpenBackups: () -> Unit = {},
) {
    var showThemeDialog by rememberSaveable { mutableStateOf(false) }
    var showLanguageDialog by rememberSaveable { mutableStateOf(false) }
    var showSessionSpaceDialog by rememberSaveable { mutableStateOf(false) }
    var showCorrectionsAlarmDialog by rememberSaveable { mutableStateOf(false) }
    var showLogSizeDialog by rememberSaveable { mutableStateOf(false) }
    val scrollState = rememberScrollState()

    SectionScaffold(
        title = stringResource(R.string.nav_settings),
        themeState = uiState.themeState,
        onToggleSun = actions.onToggleSun,
    ) { padding ->
        Column(modifier = Modifier.padding(padding).verticalScroll(scrollState)) {
            SettingsSectionHeader(stringResource(R.string.settings_section_appearance))
            SettingsItem(
                title = stringResource(R.string.settings_theme),
                value = stringResource(uiState.themeState.mode.labelRes()),
                onClick = { showThemeDialog = true },
                modifier = Modifier.testTag(THEME_SETTING_TAG),
            )
            SettingsSectionHeader(stringResource(R.string.settings_section_language))
            SettingsItem(
                title = stringResource(R.string.settings_language),
                value = stringResource(uiState.language.labelRes()),
                onClick = { showLanguageDialog = true },
                modifier = Modifier.testTag(LANGUAGE_SETTING_TAG),
            )
            SettingsSectionHeader(stringResource(R.string.settings_section_projects))
            SettingsItem(
                title = stringResource(R.string.settings_project_defaults),
                onClick = onOpenProjectDefaults, // S006/RF-13
                modifier = Modifier.testTag(PROJECT_DEFAULTS_SETTING_TAG),
            )
            SettingsItem(
                title = stringResource(R.string.backups_title),
                onClick = onOpenBackups, // S013
                modifier = Modifier.testTag(BACKUPS_SETTING_TAG),
            )
            SettingsSectionHeader(stringResource(R.string.settings_section_receiver))
            SettingsItem(
                title = stringResource(R.string.settings_session_space),
                value = sessionSpaceLabel(uiState.sessionSpaceMb),
                onClick = { showSessionSpaceDialog = true },
                modifier = Modifier.testTag(SESSION_SPACE_SETTING_TAG),
            )
            SettingsItem(
                title = stringResource(R.string.background_help_title),
                onClick = onOpenBackgroundHelp, // S004/RF-12
                modifier = Modifier.testTag(BACKGROUND_HELP_SETTING_TAG),
            )
            SettingsSectionHeader(stringResource(R.string.settings_section_corrections))
            SettingsItem(
                title = stringResource(R.string.settings_corrections_alarm),
                value = stringResource(R.string.settings_seconds, uiState.correctionsAlarmS),
                onClick = { showCorrectionsAlarmDialog = true }, // S007/RF-31
                modifier = Modifier.testTag(CORRECTIONS_ALARM_SETTING_TAG),
            )
            SettingsSectionHeader(stringResource(R.string.settings_section_diagnostics))
            SettingsItem(
                title = stringResource(R.string.report_create),
                onClick = actions.onCreateReport, // S014/RF-10
                modifier = Modifier.testTag(REPORT_SETTING_TAG),
            )
            SettingsItem(
                title = stringResource(R.string.log_size),
                value = stringResource(R.string.settings_session_space_mb, uiState.logSizeMb),
                onClick = { showLogSizeDialog = true }, // S014/RF-9
                modifier = Modifier.testTag(LOG_SIZE_SETTING_TAG),
            )
            SettingsSectionHeader(stringResource(R.string.settings_section_information))
            SettingsItem(
                title = stringResource(R.string.about_title),
                onClick = onOpenAbout,
                modifier = Modifier.testTag(ABOUT_SETTING_TAG),
            )
            SettingsItem(
                title = stringResource(R.string.licenses_title),
                onClick = onOpenLicenses,
                modifier = Modifier.testTag(LICENSES_SETTING_TAG),
            )
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

    if (showSessionSpaceDialog) {
        // S003/RF-26: el nuevo límite se aplica al momento y borra las sesiones más antiguas que no quepan.
        SingleChoiceDialog(
            title = stringResource(R.string.settings_session_space),
            options = SessionSpace.OPTIONS_MB.map { it to sessionSpaceLabel(it) },
            selected = uiState.sessionSpaceMb,
            onSelect = { mb ->
                actions.onSelectSessionSpace(mb)
                showSessionSpaceDialog = false
            },
            onDismiss = { showSessionSpaceDialog = false },
            optionTag = ::sessionSpaceOptionTag,
        )
    }

    if (showLogSizeDialog) {
        // S014/RF-9: el nuevo límite se aplica al momento y borra lo más antiguo que no quepa.
        SingleChoiceDialog(
            title = stringResource(R.string.log_size),
            options = LogSize.OPTIONS_MB.map { it to stringResource(R.string.settings_session_space_mb, it) },
            selected = uiState.logSizeMb,
            onSelect = { mb ->
                actions.onSelectLogSize(mb)
                showLogSizeDialog = false
            },
            onDismiss = { showLogSizeDialog = false },
            optionTag = ::logSizeOptionTag,
        )
    }

    if (showCorrectionsAlarmDialog) {
        // S007/RF-31: edad de las correcciones a partir de la cual suena la alarma.
        SingleChoiceDialog(
            title = stringResource(R.string.settings_corrections_alarm),
            options = CORRECTIONS_ALARM_OPTIONS_S.map { it to stringResource(R.string.settings_seconds, it) },
            selected = uiState.correctionsAlarmS,
            onSelect = { seconds ->
                actions.onSelectCorrectionsAlarm(seconds)
                showCorrectionsAlarmDialog = false
            },
            onDismiss = { showCorrectionsAlarmDialog = false },
            optionTag = ::correctionsAlarmOptionTag,
        )
    }

    if (showLanguageDialog) {
        // S001/RF-16, S001/RF-18: el idioma se aplica a toda la interfaz sin salir de Ajustes.
        SingleChoiceDialog(
            title = stringResource(R.string.settings_language_dialog_title),
            options = AppLanguage.entries.map { it to stringResource(it.labelRes()) },
            selected = uiState.language,
            onSelect = { language ->
                showLanguageDialog = false
                actions.onSelectLanguage(language)
            },
            onDismiss = { showLanguageDialog = false },
            optionTag = ::languageOptionTag,
        )
    }
}

const val SESSION_SPACE_SETTING_TAG = "setting_session_space"
const val REPORT_SETTING_TAG = "setting_report"
const val LOG_SIZE_SETTING_TAG = "setting_log_size"
const val CORRECTIONS_ALARM_SETTING_TAG = "setting_corrections_alarm"

/** Umbrales que se ofrecen, todos entre 2 y 60 s (S007/RF-31). */
val CORRECTIONS_ALARM_OPTIONS_S = listOf(2, 5, 10, 15, 20, 30, 45, 60)

fun correctionsAlarmOptionTag(seconds: Int) = "corrections_alarm_$seconds"

fun sessionSpaceOptionTag(mb: Int) = "session_space_$mb"

fun logSizeOptionTag(mb: Int) = "log_size_$mb"

/** 100 MB, 250 MB, 500 MB, 1 GB o 2 GB. */
@Composable
private fun sessionSpaceLabel(mb: Int): String = if (mb < MB_PER_GB) {
    stringResource(R.string.settings_session_space_mb, mb)
} else {
    stringResource(R.string.settings_session_space_gb, mb / MB_PER_GB)
}

private const val MB_PER_GB = 1000
