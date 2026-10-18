package es.topophone.app.ui.projects

import androidx.compose.runtime.Composable
import es.topophone.app.ui.points.ObjectListActions
import es.topophone.app.ui.points.ObjectListScreen
import es.topophone.app.ui.points.ObjectListUiState
import es.topophone.app.ui.points.PointDetailsActions
import es.topophone.app.ui.points.PointDetailsScreen
import es.topophone.app.ui.points.PointDetailsUiState
import es.topophone.app.ui.points.PointFormActions
import es.topophone.app.ui.points.PointFormScreen
import es.topophone.app.ui.points.PointFormUiState
import es.topophone.core.model.preferences.ThemeState

/** Pantallas de la sección Levantamiento (S006). */
sealed interface SurveyPage {
    /** Lista de proyectos (S006/RF-1). */
    data object List : SurveyPage

    /** Formulario de proyecto: nuevo si [projectId] es null (S006/RF-5, RF-14). */
    data class Form(val projectId: String?, val folderId: String?) : SurveyPage

    /** Vista de un proyecto (S006/RF-28). */
    data class View(val projectId: String) : SurveyPage

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
    val onOpenTrash: () -> Unit = {},
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
    }
}
