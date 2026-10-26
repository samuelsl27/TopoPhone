package es.topophone.app.ui.projects

import androidx.compose.runtime.Composable
import es.topophone.app.ui.cad.CadImportActions
import es.topophone.app.ui.cad.CadImportScreen
import es.topophone.app.ui.cad.CadImportUiState
import es.topophone.app.ui.export.ExportActions
import es.topophone.app.ui.export.ExportScreen
import es.topophone.app.ui.export.ExportUiState
import es.topophone.app.ui.points.ObjectListActions
import es.topophone.app.ui.points.ObjectListScreen
import es.topophone.app.ui.points.ObjectListUiState
import es.topophone.app.ui.points.PointDetailsActions
import es.topophone.app.ui.points.PointDetailsScreen
import es.topophone.app.ui.points.PointDetailsUiState
import es.topophone.app.ui.points.PointFormActions
import es.topophone.app.ui.points.PointFormScreen
import es.topophone.app.ui.points.PointFormUiState
import es.topophone.app.ui.points.PointImportActions
import es.topophone.app.ui.points.PointImportScreen
import es.topophone.app.ui.points.PointImportUiState
import es.topophone.app.ui.points.PointTrashActions
import es.topophone.app.ui.points.PointTrashScreen
import es.topophone.app.ui.points.PointTrashUiState
import es.topophone.app.ui.stakeout.StakeoutReportActions
import es.topophone.app.ui.stakeout.StakeoutReportScreen
import es.topophone.app.ui.stakeout.StakeoutReportUiState
import es.topophone.app.ui.surface.SurfaceImportActions
import es.topophone.app.ui.surface.SurfaceImportScreen
import es.topophone.app.ui.surface.SurfaceImportUiState
import es.topophone.core.model.preferences.ThemeState

/** Pantallas de la sección Levantamiento (S006). */
sealed interface SurveyPage {
    /** Lista de proyectos (S006/RF-1). */
    data object List : SurveyPage

    /** Formulario de proyecto: nuevo si [projectId] es null (S006/RF-5, RF-14). */
    data class Form(val projectId: String?, val folderId: String?) : SurveyPage

    /** Vista de un proyecto (S006/RF-28). */
    data class View(val projectId: String, val stakeoutPointId: String? = null, val stakeoutQuery: String = "") : SurveyPage

    /** Datos de un proyecto (S009/RF-5). */
    data class Info(val projectId: String) : SurveyPage

    /** Una carpeta abierta (S006/RF-20). */
    data class Folder(val folderId: String) : SurveyPage

    /** Papelera de proyectos (S006/RF-23). */
    data object Trash : SurveyPage

    /** Lista de objetos de un proyecto (S011/RF-1). */
    data class Objects(val projectId: String) : SurveyPage

    /** Ficha de un punto (S011/RF-5). */
    data class PointDetails(val projectId: String, val pointId: String) : SurveyPage

    /** Punto nuevo, o edición de uno si hay [pointId] (S011/RF-8, RF-16). */
    data class PointForm(val projectId: String, val pointId: String?) : SurveyPage

    /** Papelera de puntos de un proyecto (S011/RF-23). */
    data class PointTrash(val projectId: String) : SurveyPage

    /** Exportar CSV (S012/RF-1, RF-2). */
    data class Export(val projectId: String, val selection: Boolean) : SurveyPage

    /** Informe de replanteo (S019/RF-17, RF-18). */
    data class StakeoutReport(val projectId: String, val selection: Boolean) : SurveyPage

    /** Importar puntos de un CSV o TXT: el proyecto y la dirección del fichero elegido (S028/RF-1). */
    data class ImportPoints(val projectId: String, val fileUri: String) : SurveyPage

    /** Importar un DXF: el proyecto y la dirección del fichero elegido (S040/RF-1). */
    data class ImportDxf(val projectId: String, val fileUri: String) : SurveyPage

    /** Importar una superficie de un LandXML: el proyecto y la dirección del fichero elegido (S042/RF-1). */
    data class ImportSurface(val projectId: String, val fileUri: String) : SurveyPage
}

/** Lo que Levantamiento necesita de la navegación de la app (S006). */
class SurveyNavigation(
    val onNewProject: (folderId: String?) -> Unit = {},
    val onOpenProject: (String) -> Unit = {},
    val onOpenFolder: (String) -> Unit = {},
    /** Tras guardar un proyecto nuevo se abre en lugar del formulario (S006/RF-12). */
    val onProjectSaved: (String) -> Unit = {},
    val onEditProject: (String) -> Unit = {},
    val onOpenProjectInfo: (String) -> Unit = {},
    val onOpenObjects: (projectId: String) -> Unit = {},
    val onOpenPoint: (projectId: String, pointId: String) -> Unit = { _, _ -> },
    val onNewPoint: (projectId: String) -> Unit = {},
    val onEditPoint: (projectId: String, pointId: String) -> Unit = { _, _ -> },
    val onOpenPointTrash: (projectId: String) -> Unit = {},
    /** El replanteo de un punto, en la vista del proyecto; [query] es la búsqueda de la lista de objetos (S018/RF-1, RF-3). */
    val onStakeout: (projectId: String, pointId: String, query: String) -> Unit = { _, _, _ -> },
    /** S013/RF-21: "Copias de seguridad" de Ajustes. */
    val onOpenBackups: () -> Unit = {},
    val onExport: (projectId: String, selection: Boolean) -> Unit = { _, _ -> },
    /** El informe de replanteo de un proyecto o de los puntos seleccionados (S019/RF-17, RF-18). */
    val onStakeoutReport: (projectId: String, selection: Boolean) -> Unit = { _, _ -> },
    /** El asistente de importación de puntos, con el fichero que eligió el usuario (S028/RF-1). */
    val onImportPoints: (projectId: String, fileUri: String) -> Unit = { _, _ -> },
    /** La pantalla de importación de un DXF, con el fichero que eligió el usuario (S040/RF-1). */
    val onImportDxf: (projectId: String, fileUri: String) -> Unit = { _, _ -> },
    /** La pantalla de importación de una superficie, con el fichero que eligió el usuario (S042/RF-1). */
    val onImportSurface: (projectId: String, fileUri: String) -> Unit = { _, _ -> },
    val onOpenTrash: () -> Unit = {},
    /** El apartado "Inclinación" del receptor, desde el recolector (S016/RF-2, RF-18). */
    val onOpenTilt: () -> Unit = {},
    val onBack: () -> Unit = {},
)

