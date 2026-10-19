package es.topophone.app.ui

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.core.app.ApplicationProvider
import es.topophone.app.ui.backup.BackupsActions
import es.topophone.app.ui.backup.BackupsScreen
import es.topophone.app.ui.backup.BackupsUiState
import es.topophone.app.ui.projects.SurveyPageScreen
import es.topophone.app.ui.projects.SurveyUiState
import es.topophone.app.ui.receiver.ReceiverActions
import es.topophone.app.ui.receiver.ReceiverPage
import es.topophone.app.ui.receiver.ReceiverPageScreen
import es.topophone.app.ui.receiver.ReceiverUiState
import es.topophone.app.ui.sessions.SessionsActions
import es.topophone.app.ui.sessions.SessionsScreen
import es.topophone.app.ui.sessions.SessionsUiState
import es.topophone.app.ui.settings.ProjectDefaultsScreen
import es.topophone.app.ui.status.StatusIndicator
import es.topophone.app.ui.status.StatusIndicatorState
import es.topophone.core.designsystem.theme.EffectiveThemeKey
import es.topophone.core.designsystem.theme.TopoPhoneTheme
import es.topophone.core.model.preferences.AppLanguage
import es.topophone.core.model.preferences.EffectiveTheme
import es.topophone.core.model.preferences.ThemeMode
import es.topophone.core.model.preferences.ThemeState
import es.topophone.core.model.project.ProjectDefaults

/** Texto de un recurso, en el idioma del entorno de test (español por defecto). */
fun text(id: Int, vararg args: Any): String = ApplicationProvider.getApplicationContext<Context>().getString(id, *args)

val testAppInfo = AppInfo(versionName = "0.0.0-dev", gitCommit = "0123456789ab", repoUrl = "https://github.com/samuelsl27/TopoPhone")

/**
 * App completa con estado de tema e idioma en memoria, como haría el ViewModel,
 * para probar los flujos sin DataStore ni AppCompat.
 */
@Composable
fun StatefulTestApp(
    systemInDarkTheme: Boolean = false,
    initialTheme: ThemeState = ThemeState.DEFAULT,
    appInfo: AppInfo = testAppInfo,
    onOpenUrl: (String) -> Unit = {},
    onSelectSessionSpace: (Int) -> Unit = {},
    onSelectCorrectionsAlarm: (Int) -> Unit = {},
    onOpenAppBatterySettings: () -> Unit = {},
    onSelectLogSize: (Int) -> Unit = {},
    onCreateReport: () -> Unit = {},
    receiverState: ReceiverUiState = ReceiverUiState(),
    surveyState: SurveyUiState = SurveyUiState(),
) {
    var themeName by rememberSaveable { mutableStateOf(initialTheme.mode.name to initialTheme.beforeSun.name) }
    var language by rememberSaveable { mutableStateOf(AppLanguage.SYSTEM) }
    var sessionSpaceMb by rememberSaveable { mutableStateOf(500) }
    var correctionsAlarmS by rememberSaveable { mutableStateOf(10) }
    var logSizeMb by rememberSaveable { mutableStateOf(10) }
    val theme = ThemeState.restore(themeName.first, themeName.second)
    fun save(state: ThemeState) {
        themeName = state.mode.name to state.beforeSun.name
    }

    TopoPhoneTheme(theme.effective(systemInDarkTheme)) {
        TopoPhoneApp(
            uiState = AppUiState(
                themeState = theme,
                language = language,
                appInfo = appInfo,
                sessionSpaceMb = sessionSpaceMb,
                correctionsAlarmS = correctionsAlarmS,
                logSizeMb = logSizeMb,
            ),
            actions = AppActions(
                onToggleSun = { save(theme.toggleSun()) },
                onSelectTheme = { mode: ThemeMode -> save(theme.select(mode)) },
                onSelectLanguage = { language = it },
                onOpenUrl = onOpenUrl,
                onSelectSessionSpace = {
                    sessionSpaceMb = it
                    onSelectSessionSpace(it)
                },
                onOpenAppBatterySettings = onOpenAppBatterySettings,
                onSelectCorrectionsAlarm = {
                    correctionsAlarmS = it
                    onSelectCorrectionsAlarm(it)
                },
                onSelectLogSize = {
                    logSizeMb = it
                    onSelectLogSize(it)
                },
                onCreateReport = onCreateReport,
            ),
            receiverSection = { page, theme, toggleSun, navigation, _ ->
                val receiverActions = ReceiverActions(
                    onOpenSessions = navigation.onOpenSessions,
                    onOpenStatus = { navigation.onOpen(ReceiverPage.STATUS) },
                    onOpenCoordinates = { navigation.onOpen(ReceiverPage.COORDINATES) },
                    onOpenCorrections = { navigation.onOpen(ReceiverPage.CORRECTIONS) },
                )
                ReceiverPageScreen(page, theme, toggleSun, receiverState, receiverActions, navigation.onBack)
            },
            sessionsSection = { theme, toggleSun, back -> SessionsScreen(theme, toggleSun, SessionsUiState(), SessionsActions(), back) },
            statusIndicator = { onOpenReceiver -> StatusIndicator(StatusIndicatorState(), onOpenReceiver) },
            surveySection = { page, theme, toggleSun, navigation, _ -> SurveyPageScreen(page, theme, toggleSun, surveyState, navigation) },
            projectDefaultsSection = { theme, toggleSun, back ->
                var defaults by remember { mutableStateOf(ProjectDefaults()) }
                ProjectDefaultsScreen(theme, toggleSun, defaults, { defaults = it }, back)
            },
            backupsSection = { theme, toggleSun, back, _ -> BackupsScreen(theme, toggleSun, BackupsUiState(), BackupsActions(), back) },
        )
    }
}

fun ComposeTestRule.navItem(destination: TopLevelDestination): SemanticsNodeInteraction = onNodeWithTag(destination.testTag)

/** El tema guardado se lee de forma asíncrona: espera a que la app real pinte su interfaz. */
fun ComposeTestRule.awaitAppReady() = awaitNode(SemanticsMatcher.keyIsDefined(EffectiveThemeKey))

fun ComposeTestRule.awaitTheme(theme: EffectiveTheme) = awaitNode(SemanticsMatcher.expectValue(EffectiveThemeKey, theme))

private fun ComposeTestRule.awaitNode(matcher: SemanticsMatcher) =
    waitUntil(timeoutMillis = 5_000) { onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty() }
