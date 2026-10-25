package es.topophone.app.ui

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import es.topophone.app.R
import kotlinx.serialization.Serializable

// Rutas tipadas (ADR-19). Cada sección es un grafo anidado para que conserve su propia pila.
@Serializable data object SurveyGraph

@Serializable data object SurveyRoute

@Serializable data object ReceiverGraph

@Serializable data object ReceiverRoute

@Serializable data object SessionsRoute

@Serializable data object ReceiverStatusRoute

@Serializable data object ReceiverCoordinatesRoute

@Serializable data object ReceiverCorrectionsRoute

/** Apartado "Inclinación" del menú del receptor (S016/RF-1). */
@Serializable data object ReceiverTiltRoute

/** Formulario de perfil NTRIP: nuevo (sin id) o editado (S007/RF-2). */
@Serializable data class NtripProfileFormDestination(val profileId: String? = null)

@Serializable data object SettingsGraph

@Serializable data object SettingsRoute

@Serializable data object AboutRoute

@Serializable data object LicensesRoute

@Serializable data object BackgroundHelpRoute

/** "Proyectos nuevos" en Ajustes (S006/RF-13). */
@Serializable data object ProjectDefaultsDestination

/** "Copias de seguridad" en Ajustes (S013). */
@Serializable data object BackupsDestination

/** Formulario de proyecto en Levantamiento: nuevo si [projectId] es null (S006). */
@Serializable data class ProjectFormDestination(val projectId: String? = null, val folderId: String? = null)

/** Papelera de proyectos (S006/RF-23). */
@Serializable data object TrashDestination

/** Una carpeta de proyectos (S006/RF-20). */
@Serializable data class ProjectFolderDestination(val folderId: String)

/** Vista de un proyecto (S006/RF-28). */
@Serializable data class ProjectViewDestination(val projectId: String, val stakeoutPointId: String? = null, val stakeoutQuery: String = "")

/** Datos de un proyecto (S009/RF-5). */
@Serializable data class ProjectInfoDestination(val projectId: String)

/** Lista de objetos de un proyecto (S011/RF-1). */
@Serializable data class ObjectListDestination(val projectId: String)

/** Ficha de un punto (S011/RF-5). */
@Serializable data class PointDestination(val projectId: String, val pointId: String)

/** Punto nuevo, o edición de uno si hay [pointId] (S011/RF-8, RF-16). */
@Serializable data class PointFormDestination(val projectId: String, val pointId: String? = null)

/** Papelera de puntos de un proyecto (S011/RF-23). */
@Serializable data class PointTrashDestination(val projectId: String)

/** Exportar CSV: todos los puntos del proyecto o, con [selection], los elegidos en la lista (S012/RF-1, RF-2). */
@Serializable data class ExportDestination(val projectId: String, val selection: Boolean = false)

/** Informe de replanteo: de todo el proyecto o, con [selection], de los puntos elegidos en la lista (S019/RF-17, RF-18). */
@Serializable data class StakeoutReportDestination(val projectId: String, val selection: Boolean = false)

/** El asistente de importación de puntos de un CSV o TXT: el proyecto y el fichero elegido, como dirección (S028/RF-1). */
@Serializable data class ImportPointsDestination(val projectId: String, val fileUri: String)

/** Las tres secciones de la navegación principal, en este orden (S001/RF-2). */
enum class TopLevelDestination(val graph: Any, @param:StringRes val label: Int, @param:DrawableRes val icon: Int) {
    SURVEY(SurveyGraph, R.string.nav_survey, R.drawable.ic_nav_survey),
    RECEIVER(ReceiverGraph, R.string.nav_receiver, R.drawable.ic_nav_receiver),
    SETTINGS(SettingsGraph, R.string.nav_settings, R.drawable.ic_nav_settings),
    ;

    val testTag: String get() = "nav_$name"
}
