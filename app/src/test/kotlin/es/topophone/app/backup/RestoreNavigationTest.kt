package es.topophone.app.backup

import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.app.ui.AppActions
import es.topophone.app.ui.AppUiState
import es.topophone.app.ui.TopLevelDestination
import es.topophone.app.ui.TopoPhoneApp
import es.topophone.app.ui.navItem
import es.topophone.app.ui.projects.NEW_PROJECT_TAG
import es.topophone.app.ui.projects.ProjectListActions
import es.topophone.app.ui.projects.ProjectListScreen
import es.topophone.app.ui.projects.ProjectListUiState
import es.topophone.app.ui.projects.SurveyPage
import es.topophone.app.ui.settings.BACKUPS_SETTING_TAG
import es.topophone.app.ui.testAppInfo
import es.topophone.core.designsystem.theme.TopoPhoneTheme
import es.topophone.core.model.preferences.AppLanguage
import es.topophone.core.model.preferences.EffectiveTheme
import es.topophone.core.model.preferences.ThemeState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Tras restaurar o deshacer, la app vuelve a la lista de proyectos con los datos nuevos (S013/RF-8, RF-11). */
@RunWith(AndroidJUnit4::class)
class RestoreNavigationTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `S013 RF-8 tras restaurar se vuelve a la lista de proyectos`() {
        compose.setContent {
            TopoPhoneTheme(EffectiveTheme.LIGHT) {
                TopoPhoneApp(
                    uiState = AppUiState(ThemeState.DEFAULT, AppLanguage.SYSTEM, testAppInfo),
                    actions = AppActions({}, {}, {}, {}),
                    surveySection = { page, theme, toggleSun, navigation, _ ->
                        if (page == SurveyPage.List) {
                            ProjectListScreen(theme, toggleSun, ProjectListUiState(), ProjectListActions())
                        } else {
                            Text("proyecto abierto", modifier = Modifier.testTag(OPEN_PROJECT))
                        }
                    },
                    backupsSection = { _, _, _, onRestored ->
                        Button(onClick = onRestored, modifier = Modifier.testTag(RESTORED)) { Text("restaurada") }
                    },
                    statusIndicator = {},
                )
            }
        }
        compose.navItem(TopLevelDestination.SETTINGS).performClick()
        compose.onNodeWithTag(BACKUPS_SETTING_TAG).performScrollTo().performClick()

        compose.onNodeWithTag(RESTORED).performClick()

        compose.onNodeWithTag(NEW_PROJECT_TAG).assertIsDisplayed()
        compose.navItem(TopLevelDestination.SURVEY).assertIsSelected()
    }

    @Test
    fun `S013 RF-8 un proyecto abierto antes de restaurar no vuelve`() {
        compose.setContent { app() }
        compose.runOnIdle { openProject?.invoke("p1") }
        compose.onNodeWithTag(OPEN_PROJECT).assertIsDisplayed()
        compose.navItem(TopLevelDestination.SETTINGS).performClick()
        compose.onNodeWithTag(BACKUPS_SETTING_TAG).performScrollTo().performClick()

        compose.onNodeWithTag(RESTORED).performClick()
        compose.onNodeWithTag(OPEN_PROJECT).assertDoesNotExist()
        compose.navItem(TopLevelDestination.RECEIVER).performClick()
        compose.navItem(TopLevelDestination.SURVEY).performClick()

        compose.onNodeWithTag(NEW_PROJECT_TAG).assertIsDisplayed()
        compose.onNodeWithTag(OPEN_PROJECT).assertDoesNotExist()
    }

    private var openProject: ((String) -> Unit)? = null

    @androidx.compose.runtime.Composable
    private fun app() {
        TopoPhoneTheme(EffectiveTheme.LIGHT) {
            TopoPhoneApp(
                uiState = AppUiState(ThemeState.DEFAULT, AppLanguage.SYSTEM, testAppInfo),
                actions = AppActions({}, {}, {}, {}),
                receiverSection = { _, _, _, _, _ -> Text("receptor") },
                surveySection = { page, theme, toggleSun, navigation, _ ->
                    openProject = navigation.onOpenProject
                    if (page == SurveyPage.List) {
                        ProjectListScreen(theme, toggleSun, ProjectListUiState(), ProjectListActions())
                    } else {
                        Text("proyecto abierto", modifier = Modifier.testTag(OPEN_PROJECT))
                    }
                },
                backupsSection = { _, _, _, onRestored ->
                    Button(onClick = onRestored, modifier = Modifier.testTag(RESTORED)) { Text("restaurada") }
                },
                statusIndicator = {},
            )
        }
    }

    private companion object {
        const val RESTORED = "restored"
        const val OPEN_PROJECT = "open_project"
    }
}
