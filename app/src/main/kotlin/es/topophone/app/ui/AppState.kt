package es.topophone.app.ui

import es.topophone.core.model.preferences.AppLanguage
import es.topophone.core.model.preferences.ThemeMode
import es.topophone.core.model.preferences.ThemeState
import es.topophone.data.receiver.SessionSpace

/** Datos de la compilación que muestra "Acerca de" (S001/RF-20, S001/RF-21). */
data class AppInfo(val versionName: String, val gitCommit: String, val repoUrl: String)

/** Estado que la interfaz necesita de fuera (ajustes guardados e información de la app). */
data class AppUiState(
    val themeState: ThemeState,
    val language: AppLanguage,
    val appInfo: AppInfo,
    /** Espacio para sesiones en MB (S003/RF-26). */
    val sessionSpaceMb: Int = SessionSpace.DEFAULT_MB,
)

/** Acciones de la interfaz que cambian algo fuera de ella. */
class AppActions(
    val onToggleSun: () -> Unit,
    val onSelectTheme: (ThemeMode) -> Unit,
    val onSelectLanguage: (AppLanguage) -> Unit,
    val onOpenUrl: (String) -> Unit,
    val onSelectSessionSpace: (Int) -> Unit = {},
    /** Ajustes de la app en el sistema, para su batería (S004/RF-12). */
    val onOpenAppBatterySettings: () -> Unit = {},
)