/** Estado de las pantallas de Levantamiento para pintarlas sin ViewModel (tests y vistas previas). */
data class SurveyUiState(
    val list: ProjectListUiState = ProjectListUiState(),
    val form: ProjectFormUiState = ProjectFormUiState(loaded = true),
    val view: ProjectViewUiState = ProjectViewUiState(),
    val info: ProjectInfoUiState = ProjectInfoUiState(),
    val folder: ProjectFolderUiState = ProjectFolderUiState(),
    val trash: TrashUiState = TrashUiState(),
    val objects: ObjectListUiState = ObjectListUiState(),
    val point: PointDetailsUiState = PointDetailsUiState(),
    val pointForm: PointFormUiState = PointFormUiState(loaded = true),
    val pointTrash: PointTrashUiState = PointTrashUiState(loaded = true),
    val export: ExportUiState = ExportUiState(loaded = true),
    val stakeoutReport: StakeoutReportUiState = StakeoutReportUiState(loaded = true),
    val import: PointImportUiState = PointImportUiState(),
    val cadImport: CadImportUiState = CadImportUiState(),
    val surfaceImport: SurfaceImportUiState = SurfaceImportUiState(),
)

/** Pinta una pantalla de Levantamiento sin estado propio. */
@Composable
fun SurveyPageScreen(
    page: SurveyPage,
    themeState: ThemeState,
    onToggleSun: () -> Unit,
    state: SurveyUiState,
    navigation: SurveyNavigation,
) {
    when (page) {
        SurveyPage.List -> ProjectListScreen(
            themeState,
            onToggleSun,
            state.list,
            ProjectListActions(
                onNewProject = { navigation.onNewProject(null) },
                onOpenProject = navigation.onOpenProject,
                onOpenFolder = navigation.onOpenFolder,
                onEdit = navigation.onEditProject,
                onOpenTrash = navigation.onOpenTrash,
            ),
        )
        is SurveyPage.Form -> ProjectFormScreen(themeState, onToggleSun, state.form, ProjectFormActions(onBack = navigation.onBack))
        is SurveyPage.View -> ProjectViewScreen(
            themeState,
            onToggleSun,
            state.view,
            navigation.onBack,
            navigation.onEditProject,
            navigation.onOpenProjectInfo,
        )
        is SurveyPage.Info -> ProjectInfoScreen(themeState, onToggleSun, state.info, navigation.onBack)
        is SurveyPage.Folder -> ProjectFolderScreen(
            themeState,
            onToggleSun,
            state.folder,
            ProjectFolderActions(ProjectListActions(onOpenProject = navigation.onOpenProject), navigation.onNewProject, navigation.onBack),
        )
        SurveyPage.Trash -> TrashScreen(themeState, onToggleSun, state.trash, TrashActions(onBack = navigation.onBack))
        is SurveyPage.Objects -> ObjectListScreen(themeState, onToggleSun, state.objects, ObjectListActions(), navigation.onBack)
        is SurveyPage.PointDetails -> PointDetailsScreen(themeState, onToggleSun, state.point, PointDetailsActions(), navigation.onBack)
        is SurveyPage.PointForm -> PointFormScreen(themeState, onToggleSun, state.pointForm, PointFormActions(), navigation.onBack)
        is SurveyPage.PointTrash -> PointTrashScreen(themeState, onToggleSun, state.pointTrash, PointTrashActions(), navigation.onBack)
        is SurveyPage.Export -> ExportScreen(themeState, onToggleSun, state.export, ExportActions(), navigation.onBack)
        is SurveyPage.ImportPoints -> PointImportScreen(themeState, onToggleSun, state.import, PointImportActions(), navigation.onBack)
        is SurveyPage.ImportDxf -> CadImportScreen(themeState, onToggleSun, state.cadImport, CadImportActions(), navigation.onBack)
        is SurveyPage.ImportSurface ->
            SurfaceImportScreen(themeState, onToggleSun, state.surfaceImport, SurfaceImportActions(), navigation.onBack)
        is SurveyPage.StakeoutReport ->
            StakeoutReportScreen(themeState, onToggleSun, state.stakeoutReport, StakeoutReportActions(), navigation.onBack)
    }
}
