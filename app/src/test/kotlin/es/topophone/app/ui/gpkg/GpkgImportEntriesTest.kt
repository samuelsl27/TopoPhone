package es.topophone.app.ui.gpkg

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
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

/** Los accesos a «Importar GeoPackage» desde los dos menús y la navegación a su pantalla (S048/RF-1). Interfaz en es-ES. */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "es-rES")
class GpkgImportEntriesTest {

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
    fun `S048 RF-1 el menu de un proyecto de la lista ofrece Importar GeoPackage`() {
        var imported: String? = null
        compose.setContent {
            TopoPhoneTheme(EffectiveTheme.LIGHT) {
                ProjectListScreen(
                    ThemeState.DEFAULT,
                    {},
                    ProjectListUiState(projects = listOf(project)),
                    ProjectListActions(onImportGpkg = { imported = it }),
                )
            }
        }

        compose.onNodeWithTag(projectMenuTag("p1")).performClick()
        compose.onNodeWithText("Importar GeoPackage").performScrollTo().assertIsDisplayed().performClick()

        imported shouldBe "p1"
    }

    @Test
    fun `S048 RF-1 el menu de la lista de objetos ofrece Importar GeoPackage`() {
        var imported = 0
        compose.setContent {
            TopoPhoneTheme(EffectiveTheme.LIGHT) {
                ObjectListScreen(
                    ThemeState.DEFAULT,
                    {},
                    ObjectListUiState(loaded = true),
                    ObjectListActions(onImportGpkg = { imported++ }),
                    onBack = {},
                )
            }
        }

        compose.onNodeWithTag(OBJECTS_MENU_TAG).performClick()
        compose.onNodeWithText("Importar GeoPackage").performScrollTo().assertIsDisplayed().performClick()

        imported shouldBe 1
    }

    @Test
    fun `S048 RF-1 los accesos conviven con Importar DXF y Importar superficie`() {
        compose.setContent {
            TopoPhoneTheme(EffectiveTheme.LIGHT) {
                ProjectListScreen(ThemeState.DEFAULT, {}, ProjectListUiState(projects = listOf(project)), ProjectListActions())
            }
        }

        compose.onNodeWithTag(projectMenuTag("p1")).performClick()

        compose.onNodeWithText("Importar DXF").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Importar superficie").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Importar GeoPackage").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `S048 RF-1 la navegacion lleva a la pantalla del proyecto con el fichero elegido`() {
        var navigated: Pair<String, String>? = null
        val navigation = SurveyNavigation(onImportGpkg = { projectId, fileUri -> navigated = projectId to fileUri })

        navigation.onImportGpkg("p1", "content://docs/capas.gpkg")

        navigated shouldBe ("p1" to "content://docs/capas.gpkg")
    }

    @Test
    fun `S048 RF-1 la pagina de la importacion se pinta con su estado`() {
        compose.setContent {
            TopoPhoneTheme(EffectiveTheme.LIGHT) {
                SurveyPageScreen(
                    SurveyPage.ImportGpkg("p1", "content://docs/capas.gpkg"),
                    ThemeState.DEFAULT,
                    {},
                    SurveyUiState(gpkgImport = GpkgImportUiState(stage = GpkgImportStage.PROBLEM, fileName = "capas.gpkg")),
                    SurveyNavigation(),
                )
            }
        }

        compose.onNodeWithText("capas.gpkg").assertIsDisplayed()
        compose.onNodeWithText("Importar GeoPackage").assertIsDisplayed()
    }
}
