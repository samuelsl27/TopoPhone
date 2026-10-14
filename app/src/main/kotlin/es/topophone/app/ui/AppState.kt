package es.topophone.app.ui

import es.topophone.core.model.preferences.AppLanguage
import es.topophone.core.model.preferences.ThemeMode
import es.topophone.core.model.preferences.ThemeState

/** Datos de la compilación que muestra "Acerca de" (S001/RF-20, S001/RF-21). */
data class AppInfo(val versionName: String, val gitCommit: String, val repoUrl: String)

/** Estado que la interfaz necesita de fuera (ajustes guardados e información de la app). */
data class AppUiState(val themeState: ThemeState, val language: AppLanguage, val appInfo: AppInfo)

/** Acciones de la interfaz que cambian algo fuera de ella. */
class AppActions(
    val onToggleSun: () -> Unit,
    val onSelectTheme: (ThemeMode) -> Unit,
    val onSelectLanguage: (AppLanguage) -> Unit,
    val onOpenUrl: (String) -> Unit,
)
