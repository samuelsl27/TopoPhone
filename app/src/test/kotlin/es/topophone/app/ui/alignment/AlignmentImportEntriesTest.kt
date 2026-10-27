package es.topophone.app.ui.alignment

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

/** Los accesos a «Importar eje» desde la lista de proyectos y la lista de objetos, el diálogo de origen y su navegación (S049/RF-1). */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "es-rES")
class AlignmentImportEntriesTest {

    @get:Rule
    val compose = createComposeRule()

    private val project = Project(
        id = "p1",
        name = "Camino de la Dehesa",
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
    fun `S049 RF-1 el menu de un proyecto de la lista ofrece Importar eje`() {
        var imported: String? = null
        compose.setContent {
            TopoPhoneTheme(EffectiveTheme.LIGHT) {
                ProjectListScreen(
                    ThemeState.DEFAULT,
                    {},
                    ProjectListUiState(projects = listOf(project)),
                    ProjectListActions(onImportAlignment = { imported = it }),
                )
            }
        }

        compose.onNodeWithTag(projectMenuTag("p1")).performClick()
        compose.onNodeWithText("Importar eje").assertIsDisplayed().performClick()

        imported shouldBe "p1"
    }

    @Test
    fun `S049 RF-1 el menu de la lista de objetos ofrece Importar eje`() {
        var imported = 0
        compose.setContent {
            TopoPhoneTheme(EffectiveTheme.LIGHT) {
                ObjectListScreen(
                    ThemeState.DEFAULT,
                    {},
                    ObjectListUiState(loaded = true),
                    ObjectListActions(onImportAlignment = { imported++ }),
                    onBack = {},
                )
            }
        }

        compose.onNodeWithTag(OBJECTS_MENU_TAG).performClick()
        compose.onNodeWithText("Importar eje").performScrollTo().assertIsDisplayed().performClick()

        imported shouldBe 1
    }

    @Test
    fun `S049 RF-1 el acceso convive con Importar superficie y Importar GeoPackage`() {
        compose.setContent {
            TopoPhoneTheme(EffectiveTheme.LIGHT) {
                ProjectListScreen(ThemeState.DEFAULT, {}, ProjectListUiState(projects = listOf(project)), ProjectListActions())
            }
        }

        compose.onNodeWithTag(projectMenuTag("p1")).performClick()

        compose.onNodeWithText("Importar superficie").assertIsDisplayed()
        compose.onNodeWithText("Importar GeoPackage").assertIsDisplayed()
        compose.onNodeWithText("Importar eje").assertIsDisplayed()
    }

    @Test
    fun `S049 RF-1 el dialogo de origen ofrece un LandXML y una polilinea de un dibujo`() {
        var opened: String? = null
        val navigation = SurveyNavigation(onOpenProject = { opened = it })
        var launch: ((String) -> Unit)? = null
        compose.setContent {
            TopoPhoneTheme(EffectiveTheme.LIGHT) { launch = rememberImportAlignmentLauncher(navigation) }
        }

        compose.runOnIdle { launch!!("p1") }
        compose.onNodeWithTag(ALIGNMENT_SOURCE_LANDXML_TAG).assertIsDisplayed()
        compose.onNodeWithTag(ALIGNMENT_SOURCE_DRAWING_TAG).assertIsDisplayed().performClick()
        compose.onNodeWithText("Convertir en eje", substring = true).assertIsDisplayed()
        compose.onNodeWithTag(ALIGNMENT_SOURCE_DRAWING_OPEN_TAG).performClick()

        opened shouldBe "p1"
        compose.onNodeWithTag(ALIGNMENT_SOURCE_DRAWING_TAG).assertDoesNotExist()
    }

    @Test
    fun `S049 RF-1 cancelar el dialogo no navega a ninguna parte`() {
        var navigated = 0
        val navigation = SurveyNavigation(onOpenProject = { navigated++ }, onImportAlignment = { _, _ -> navigated++ })
        var launch: ((String) -> Unit)? = null
        compose.setContent {
            TopoPhoneTheme(EffectiveTheme.LIGHT) { launch = rememberImportAlignmentLauncher(navigation) }
        }

        compose.runOnIdle { launch!!("p1") }
        compose.onNodeWithTag(ALIGNMENT_SOURCE_CANCEL_TAG).performClick()

        navigated shouldBe 0
    }

    @Test
    fun `S049 RF-1 la navegacion lleva a la pantalla del proyecto con el fichero elegido`() {
        var navigated: Pair<String, String>? = null
        val navigation = SurveyNavigation(onImportAlignment = { projectId, fileUri -> navigated = projectId to fileUri })

        navigation.onImportAlignment("p1", "content://docs/ejes.xml")

        navigated shouldBe ("p1" to "content://docs/ejes.xml")
    }
}
