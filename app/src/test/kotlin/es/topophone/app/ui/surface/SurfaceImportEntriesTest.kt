package es.topophone.app.ui.surface

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.app.ui.points.OBJECTS_MENU_TAG
import es.topophone.app.ui.points.ObjectListActions
import es.topophone.app.ui.points.ObjectListScreen
import es.topophone.app.ui.points.ObjectListUiState
import es.topophone.app.ui.projects.ProjectListActions
import es.topophone.app.ui.projects.ProjectListScreen
import es.topophone.app.ui.projects.ProjectListUiState
import es.topophone.app.ui.projects.SurveyNavigation
import es.topophone.app.ui.projects.SurveyPage
import es.topophone.app.ui.projects.SurveyPageScreen
import es.topophone.app.ui.projects.SurveyUiState
import es.topophone.app.ui.projects.projectMenuTag
import es.topophone.core.designsystem.theme.TopoPhoneTheme
import es.topophone.core.model.preferences.EffectiveTheme
import es.topophone.core.model.preferences.ThemeState
import es.topophone.core.model.project.AngularUnit
import es.topophone.core.model.project.CorrectionsDatum
import es.topophone.core.model.project.LinearUnit
import es.topophone.core.model.project.Project
import es.topophone.core.model.project.ProjectCrs
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Los accesos a «Importar superficie» desde la lista de proyectos y la lista de objetos, y su navegación (S042/RF-1). Interfaz en es-ES. */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "es-rES")
class SurfaceImportEntriesTest {

    @get:Rule
    val compose = createComposeRule()

    private val project = Project(
        id = "p1",
        name = "Calle Mayor 12",
        author = "",
        description = "",
        crs = ProjectCrs.ETRS89_UTM30,
        linearUnit = LinearUnit.METRE,
        angularUnit = AngularUnit.DMS,
        correctionsDatum = CorrectionsDatum.ETRS89,
        folderId = null,
        createdAtMs = 0,
        modifiedAtMs = 0,
        openedAtMs = null,
        deletedAtMs = null,
    )

    @Test
    fun `S042 RF-1 el menu de un proyecto de la lista ofrece Importar superficie`() {
        var imported: String? = null
        compose.setContent {
            TopoPhoneTheme(EffectiveTheme.LIGHT) {
                ProjectListScreen(
                    ThemeState.DEFAULT,
                    {},
                    ProjectListUiState(projects = listOf(project)),
                    ProjectListActions(onImportSurface = { imported = it }),
                )
            }
        }

        compose.onNodeWithTag(projectMenuTag("p1")).performClick()
        compose.onNodeWithText("Importar superficie").assertIsDisplayed().performClick()

        imported shouldBe "p1"
    }

    @Test
    fun `S042 RF-1 el menu de la lista de objetos ofrece Importar superficie`() {
        var imported = 0
        compose.setContent {
            TopoPhoneTheme(EffectiveTheme.LIGHT) {
                ObjectListScreen(
                    ThemeState.DEFAULT,
                    {},
                    ObjectListUiState(loaded = true),
                    ObjectListActions(onImportSurface = { imported++ }),
                    onBack = {},
                )
            }
        }

        compose.onNodeWithTag(OBJECTS_MENU_TAG).performClick()
        compose.onNodeWithText("Importar superficie").assertIsDisplayed().performClick()

        imported shouldBe 1
    }

    @Test
    fun `S042 RF-1 el acceso convive con Importar DXF y Importar CSV o TXT`() {
        compose.setContent {
            TopoPhoneTheme(EffectiveTheme.LIGHT) {
                ProjectListScreen(ThemeState.DEFAULT, {}, ProjectListUiState(projects = listOf(project)), ProjectListActions())
            }
        }

        compose.onNodeWithTag(projectMenuTag("p1")).performClick()

        compose.onNodeWithText("Importar CSV/TXT").assertIsDisplayed()
        compose.onNodeWithText("Importar DXF").assertIsDisplayed()
        compose.onNodeWithText("Importar superficie").assertIsDisplayed()
    }

    @Test
    fun `S042 RF-1 la navegacion lleva a la pantalla del proyecto con el fichero elegido`() {
        var navigated: Pair<String, String>? = null
        val navigation = SurveyNavigation(onImportSurface = { projectId, fileUri -> navigated = projectId to fileUri })

        navigation.onImportSurface("p1", "content://docs/obra.xml")

        navigated shouldBe ("p1" to "content://docs/obra.xml")
    }

    @Test
    fun `S042 RF-1 la pagina de la importacion se pinta con su estado`() {
        compose.setContent {
            TopoPhoneTheme(EffectiveTheme.LIGHT) {
                SurveyPageScreen(
                    SurveyPage.ImportSurface("p1", "content://docs/obra.xml"),
                    ThemeState.DEFAULT,
                    {},
                    SurveyUiState(
                        surfaceImport = SurfaceImportUiState(stage = SurfaceImportStage.PROBLEM, fileName = "obra.xml"),
                    ),
                    SurveyNavigation(),
                )
            }
        }

        compose.onNodeWithText("obra.xml").assertIsDisplayed()
        compose.onNodeWithText("Importar superficie").assertIsDisplayed()
    }
}
