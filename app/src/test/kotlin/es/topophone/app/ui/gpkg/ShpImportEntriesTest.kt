package es.topophone.app.ui.gpkg

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.app.ui.ImportShpDestination
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

/** Los accesos a «Importar Shapefile» desde los menús y la navegación a su pantalla con varios ficheros (S030/RF-1, RF-3, RF-4). Interfaz en es-ES. */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "es-rES")
class ShpImportEntriesTest {

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
    fun `S030 RF-1 el menu de un proyecto de la lista ofrece Importar Shapefile`() {
        var imported: String? = null
        compose.setContent {
            TopoPhoneTheme(EffectiveTheme.LIGHT) {
                ProjectListScreen(
                    ThemeState.DEFAULT,
                    {},
                    ProjectListUiState(projects = listOf(project)),
                    ProjectListActions(onImportShp = { imported = it }),
                )
            }
        }

        compose.onNodeWithTag(projectMenuTag("p1")).performClick()
        compose.onNodeWithText("Importar Shapefile").performScrollTo().assertIsDisplayed().performClick()

        imported shouldBe "p1"
    }

    @Test
    fun `S030 RF-1 el menu de la lista de objetos ofrece Importar Shapefile`() {
        var imported = 0
        compose.setContent {
            TopoPhoneTheme(EffectiveTheme.LIGHT) {
                ObjectListScreen(
                    ThemeState.DEFAULT,
                    {},
                    ObjectListUiState(loaded = true),
                    ObjectListActions(onImportShp = { imported++ }),
                    onBack = {},
                )
            }
        }

        compose.onNodeWithTag(OBJECTS_MENU_TAG).performClick()
        compose.onNodeWithText("Importar Shapefile").performScrollTo().assertIsDisplayed().performClick()

        imported shouldBe 1
    }

    @Test
    fun `S030 RF-1 el acceso convive con Importar GeoPackage y Importar DXF`() {
        compose.setContent {
            TopoPhoneTheme(EffectiveTheme.LIGHT) {
                ProjectListScreen(ThemeState.DEFAULT, {}, ProjectListUiState(projects = listOf(project)), ProjectListActions())
            }
        }

        compose.onNodeWithTag(projectMenuTag("p1")).performClick()

        compose.onNodeWithText("Importar DXF").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Importar GeoPackage").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Importar Shapefile").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `S030 RF-3 y RF-4 la navegacion lleva a la pantalla del proyecto con uno o varios ficheros elegidos`() {
        var navigated: Pair<String, List<String>>? = null
        val navigation = SurveyNavigation(onImportShp = { projectId, uris -> navigated = projectId to uris })

        navigation.onImportShp("p1", listOf("content://docs/parcelas.zip"))
        navigated shouldBe ("p1" to listOf("content://docs/parcelas.zip"))
        navigation.onImportShp("p1", listOf("content://docs/a.shp", "content://docs/a.dbf", "content://docs/a.prj"))
        navigated shouldBe ("p1" to listOf("content://docs/a.shp", "content://docs/a.dbf", "content://docs/a.prj"))
    }

    @Test
    fun `S030 RF-1 la direccion de la importacion lleva la lista de ficheros y sobrevive a serializarla`() {
        val destination = ImportShpDestination("p1", listOf("content://docs/a.shp", "content://docs/a%20b.dbf"))

        val json = Json.encodeToString(ImportShpDestination.serializer(), destination)

        Json.decodeFromString(ImportShpDestination.serializer(), json) shouldBe destination
    }

    @Test
    fun `S030 RF-1 la pagina de la importacion se pinta con su estado`() {
        compose.setContent {
            TopoPhoneTheme(EffectiveTheme.LIGHT) {
                SurveyPageScreen(
                    SurveyPage.ImportShp("p1", listOf("content://docs/parcelas.zip")),
                    ThemeState.DEFAULT,
                    {},
                    SurveyUiState(
                        gpkgImport = GpkgImportUiState(
                            stage = GpkgImportStage.PROBLEM,
                            format = ImportFormat.SHAPEFILE,
                            fileName = "parcelas.zip",
                            problem = GpkgFileProblem.NO_SHP,
                        ),
                    ),
                    SurveyNavigation(),
                )
            }
        }

        compose.onNodeWithText("parcelas.zip").assertIsDisplayed()
        compose.onNodeWithText("Falta el fichero .shp. Elige también el .shp junto a sus ficheros, o el ZIP entero.").assertIsDisplayed()
    }
}
