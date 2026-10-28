package es.topophone.app.ui.alignment

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.app.ui.collector.COLLECTOR_MEASURE_TAG
import es.topophone.app.ui.collector.CollectorActions
import es.topophone.app.ui.collector.CollectorUiState
import es.topophone.app.ui.stakeout.GuidanceOff
import es.topophone.core.designsystem.theme.TopoPhoneTheme
import es.topophone.core.model.point.AxisHeightRef
import es.topophone.core.model.preferences.EffectiveTheme
import es.topophone.core.model.project.AngularUnit
import es.topophone.core.model.project.CorrectionsDatum
import es.topophone.core.model.project.LinearUnit
import es.topophone.core.model.project.Project
import es.topophone.core.model.project.ProjectCrs
import es.topophone.core.survey.AxisAnalysisValue
import es.topophone.core.survey.AxisHeightDiff
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * El panel de «Analizar» con estados ya hechos: el PK y el desplazamiento con su palabra, la diferencia de cota con la rasante o con la
 * superficie, la elección de la referencia, la punta sin proyección sobre el eje y por qué no hay análisis
 * (S054/RF-12, RF-14; RNF-T7, RNF-T8).
 */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "es-rES")
class AxisAnalysisPanelTest {

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

    private fun state(
        analysis: AxisAnalysisValue? = projected(),
        reference: AxisHeightRef? = AxisHeightRef.PROFILE,
        hasProfile: Boolean = true,
        hasSurface: Boolean = false,
        off: GuidanceOff? = null,
    ) = AxisAnalysisUiState(
        project = project,
        loaded = true,
        alignmentId = "a1",
        alignmentName = "Eje A",
        hasProfile = hasProfile,
        hasSurface = hasSurface,
        reference = reference,
        analysis = analysis,
        off = off,
    )

    private fun projected(
        chainage: Double = 1240.5,
        offset: Double = 3.5,
        ref: AxisHeightRef? = AxisHeightRef.PROFILE,
        height: AxisHeightDiff = AxisHeightDiff.Diff(102.0, 0.12),
    ) = AxisAnalysisValue.Projected(chainage, offset, ref, height)

    private val calls = mutableListOf<String>()

    private fun show(state: AxisAnalysisUiState, collector: CollectorUiState? = CollectorUiState(loaded = true)) = compose.setContent {
        TopoPhoneTheme(EffectiveTheme.LIGHT) {
            AxisAnalysisPanel(
                state,
                AxisAnalysisActions(onClose = { calls += "close" }, onReference = { calls += "ref:${it.name}" }),
                collector = collector,
                collectorActions = CollectorActions(onMeasure = { calls += "measure" }),
            )
        }
    }

    @Test
    fun `S054 RF-12 muestra el eje, el PK y el desplazamiento con la palabra derecha`() {
        show(state())

        compose.onNodeWithTag(ANALYSIS_TITLE_TAG).assertTextContains("Eje A", substring = true)
        compose.onNodeWithTag(ANALYSIS_PK_TAG, useUnmergedTree = true).assertTextContains("1+240,50", substring = true)
        compose.onNodeWithTag(
            ANALYSIS_OFFSET_TAG,
            useUnmergedTree = true,
        ).assertTextContains("3,500 m a la derecha del eje", substring = true)
    }

    @Test
    fun `S054 RNF-T8 a la izquierda y sobre el eje tambien se dicen con palabras`() {
        show(state(projected(offset = -2.0)))
        compose.onNodeWithTag(
            ANALYSIS_OFFSET_TAG,
            useUnmergedTree = true,
        ).assertTextContains("2,000 m a la izquierda del eje", substring = true)
    }

    @Test
    fun `S054 RF-12 la diferencia de cota con la rasante dice la referencia, su cota y si esta por encima`() {
        show(state())

        compose.onNodeWithTag(ANALYSIS_DIFF_TAG, useUnmergedTree = true).assertTextContains("rasante", substring = true)
        compose.onNodeWithTag(ANALYSIS_DIFF_TAG, useUnmergedTree = true).assertTextContains("+0,120 m", substring = true)
        compose.onNodeWithTag(ANALYSIS_DIFF_TAG, useUnmergedTree = true).assertTextContains("por encima", substring = true)
        compose.onNodeWithTag(ANALYSIS_REFERENCE_ELEVATION_TAG, useUnmergedTree = true).assertTextContains("102,000 m", substring = true)
    }

