package es.topophone.app.ui.gpkg

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.app.ui.ImportKmlDestination
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
import kotlinx.serialization.json.Json
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Los accesos a «Importar KML/KMZ» desde los menús y la navegación a su pantalla (S031/RF-1). Interfaz en es-ES. */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "es-rES")
class KmlImportEntriesTest {

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
    fun `S031 RF-1 el menu de un proyecto de la lista ofrece Importar KML o KMZ`() {
        var imported: String? = null
        compose.setContent {
            TopoPhoneTheme(EffectiveTheme.LIGHT) {
                ProjectListScreen(
                    ThemeState.DEFAULT,
                    {},
                    ProjectListUiState(projects = listOf(project)),
                    ProjectListActions(onImportKml = { imported = it }),
                )
            }
        }

        compose.onNodeWithTag(projectMenuTag("p1")).performClick()
        compose.onNodeWithText("Importar KML/KMZ").performScrollTo().assertIsDisplayed().performClick()

        imported shouldBe "p1"
    }

    @Test
    fun `S031 RF-1 el menu de la lista de objetos ofrece Importar KML o KMZ`() {
        var imported = 0
        compose.setContent {
            TopoPhoneTheme(EffectiveTheme.LIGHT) {
                ObjectListScreen(
                    ThemeState.DEFAULT,
                    {},
                    ObjectListUiState(loaded = true),
                    ObjectListActions(onImportKml = { imported++ }),
                    onBack = {},
                )
            }
        }

        compose.onNodeWithTag(OBJECTS_MENU_TAG).performClick()
        compose.onNodeWithText("Importar KML/KMZ").performScrollTo().assertIsDisplayed().performClick()

        imported shouldBe 1
    }

    @Test
    fun `S031 RF-1 el acceso convive con Importar GeoPackage y Importar Shapefile`() {
        compose.setContent {
            TopoPhoneTheme(EffectiveTheme.LIGHT) {
                ProjectListScreen(ThemeState.DEFAULT, {}, ProjectListUiState(projects = listOf(project)), ProjectListActions())
            }
        }

        compose.onNodeWithTag(projectMenuTag("p1")).performClick()

        compose.onNodeWithText("Importar GeoPackage").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Importar Shapefile").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Importar KML/KMZ").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `S031 RF-1 la navegacion lleva a la pantalla del proyecto con el fichero elegido`() {
        var navigated: Pair<String, String>? = null
        val navigation = SurveyNavigation(onImportKml = { projectId, uri -> navigated = projectId to uri })

        navigation.onImportKml("p1", "content://docs/obra.kmz")

        navigated shouldBe ("p1" to "content://docs/obra.kmz")
    }

    @Test
    fun `S031 RF-1 la direccion de la importacion sobrevive a serializarla`() {
        val destination = ImportKmlDestination("p1", "content://docs/obra%20norte.kmz")

        val json = Json.encodeToString(ImportKmlDestination.serializer(), destination)

        Json.decodeFromString(ImportKmlDestination.serializer(), json) shouldBe destination
    }

    @Test
    fun `S031 RF-1 la pagina de la importacion se pinta con su estado`() {
        compose.setContent {
            TopoPhoneTheme(EffectiveTheme.LIGHT) {
                SurveyPageScreen(
                    SurveyPage.ImportKml("p1", "content://docs/obra.kmz"),
                    ThemeState.DEFAULT,
                    {},
                    SurveyUiState(
                        gpkgImport = GpkgImportUiState(
                            stage = GpkgImportStage.PROBLEM,
                            format = ImportFormat.KML,
                            fileName = "obra.kmz",
                            problem = GpkgFileProblem.NOT_KML,
                        ),
                    ),
                    SurveyNavigation(),
                )
            }
        }

        compose.onNodeWithText("obra.kmz").assertIsDisplayed()
        compose.onNodeWithText("El fichero no es un KML ni un KMZ.").assertIsDisplayed()
        compose.onNodeWithTag(GPKG_PROBLEM_TAG).assertIsDisplayed()
    }
}
