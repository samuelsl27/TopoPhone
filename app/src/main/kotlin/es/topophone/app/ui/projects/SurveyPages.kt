package es.topophone.app.ui.projects

import androidx.compose.runtime.Composable
import es.topophone.core.model.preferences.ThemeState

/** Pantallas de la sección Levantamiento (S006). */
sealed interface SurveyPage {
    /** Lista de proyectos (S006/RF-1). */
    data object List : SurveyPage
}

/** Lo que Levantamiento necesita de la navegación de la app (S006). */
class SurveyNavigation(
    val onNewProject: (folderId: String?) -> Unit = {},
    val onOpenProject: (String) -> Unit = {},
    val onOpenFolder: (String) -> Unit = {},
    val onBack: () -> Unit = {},
)

/** Estado de las pantallas de Levantamiento para pintarlas sin ViewModel (tests y vistas previas). */
data class SurveyUiState(val list: ProjectListUiState = ProjectListUiState())

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
            ),
        )
    }
}
