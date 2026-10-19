package es.topophone.app.ui

import es.topophone.core.model.log.LogSize
import es.topophone.core.model.preferences.AppLanguage
import es.topophone.core.model.preferences.ThemeMode
import es.topophone.core.model.preferences.ThemeState
import es.topophone.data.receiver.CorrectionsAlarm
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
    /** Umbral de la alarma de correcciones en s (S007/RF-31). */
    val correctionsAlarmS: Int = CorrectionsAlarm.DEFAULT_S,
    /** Tamaño del log en MB (S014/RF-8). */
    val logSizeMb: Int = LogSize.DEFAULT_MB,
)

/** Acciones de la interfaz que cambian algo fuera de ella. */
class AppActions(
    val onToggleSun: () -> Unit,
    val onSelectTheme: (ThemeMode) -> Unit,
    val onSelectLanguage: (AppLanguage) -> Unit,
    val onOpenUrl: (String) -> Unit,
    val onSelectSessionSpace: (Int) -> Unit = {},
    val onSelectCorrectionsAlarm: (Int) -> Unit = {},
    /** Ajustes de la app en el sistema, para su batería (S004/RF-12). */
    val onOpenAppBatterySettings: () -> Unit = {},
    /** S014/RF-9. */
    val onSelectLogSize: (Int) -> Unit = {},
    /** S014/RF-10: pregunta si guardar o compartir el informe de sistema. */
    val onCreateReport: () -> Unit = {},
)
