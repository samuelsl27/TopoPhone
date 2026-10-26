package es.topophone.app.ui.cad

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

/** Los accesos a «Importar DXF» desde los menús y la navegación a su pantalla (S040/RF-1). Interfaz en es-ES. */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "es-rES")
class CadImportEntriesTest {

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
    fun `S040 RF-1 el menu de un proyecto de la lista ofrece Importar DXF`() {
        var imported: String? = null
        compose.setContent {
            TopoPhoneTheme(EffectiveTheme.LIGHT) {
                ProjectListScreen(
                    ThemeState.DEFAULT,
                    {},
                    ProjectListUiState(projects = listOf(project)),
                    ProjectListActions(onImportDxf = { imported = it }),
                )
            }
        }

        compose.onNodeWithTag(projectMenuTag("p1")).performClick()
        compose.onNodeWithText("Importar DXF").assertIsDisplayed().performClick()

        imported shouldBe "p1"
    }

    @Test
    fun `S040 RF-1 el menu de la lista de objetos ofrece Importar DXF`() {
        var imported = 0
        compose.setContent {
            TopoPhoneTheme(EffectiveTheme.LIGHT) {
                ObjectListScreen(
                    ThemeState.DEFAULT,
                    {},
                    ObjectListUiState(loaded = true),
                    ObjectListActions(onImportDxf = { imported++ }),
                    onBack = {},
                )
            }
        }

        compose.onNodeWithTag(OBJECTS_MENU_TAG).performClick()
        compose.onNodeWithText("Importar DXF").assertIsDisplayed().performClick()

        imported shouldBe 1
    }

    @Test
    fun `S040 RF-1 los dos accesos conviven con Importar CSV o TXT`() {
        compose.setContent {
            TopoPhoneTheme(EffectiveTheme.LIGHT) {
                ProjectListScreen(ThemeState.DEFAULT, {}, ProjectListUiState(projects = listOf(project)), ProjectListActions())
            }
        }

        compose.onNodeWithTag(projectMenuTag("p1")).performClick()

        compose.onNodeWithText("Importar CSV/TXT").assertIsDisplayed()
        compose.onNodeWithText("Importar DXF").assertIsDisplayed()
    }

    @Test
    fun `S040 RF-1 la navegacion lleva a la pantalla del proyecto con el fichero elegido`() {
        var navigated: Pair<String, String>? = null
        val navigation = SurveyNavigation(onImportDxf = { projectId, fileUri -> navigated = projectId to fileUri })

        navigation.onImportDxf("p1", "content://docs/plano.dxf")

        navigated shouldBe ("p1" to "content://docs/plano.dxf")
    }

    @Test
    fun `S040 RF-1 la pagina de la importacion se pinta con su estado`() {
        compose.setContent {
            TopoPhoneTheme(EffectiveTheme.LIGHT) {
                SurveyPageScreen(
                    SurveyPage.ImportDxf("p1", "content://docs/plano.dxf"),
                    ThemeState.DEFAULT,
                    {},
                    SurveyUiState(cadImport = CadImportUiState(stage = CadImportStage.PROBLEM, fileName = "plano.dxf")),
                    SurveyNavigation(),
                )
            }
        }

        compose.onNodeWithText("plano.dxf").assertIsDisplayed()
        compose.onNodeWithText("Importar DXF").assertIsDisplayed()
    }
}
