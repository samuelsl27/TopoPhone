package es.topophone.app.ui.projects

import androidx.compose.runtime.Composable
import es.topophone.core.model.preferences.ThemeState

/** Pantallas de la sección Levantamiento (S006). */
sealed interface SurveyPage {
    /** Lista de proyectos (S006/RF-1). */
    data object List : SurveyPage

    /** Formulario de proyecto: nuevo si [projectId] es null (S006/RF-5, RF-14). */
    data class Form(val projectId: String?, val folderId: String?) : SurveyPage

    /** Vista de un proyecto (S006/RF-28). */
    data class View(val projectId: String) : SurveyPage
}

/** Lo que Levantamiento necesita de la navegación de la app (S006). */
class SurveyNavigation(
    val onNewProject: (folderId: String?) -> Unit = {},
    val onOpenProject: (String) -> Unit = {},
    val onOpenFolder: (String) -> Unit = {},
    /** Tras guardar un proyecto nuevo se abre en lugar del formulario (S006/RF-12). */
    val onProjectSaved: (String) -> Unit = {},
    val onEditProject: (String) -> Unit = {},
    val onOpenTrash: () -> Unit = {},
    val onBack: () -> Unit = {},
)

/** Estado de las pantallas de Levantamiento para pintarlas sin ViewModel (tests y vistas previas). */
data class SurveyUiState(
    val list: ProjectListUiState = ProjectListUiState(),
    val form: ProjectFormUiState = ProjectFormUiState(loaded = true),
    val view: ProjectViewUiState = ProjectViewUiState(),
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
        is SurveyPage.View -> ProjectViewScreen(themeState, onToggleSun, state.view, navigation.onBack, navigation.onEditProject)
    }
}
