package es.topophone.app.ui.alignment

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.app.ui.collector.COLLECT_BUTTON_TAG
import es.topophone.app.ui.collector.CollectorUiState
import es.topophone.app.ui.projects.ProjectViewScreen
import es.topophone.app.ui.projects.ProjectViewUiState
import es.topophone.app.ui.stakeout.AXIS_PANEL_TAG
import es.topophone.core.designsystem.theme.TopoPhoneTheme
import es.topophone.core.model.preferences.EffectiveTheme
import es.topophone.core.model.preferences.ThemeState
import es.topophone.core.model.project.AngularUnit
import es.topophone.core.model.project.CorrectionsDatum
import es.topophone.core.model.project.LinearUnit
import es.topophone.core.model.project.Project
import es.topophone.core.model.project.ProjectCrs
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** «Analizar» ocupa el hueco del recolector en la vista del proyecto, sin Recolectar ni el replanteo de un eje (S054/RF-12). */
@RunWith(AndroidJUnit4::class)
class AxisAnalysisEntriesTest {

    @get:Rule
    val compose = createComposeRule()

    private val project = Project(
        id = "p1",
        name = "Obra",
        author = "",
        description = "",
        crs = ProjectCrs.ETRS89_UTM30,
        linearUnit = LinearUnit.METRE,
        angularUnit = AngularUnit.DECIMAL_DEGREES,
        correctionsDatum = CorrectionsDatum.ETRS89,
        folderId = null,
        createdAtMs = 1_000,
        modifiedAtMs = 1_000,
        openedAtMs = null,
        deletedAtMs = null,
    )

    private fun show(analysis: AxisAnalysisUiState?) = compose.setContent {
        TopoPhoneTheme(EffectiveTheme.LIGHT) {
            ProjectViewScreen(
                ThemeState.DEFAULT,
                {},
                ProjectViewUiState(project),
                onBack = {},
                collector = CollectorUiState(loaded = true),
                axisAnalysis = analysis,
            )
        }
    }

    @Test
    fun `S054 RF-12 la vista del proyecto muestra el panel de Analizar en el hueco del recolector y esconde Recolectar`() {
        show(AxisAnalysisUiState(project = project, loaded = true, alignmentName = "Eje A", off = null))

        compose.onNodeWithTag(ANALYSIS_PANEL_TAG).assertIsDisplayed()
        compose.onAllNodesWithTag(COLLECT_BUTTON_TAG).assertCountEquals(0)
        compose.onAllNodesWithTag(AXIS_PANEL_TAG).assertCountEquals(0)
    }

    @Test
    fun `S054 RF-12 sin analisis abierto la vista del proyecto ofrece Recolectar y no muestra el panel`() {
        show(null)

        compose.onAllNodesWithTag(ANALYSIS_PANEL_TAG).assertCountEquals(0)
        compose.onNodeWithTag(COLLECT_BUTTON_TAG).assertIsDisplayed()
    }
}
