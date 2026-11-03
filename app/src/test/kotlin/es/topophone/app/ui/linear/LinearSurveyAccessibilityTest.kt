package es.topophone.app.ui.linear

import android.view.View
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.core.designsystem.theme.TopoPhoneTheme
import es.topophone.core.model.alignment.Alignment
import es.topophone.core.model.alignment.AlignmentElement
import es.topophone.core.model.alignment.AlignmentExtent
import es.topophone.core.model.alignment.AlignmentSource
import es.topophone.core.model.preferences.EffectiveTheme
import es.topophone.core.model.project.LinearUnit
import es.topophone.core.model.survey.linear.LinearSurvey
import es.topophone.core.model.survey.linear.LinearSurveyKind
import es.topophone.core.model.survey.linear.SurveyFlag
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Los tres paneles de toma (eje, perfil longitudinal y perfil transversal) con la pantalla encendida mientras están abiertos, con todos sus
 * botones de 56 dp como mínimo y con lo que cambia mientras se mide anunciado a TalkBack (S052; RNF-T2, RNF-T8).
 */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "es-rES")
class LinearSurveyAccessibilityTest {

    @get:Rule
    val compose = createComposeRule()

    private var view: View? = null

    private val axis = Alignment(
        "a1", "p1", "Eje A", null, 0L, AlignmentSource.MANUAL, listOf(AlignmentElement.line(0.0, 420.0, 440_000.0, 4_474_000.0, 1.0)),
        null, true, AlignmentExtent(0.0, 1.0, 0.0, 1.0),
    )

    private fun survey(kind: LinearSurveyKind) = LinearSurvey("t1", "p1", kind, "a1", "Eje A", chainage = 105.0, createdAtMs = 1)

    private val axisSurvey = AxisSurveyUiState(
        loading = false,
        survey = survey(LinearSurveyKind.AXIS),
        rows = listOf(
            AxisSurveyRow("i1", "T1", 440_000.0, 4_474_000.0, 100.0, 40.0, -3.7, true),
            AxisSurveyRow("i2", "T2", 440_100.0, 4_474_000.0, 101.0, 40.0, -3.7, true),
        ),
        lengthM = 100.0,
        canGrade = true,
    )

    private fun longRow(i: Int, flag: SurveyFlag = SurveyFlag.NONE) =
        LongitudinalRow("l$i", "L$i", i * 100.0, 0.1, 100.0 + i, 100.0, i.toDouble(), flag, true)

    private val longitudinal = LongitudinalUiState(
        loading = false,
        alignment = axis,
        hasGrade = true,
        survey = survey(LinearSurveyKind.LONGITUDINAL),
        rows = listOf(longRow(1), longRow(2), longRow(3, SurveyFlag.OUT_OF_AXIS)),
        chart = ProfileChart(
            ProfileAxis.CHAINAGE,
            listOf(
                ProfileChartPoint("L1", 100.0, 101.0, 100.0, 1.0, true, SurveyFlag.NONE),
                ProfileChartPoint("L2", 200.0, 102.0, 100.0, 2.0, true, SurveyFlag.NONE),
            ),
            emptyList(),
        ),
    )

    private fun crossRow(i: Int) = CrossRow("c$i", "X$i", 105.0, i - 2.0, 100.0 + i, 100.0, i.toDouble(), SurveyFlag.NONE, true, null)

    private val cross = CrossUiState(
        loading = false,
        alignment = axis,
        survey = survey(LinearSurveyKind.CROSS),
        rows = listOf(crossRow(1), crossRow(2), crossRow(3)),
        chart = ProfileChart(
            ProfileAxis.OFFSET,
            listOf(ProfileChartPoint("X1", -1.0, 101.0, 100.0, 1.0, true, SurveyFlag.NONE)),
            emptyList(),
        ),
        hasDesign = true,
    )