    @Test
    fun `S054 RF-12 por debajo de la superficie se dice por debajo y con su signo`() {
        show(state(projected(ref = AxisHeightRef.SURFACE, height = AxisHeightDiff.Diff(100.0, -0.35)), reference = AxisHeightRef.SURFACE))

        compose.onNodeWithTag(ANALYSIS_DIFF_TAG, useUnmergedTree = true).assertTextContains("superficie", substring = true)
        compose.onNodeWithTag(ANALYSIS_DIFF_TAG, useUnmergedTree = true).assertTextContains("-0,350 m", substring = true)
        compose.onNodeWithTag(ANALYSIS_DIFF_TAG, useUnmergedTree = true).assertTextContains("por debajo", substring = true)
    }

    @Test
    fun `S054 RF-12 una punta sin altura o fuera de la referencia lo dice y no da diferencia`() {
        show(state(projected(height = AxisHeightDiff.Diff(102.0, null))))
        compose.onNodeWithTag(ANALYSIS_NOTE_TAG, useUnmergedTree = true).assertTextContains("no tiene altura", substring = true)
    }

    @Test
    fun `S054 RF-12 fuera de la superficie la nota lo dice`() {
        show(state(projected(ref = AxisHeightRef.SURFACE, height = AxisHeightDiff.OutsideSurface), reference = AxisHeightRef.SURFACE))
        compose.onNodeWithTag(ANALYSIS_NOTE_TAG, useUnmergedTree = true).assertTextContains("fuera de la superficie", substring = true)
        compose.onAllNodesWithTag(ANALYSIS_DIFF_TAG, useUnmergedTree = true).assertCountEquals(0)
    }

    @Test
    fun `S054 RF-12 sin referencia solo se ven el PK y el desplazamiento`() {
        show(state(projected(ref = null, height = AxisHeightDiff.None), reference = null, hasProfile = false))

        compose.onNodeWithTag(ANALYSIS_PK_TAG, useUnmergedTree = true).assertExists()
        compose.onNodeWithTag(
            ANALYSIS_NOTE_TAG,
            useUnmergedTree = true,
        ).assertTextContains("solo el PK y el desplazamiento", substring = true)
        compose.onAllNodesWithTag(ANALYSIS_DIFF_TAG, useUnmergedTree = true).assertCountEquals(0)
    }

    @Test
    fun `S054 RF-12 con rasante y superficie se elige la referencia y la actual aparece elegida`() {
        show(state(hasSurface = true))

        compose.onNodeWithTag(ANALYSIS_REF_PROFILE_TAG).performScrollTo().assertIsSelected()
        compose.onNodeWithTag(ANALYSIS_REF_SURFACE_TAG).performClick()

        calls shouldBe listOf("ref:SURFACE")
    }

    @Test
    fun `S054 RF-12 con una sola referencia no se ofrece elegir`() {
        show(state(hasSurface = false))

        compose.onAllNodesWithTag(ANALYSIS_REF_PROFILE_TAG).assertCountEquals(0)
    }

    @Test
    fun `S054 RF-14 sin proyeccion sobre el eje lo dice y no muestra PK ni desplazamiento`() {
        show(state(AxisAnalysisValue.NoProjection))

        compose.onNodeWithTag(ANALYSIS_NO_PROJECTION_TAG).assertTextContains("antes del inicio o después del final", substring = true)
        compose.onAllNodesWithTag(ANALYSIS_PK_TAG, useUnmergedTree = true).assertCountEquals(0)
        compose.onAllNodesWithTag(ANALYSIS_OFFSET_TAG, useUnmergedTree = true).assertCountEquals(0)
    }

    @Test
    fun `S054 RF-12 sin posicion dice por que no hay analisis`() {
        show(state(analysis = null, off = GuidanceOff.NO_DATA))

        compose.onNodeWithTag(ANALYSIS_OFF_TAG).assertIsDisplayed()
        compose.onAllNodesWithTag(ANALYSIS_PK_TAG, useUnmergedTree = true).assertCountEquals(0)
    }

    @Test
    fun `S054 RNF-T8 el resultado lleva una frase para TalkBack`() {
        show(state())

        compose.onNodeWithTag(ANALYSIS_RESULT_TAG).assertTextContains("1+240,50", substring = true)
    }

    @Test
    fun `S054 RF-13 se mide con el boton de medir del recolector y cerrar llega a la accion`() {
        show(state())

        compose.onNodeWithTag(COLLECTOR_MEASURE_TAG).assertIsDisplayed().performClick()
        compose.onNodeWithTag(ANALYSIS_CLOSE_TAG).performClick()

        calls shouldBe listOf("measure", "close")
    }
}
