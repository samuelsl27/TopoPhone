package es.topophone.app.ui.export

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.app.ui.options.ProjectOption
import es.topophone.app.ui.options.chooseOption
import es.topophone.app.ui.points.ObjectListActions
import es.topophone.app.ui.points.ObjectListScreen
import es.topophone.app.ui.points.ObjectListUiState
import es.topophone.app.ui.points.PointFixtures.measured
import es.topophone.app.ui.points.SELECTION_EXPORT_KML_TAG
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

/** «Exportar KML/KMZ» en el menú de un proyecto, en el de su lista de objetos y con una selección (S031/RF-20). Interfaz en es-ES. */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "es-rES")
class KmlExportEntriesTest {

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
    fun `S031 RF-20 el menu de un proyecto ofrece Exportar KML y KMZ`() {
        var exported: String? = null
        compose.setContent {
            TopoPhoneTheme(EffectiveTheme.LIGHT) {
                ProjectListScreen(
                    ThemeState.DEFAULT,
                    {},
                    ProjectListUiState(projects = listOf(project)),
                    ProjectListActions(onExportKml = { exported = it }),
                )
            }
        }

        compose.onNodeWithTag(projectMenuTag("p1")).performClick()
        compose.onNodeWithText("Exportar KML/KMZ").performClick()

        exported shouldBe "p1"
    }

    @Test
    fun `S031 RF-20 la lista de objetos exporta todo desde su menu y la seleccion desde su barra`() {
        val chosen = mutableListOf<String>()
        compose.setContent {
            TopoPhoneTheme(EffectiveTheme.LIGHT) {
                ObjectListScreen(
                    ThemeState.DEFAULT,
                    {},
                    ObjectListUiState(loaded = true, points = listOf(measured("1").summary()), total = 1, selected = setOf("id-1")),
                    ObjectListActions(
                        onExportKml = { chosen += "all" },
                        onExportKmlSelected = { chosen += "selected" },
                    ),
                    onBack = {},
                )
            }
        }

        compose.onNodeWithTag(SELECTION_EXPORT_KML_TAG).performClick()
        compose.chooseOption(ProjectOption.EXPORT_KML)

        chosen shouldBe listOf("selected", "all")
    }

    @Test
    fun `S031 RF-20 la navegacion lleva el proyecto y si es con seleccion`() {
        val calls = mutableListOf<Pair<String, Boolean>>()
        val navigation = SurveyNavigation(onExportKml = { projectId, selection -> calls += projectId to selection })

        navigation.onExportKml("p1", false)
        navigation.onExportKml("p1", true)

        calls shouldBe listOf("p1" to false, "p1" to true)
    }
}