    private val liveRegion = SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite)

    private fun show(content: @androidx.compose.runtime.Composable () -> Unit) = compose.setContent {
        view = LocalView.current
        TopoPhoneTheme(EffectiveTheme.LIGHT) { content() }
    }

    private fun assertAllButtonsAre56dp() {
        val buttons = compose.onAllNodes(hasClickAction())
        val count = buttons.fetchSemanticsNodes().size
        (count > 0) shouldBe true
        repeat(count) { buttons[it].assertHeightIsAtLeast(56.dp) }
    }

    @Test
    fun `S052 RNF-T2 la pantalla no se apaga mientras el panel de tomar eje esta abierto`() {
        var shown by mutableStateOf(true)
        show { if (shown) AxisSurveyPanel(axisSurvey, AxisSurveyActions(onClose = { shown = false })) }
        compose.waitForIdle()
        view?.keepScreenOn shouldBe true

        compose.onNodeWithTag(AXIS_SURVEY_CLOSE_TAG).performClick()
        compose.waitForIdle()
        view?.keepScreenOn shouldBe false
    }

    @Test
    fun `S052 RNF-T2 la pantalla no se apaga mientras el panel del perfil longitudinal esta abierto`() {
        var shown by mutableStateOf(true)
        show { if (shown) LongitudinalPanel(longitudinal, "Obra", LongitudinalActions(onClose = { shown = false }), { true }) }
        compose.waitForIdle()
        view?.keepScreenOn shouldBe true

        compose.onNodeWithTag(LONGITUDINAL_CLOSE_TAG).performClick()
        compose.waitForIdle()
        view?.keepScreenOn shouldBe false
    }

    @Test
    fun `S052 RNF-T2 la pantalla no se apaga mientras el panel del perfil transversal esta abierto`() {
        var shown by mutableStateOf(true)
        show { if (shown) CrossPanel(cross, "Obra", CrossActions(onClose = { shown = false }), { true }) }
        compose.waitForIdle()
        view?.keepScreenOn shouldBe true

        compose.onNodeWithTag(CROSS_CLOSE_TAG).performClick()
        compose.waitForIdle()
        view?.keepScreenOn shouldBe false
    }

    @Test
    fun `S052 RNF-T8 todos los botones del panel de tomar eje miden 56 dp`() {
        show { AxisSurveyPanel(axisSurvey.copy(finish = AxisFinishDraft(name = "A")), AxisSurveyActions()) }

        assertAllButtonsAre56dp()
    }

    @Test
    fun `S052 RNF-T8 todos los botones del panel del perfil longitudinal miden 56 dp`() {
        show { LongitudinalPanel(longitudinal, "Obra", LongitudinalActions(), { true }) }

        assertAllButtonsAre56dp()
    }

    @Test
    fun `S052 RNF-T8 todos los botones del panel del perfil transversal miden 56 dp`() {
        show { CrossPanel(cross, "Obra", CrossActions(), { true }) }

        assertAllButtonsAre56dp()
    }

    @Test
    fun `S052 RNF-T8 antes de empezar cada panel tiene tambien todos sus botones de 56 dp`() {
        show {
            AxisSurveyPanel(AxisSurveyUiState(loading = false), AxisSurveyActions())
            LongitudinalPanel(LongitudinalUiState(loading = false, alignment = axis), "Obra", LongitudinalActions(), { true })
            CrossPanel(CrossUiState(loading = false, alignment = axis), "Obra", CrossActions(), { true })
        }

        assertAllButtonsAre56dp()
    }

    @Test
    fun `S052 RNF-T8 lo que cambia mientras se mide se anuncia como region viva`() {
        show {
            AxisSurveyPanel(axisSurvey.copy(problem = AxisSurveyProblem.TooFewPoints), AxisSurveyActions())
            LongitudinalPanel(longitudinal.copy(problem = LongitudinalProblem.NoPoints), "Obra", LongitudinalActions(), { true })
        }

        compose.onNodeWithTag(AXIS_SURVEY_SUMMARY_TAG).assert(liveRegion)
        compose.onNodeWithTag(AXIS_SURVEY_PROBLEM_TAG).assert(liveRegion)
        compose.onNodeWithTag(LONGITUDINAL_SUMMARY_TAG).assert(liveRegion)
        compose.onNodeWithTag(LONGITUDINAL_PROBLEM_TAG).assert(liveRegion)
        compose.onNodeWithTag(PROFILE_SELECTED_TAG).assert(liveRegion)
    }

    @Test
    fun `S052 RNF-T8 el resumen del perfil transversal y el texto del punto elegido son regiones vivas`() {
        show { CrossPanel(cross.copy(problem = CrossProblem.NoPoints), "Obra", CrossActions(), { true }) }

        compose.onNodeWithTag(CROSS_SUMMARY_TAG).assert(liveRegion)
        compose.onNodeWithTag(CROSS_PROBLEM_TAG).assert(liveRegion)
        compose.onNodeWithTag(PROFILE_SELECTED_TAG).assert(liveRegion)
    }

    @Test
    fun `S052 RNF-T8 el grafico se lee con una frase y no como un dibujo`() {
        show { ProfileView(longitudinal.chart, LinearUnit.METRE) }

        val config = compose.onNodeWithTag(PROFILE_CANVAS_TAG).fetchSemanticsNode().config
        val description = config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString(" ").orEmpty()
        description.contains("Perfil con 2 puntos") shouldBe true
    }
}
