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

/** Apartado "Antena y jalón" del menú del receptor (S046). */
@Serializable data object ReceiverGearRoute

/** Apartado "Guía" del menú del receptor: las listas de configuración y las comprobaciones (S043/RF-1). */
@Serializable data object ReceiverGuideRoute

/** Apartado "Datos del receptor" del menú del receptor: modelo, serie y firmware (S043/RF-18). */
@Serializable data object ReceiverEquipmentRoute

/** Formulario de perfil NTRIP: nuevo (sin id) o editado (S007/RF-2). */
@Serializable data class NtripProfileFormDestination(val profileId: String? = null)

/** Formulario de un receptor Wi-Fi: nuevo (sin id) o editado (S021/RF-2, RF-4). */
@Serializable data class WifiReceiverFormDestination(val receiverId: String? = null)

@Serializable data object SettingsGraph

@Serializable data object SettingsRoute

@Serializable data object AboutRoute

@Serializable data object LicensesRoute

@Serializable data object BackgroundHelpRoute

/** "Proyectos nuevos" en Ajustes (S006/RF-13). */
@Serializable data object ProjectDefaultsDestination

/** "Bibliotecas de códigos" en Ajustes (S023/RF-1). */
@Serializable data object CodeLibrariesDestination

/** Una biblioteca de códigos con sus códigos (S023/RF-5, RF-11). */
@Serializable data class CodeLibraryDestination(val libraryId: String)

/** Alta o edición de un código de una biblioteca: nuevo si [codeId] es null (S023/RF-5). */
@Serializable data class CodeEditorDestination(val libraryId: String, val codeId: String? = null)

/** El editor de un código creado en el proyecto, desde el recolector (S024/RF-5, RF-7). */
@Serializable data class ProjectCodeEditorDestination(val projectId: String, val codeId: String? = null)

/** Asistente de importación de una biblioteca desde el CSV [fileUri]; con [libraryId] se añade a esa biblioteca (S023/RF-12). */
@Serializable data class CodeImportDestination(val fileUri: String, val libraryId: String? = null)

/** Los códigos de la biblioteca que lleva un proyecto, solo de consulta (S023/RF-20). */
@Serializable data class ProjectCodesDestination(val projectId: String)

/** "Copias de seguridad" en Ajustes (S013). */
@Serializable data object BackupsDestination

/** Formulario de proyecto en Levantamiento: nuevo si [projectId] es null (S006). */
@Serializable data class ProjectFormDestination(val projectId: String? = null, val folderId: String? = null)

/** Papelera de proyectos (S006/RF-23). */
@Serializable data object TrashDestination

/** Una carpeta de proyectos (S006/RF-20). */
@Serializable data class ProjectFolderDestination(val folderId: String)

/** Vista de un proyecto (S006/RF-28). */
@Serializable data class ProjectViewDestination(
    val projectId: String,
    val stakeoutPointId: String? = null,
    val stakeoutQuery: String = "",
    /** Abre el replanteo de esta línea del proyecto (S027/RF-1). */
    val stakeoutLineId: String? = null,
)

/** Datos de un proyecto (S009/RF-5). */
@Serializable data class ProjectInfoDestination(val projectId: String)

/** Lista de objetos de un proyecto (S011/RF-1). */
@Serializable data class ObjectListDestination(val projectId: String)

/** El asistente «Línea desde puntos» de un proyecto (S025/RF-12). */
@Serializable data class LineFromPointsDestination(val projectId: String)

/** La ficha de una línea del proyecto (S025/RF-22). */
@Serializable data class LineDestination(val projectId: String, val lineId: String)

/** La información geométrica de una línea o un polígono del proyecto (S026/RF-1). */
@Serializable data class GeometryInfoDestination(val projectId: String, val lineId: String)

/** El ángulo entre tres puntos del proyecto o, con [lineId], de los vértices de una línea (S026/RF-13). */
@Serializable data class ThreePointAngleDestination(val projectId: String, val lineId: String? = null)

/** La elección de COGO: inversa, poligonal o intersección (S038/RF-1). */
@Serializable data class CogoDestination(val projectId: String)

/** La inversa entre dos puntos (S038/RF-6). */
@Serializable data class CogoInverseDestination(val projectId: String)

/** La intersección de distancias y direcciones (S038/RF-13). */
@Serializable data class CogoIntersectionDestination(val projectId: String)

/** La poligonal (S038/RF-10). */
@Serializable data class CogoTraverseDestination(val projectId: String)

/** Ficha de un punto (S011/RF-5). */
@Serializable data class PointDestination(val projectId: String, val pointId: String)

/** Punto nuevo, o edición de uno si hay [pointId] (S011/RF-8, RF-16). */
@Serializable data class PointFormDestination(val projectId: String, val pointId: String? = null)

/** Papelera de puntos de un proyecto (S011/RF-23). */
@Serializable data class PointTrashDestination(val projectId: String)

/** Exportar CSV: todos los puntos del proyecto o, con [selection], los elegidos en la lista (S012/RF-1, RF-2). */
@Serializable data class ExportDestination(val projectId: String, val selection: Boolean = false)

/** Exportar GeoPackage: todos los puntos del proyecto o, con [selection], los elegidos en la lista (S053/RF-1, RF-2). */
@Serializable data class ExportGeoPackageDestination(val projectId: String, val selection: Boolean = false)

/** Exportar DXF: todos los puntos del proyecto o, con [selection], los elegidos en la lista (S029/RF-1, RF-2). */
@Serializable data class ExportDxfDestination(val projectId: String, val selection: Boolean = false)

/** Exportar Shapefile: todos los puntos del proyecto o, con [selection], los elegidos en la lista (S055/RF-1, RF-2). */
@Serializable data class ExportShpDestination(val projectId: String, val selection: Boolean = false)

/** Exportar KML o KMZ: todos los puntos del proyecto o, con [selection], los elegidos en la lista (S031/RF-20). */
@Serializable data class ExportKmlDestination(val projectId: String, val selection: Boolean = false)

/** Exportar las fotos: de todos los puntos del proyecto o, con [selection], de los elegidos en la lista (S056/RF-1, RF-2). */
@Serializable data class ExportPhotosDestination(val projectId: String, val selection: Boolean = false)

/** Informe de replanteo: de todo el proyecto o, con [selection], de los puntos elegidos en la lista (S019/RF-17, RF-18). */
@Serializable data class StakeoutReportDestination(val projectId: String, val selection: Boolean = false)

/** El asistente de importación de puntos de un CSV o TXT: el proyecto y el fichero elegido, como dirección (S028/RF-1). */
@Serializable data class ImportPointsDestination(val projectId: String, val fileUri: String)

/** La importación de un DXF: el proyecto y el fichero elegido, como dirección (S040/RF-1). */
@Serializable data class ImportDxfDestination(val projectId: String, val fileUri: String)

/** La importación de una superficie de un LandXML: el proyecto y el fichero elegido, como dirección (S042/RF-1). */
@Serializable data class ImportSurfaceDestination(val projectId: String, val fileUri: String)

/** La importación de un ráster de cotas: el proyecto y el fichero elegido, como dirección (S045/RF-1). */
@Serializable data class ImportRasterDestination(val projectId: String, val fileUri: String)

/** La importación de un GeoPackage: el proyecto y el fichero elegido, como dirección (S048/RF-1). */
@Serializable data class ImportGpkgDestination(val projectId: String, val fileUri: String)

/** La importación de un Shapefile: el proyecto y los ficheros elegidos (un ZIP o varios sueltos), como dirección (S030/RF-1). */
@Serializable data class ImportShpDestination(val projectId: String, val fileUris: List<String>)

@Serializable data class ImportKmlDestination(val projectId: String, val fileUri: String)

/** La importación de los ejes de un LandXML: el proyecto y el fichero elegido, como dirección (S049/RF-1). */
@Serializable data class ImportAlignmentDestination(val projectId: String, val fileUri: String)

/** La rasante de un CSV para un eje: el proyecto y el fichero elegido, como dirección (S049/RF-9). */
@Serializable data class ImportProfileCsvDestination(val projectId: String, val fileUri: String)

/** La definición a mano de un eje del proyecto (S049/RF-8). */
@Serializable data class DefineAlignmentDestination(val projectId: String)

/** El desplazamiento de base de un proyecto (S020/RF-1). */
@Serializable data class BaseShiftDestination(val projectId: String)

/** El cálculo de un punto sobre un eje del proyecto (S049/RF-20). */
@Serializable data class AlignmentPointDestination(val projectId: String, val alignmentId: String)

/** Las secciones transversales y el peralte de un eje del proyecto (S050/RF-1). */
@Serializable data class SectionsDestination(val projectId: String, val alignmentId: String)

/** Las tomas y los perfiles tomados de un proyecto (S052/RF-22). */
@Serializable data class LinearSurveysDestination(val projectId: String)

/** Los taludes de un eje del proyecto (S051/RF-1). */
@Serializable data class SlopesDestination(val projectId: String, val alignmentId: String)

/** El cálculo del punto de talud teórico de un eje (S051/RF-5 a RF-8). */
@Serializable data class SlopePointDestination(val projectId: String, val alignmentId: String)

/** El cálculo de un punto de la sección de un eje y su alzado (S050/RF-9 a RF-15). */
@Serializable data class SectionPointDestination(val projectId: String, val alignmentId: String)

/** La importación de secciones de un CSV o un LandXML a un eje: el tipo y el fichero elegido, como dirección (S050/RF-4, RF-5). */
@Serializable data class ImportSectionsDestination(val projectId: String, val alignmentId: String, val kind: String, val fileUri: String)

/** Las tres secciones de la navegación principal, en este orden (S001/RF-2). */
enum class TopLevelDestination(val graph: Any, @param:StringRes val label: Int, @param:DrawableRes val icon: Int) {
    SURVEY(SurveyGraph, R.string.nav_survey, R.drawable.ic_nav_survey),
    RECEIVER(ReceiverGraph, R.string.nav_receiver, R.drawable.ic_nav_receiver),
    SETTINGS(SettingsGraph, R.string.nav_settings, R.drawable.ic_nav_settings),
    ;

    val testTag: String get() = "nav_$name"
}
