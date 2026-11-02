package es.topophone.app.ui.linear

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.core.designsystem.theme.TopoPhoneTheme
import es.topophone.core.model.preferences.EffectiveTheme
import es.topophone.core.model.project.LinearUnit
import es.topophone.core.model.survey.linear.LinearSurvey
import es.topophone.core.model.survey.linear.LinearSurveyKind
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * El panel de «Tomar eje» con estados ya hechos: empezar, el resumen con la longitud acumulada, quitar último, terminar con su formulario
 * de radios y rasante, los motivos de rechazo con el vértice y el eje creado (S052/RF-1, RF-3 a RF-7; RNF-T7, RNF-T8).
 */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "es-rES")
class AxisSurveyPanelTest {

    @get:Rule
    val compose = createComposeRule()

    private val survey = LinearSurvey("t1", "p1", LinearSurveyKind.AXIS, null, "", createdAtMs = 1)

    private fun row(i: Int) = AxisSurveyRow("id$i", "T$i", 440_000.0 + i, 4_474_000.0, 100.0 + i, 40.0, -3.7, true)

    private fun state(
        count: Int = 3,
        length: Double = 1234.5678,
        canGrade: Boolean = true,
        finish: AxisFinishDraft? = null,
        problem: AxisSurveyProblem? = null,
        result: AxisSurveyResult? = null,
        unit: LinearUnit = LinearUnit.METRE,
        withSurvey: Boolean = true,
    ) = AxisSurveyUiState(
        loading = false,
        unit = unit,
        survey = survey.takeIf { withSurvey },
        rows = List(count) { row(it + 1) },
        lengthM = length,
        canGrade = canGrade,
        finish = finish,
        problem = problem,
        result = result,
    )

    private val calls = mutableListOf<String>()

    private fun actions(draft: AxisFinishDraft? = null) = AxisSurveyActions(
        onClose = { calls += "close" },
        onBegin = { calls += "begin" },
        onRemoveLast = { calls += "removeLast" },
        onStartFinish = { calls += "startFinish:$it" },
        onFinishDraft = { change -> calls += "draft:" + change(draft ?: AxisFinishDraft()) },
        onCancelFinish = { calls += "cancelFinish" },
        onCreate = { calls += "create" },
        onDiscard = { calls += "discard" },
        onDismissResult = { calls += "dismissResult" },
    )

    private fun show(state: AxisSurveyUiState) = compose.setContent {
        TopoPhoneTheme(EffectiveTheme.LIGHT) { AxisSurveyPanel(state, actions(state.finish)) }
    }

    @Test
    fun `S052 RF-1 sin toma el panel explica y ofrece empezar con un boton de 56 dp`() {
        show(state(withSurvey = false, count = 0))

        compose.onNodeWithTag(AXIS_SURVEY_BEGIN_TAG).assertIsDisplayed().assertHeightIsAtLeast(56.dp).performClick()

        calls shouldBe listOf("begin")
        compose.onAllNodesWithTag(AXIS_SURVEY_SUMMARY_TAG).assertCountEquals(0)
    }

    @Test
    fun `S052 RF-3 el resumen dice cuantos puntos lleva la toma y su longitud acumulada y el ultimo punto`() {
        show(state(count = 3, length = 1234.5678))

        compose.onNodeWithTag(AXIS_SURVEY_SUMMARY_TAG).assertTextContains("3 puntos", substring = true)
        compose.onNodeWithTag(AXIS_SURVEY_SUMMARY_TAG).assertTextContains("1.234,568", substring = true)
        compose.onNodeWithTag(AXIS_SURVEY_SUMMARY_TAG).assertTextContains("m", substring = true)
        compose.onNodeWithTag(AXIS_SURVEY_LAST_TAG).assertTextContains("T3", substring = true)
    }

    @Test
    fun `S052 RF-3 la longitud sale en la unidad del proyecto`() {
        show(state(length = 100.0, unit = LinearUnit.INTERNATIONAL_FOOT))

        compose.onNodeWithTag(AXIS_SURVEY_SUMMARY_TAG).assertTextContains("328,084", substring = true)
        compose.onNodeWithTag(AXIS_SURVEY_SUMMARY_TAG).assertTextContains("ft", substring = true)
    }

    @Test
    fun `S052 RF-4 quitar ultimo descartar y cerrar avisan y todos los botones miden 56 dp`() {
        show(state())

        compose.onNodeWithTag(AXIS_SURVEY_REMOVE_LAST_TAG).assertHeightIsAtLeast(56.dp).performClick()
        compose.onNodeWithTag(AXIS_SURVEY_DISCARD_TAG).assertHeightIsAtLeast(56.dp).performClick()
        compose.onNodeWithTag(AXIS_SURVEY_CLOSE_TAG).assertHeightIsAtLeast(56.dp).performClick()

        calls shouldBe listOf("removeLast", "discard", "close")
    }

    @Test
    fun `S052 RF-5 terminar pide el formulario con el nombre propuesto en el idioma de la interfaz`() {
        show(state())

        compose.onNodeWithTag(AXIS_SURVEY_FINISH_TAG).assertHeightIsAtLeast(56.dp).performClick()

        calls shouldBe listOf("startFinish:Eje tomado")
    }

    @Test
    fun `S052 RF-4 con la toma vacia no se puede quitar ni terminar`() {
        show(state(count = 0))

        compose.onNodeWithTag(AXIS_SURVEY_REMOVE_LAST_TAG).assertIsNotEnabled()
        compose.onNodeWithTag(AXIS_SURVEY_FINISH_TAG).assertIsNotEnabled()
    }

    @Test
    fun `S052 RF-5 el formulario tiene nombre PK inicial y un radio por cada vertice interior`() {
        show(state(count = 5, finish = AxisFinishDraft(name = "Camino", startChainage = "0")))

        compose.onNodeWithTag(AXIS_SURVEY_NAME_TAG).assertIsDisplayed().assertHeightIsAtLeast(56.dp)
        compose.onNodeWithTag(AXIS_SURVEY_START_TAG).assertIsDisplayed()
        for (vertex in 2..4) compose.onNodeWithTag(axisSurveyRadiusTag(vertex)).performScrollTo().assertIsDisplayed()
        compose.onAllNodesWithTag(axisSurveyRadiusTag(1)).assertCountEquals(0)
        compose.onAllNodesWithTag(axisSurveyRadiusTag(5)).assertCountEquals(0)
    }

    @Test
    fun `S052 RF-5 escribir en el formulario cambia el borrador y crear y cancelar avisan`() {
        show(state(count = 3, finish = AxisFinishDraft(name = "Camino")))

        compose.onNodeWithTag(AXIS_SURVEY_NAME_TAG).performTextReplacement("Pista")
        compose.onNodeWithTag(axisSurveyRadiusTag(2)).performScrollTo().performTextReplacement("150")
        compose.onNodeWithTag(AXIS_SURVEY_CREATE_TAG).assertHeightIsAtLeast(56.dp).performClick()
        compose.onNodeWithTag(AXIS_SURVEY_CANCEL_TAG).performClick()

        val drafts = calls.filter { it.startsWith("draft:") }
        drafts.any { it.contains("name=Pista") } shouldBe true
        drafts.any { it.contains("radii={2=150}") } shouldBe true
        calls.takeLast(2) shouldBe listOf("create", "cancelFinish")
    }

    @Test
    fun `S052 RF-7 la rasante se puede pedir si todos los puntos tienen cota`() {
        show(state(finish = AxisFinishDraft(name = "A"), canGrade = true))

        compose.onNodeWithTag(AXIS_SURVEY_GRADE_TAG).performScrollTo().assertIsEnabled().performClick()

        calls.single().contains("withGrade=true") shouldBe true
    }

    @Test
    fun `S052 RF-7 sin cota en algun punto la casilla de rasante esta apagada y lo explica`() {
        show(state(finish = AxisFinishDraft(name = "A"), canGrade = false))

        compose.onNodeWithTag(AXIS_SURVEY_GRADE_TAG).performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag(AXIS_SURVEY_GRADE_TAG).assertTextContains("no se puede crear la rasante", substring = true)
    }

    @Test
    fun `S052 RF-6 cada motivo de rechazo dice el vertice cuando lo hay`() {
        var problem by mutableStateOf<AxisSurveyProblem?>(null)
        compose.setContent {
            TopoPhoneTheme(EffectiveTheme.LIGHT) { AxisSurveyPanel(state(problem = problem), actions()) }
        }
        val cases = listOf(
            AxisSurveyProblem.CoincidentPoints(2) to "vértice 2",
            AxisSurveyProblem.RadiusDoesNotFit(3) to "vértice 3",
            AxisSurveyProblem.InvalidRadius(4) to "vértice 4",
            AxisSurveyProblem.InvalidNumber(2) to "vértice 2",
            AxisSurveyProblem.TooFewPoints to "al menos 2 puntos",
            AxisSurveyProblem.NameTaken to "Ya hay un eje",
            AxisSurveyProblem.EmptyName to "nombre",
            AxisSurveyProblem.InvalidNumber(null) to "PK inicial",
        )

        for ((issue, expected) in cases) {
            problem = issue
            compose.waitForIdle()
            compose.onNodeWithTag(AXIS_SURVEY_PROBLEM_TAG).assertTextContains(expected, substring = true)
        }
    }

    @Test
    fun `S052 RF-6 el motivo tambien se ve dentro del formulario`() {
        show(state(count = 3, finish = AxisFinishDraft(name = "A"), problem = AxisSurveyProblem.RadiusDoesNotFit(2)))

        compose.onNodeWithTag(AXIS_SURVEY_PROBLEM_TAG).performScrollTo().assertTextContains("vértice 2", substring = true)
    }

    @Test
    fun `S052 RF-8 el eje creado dice el nombre la longitud y si quedo activo y con rasante`() {
        show(state(result = AxisSurveyResult("Camino", 1234.5678, activated = true, hasGrade = true)))

        compose.onNodeWithText("«Camino», longitud 1.234,568 m.").assertIsDisplayed()
        compose.onNodeWithText("Queda como eje activo.").assertIsDisplayed()
        compose.onNodeWithText("Incluye la rasante de los puntos.").assertIsDisplayed()
        compose.onNodeWithTag(AXIS_SURVEY_RESULT_OK_TAG).assertHeightIsAtLeast(56.dp).performClick()
        calls shouldBe listOf("dismissResult")
    }

    @Test
    fun `S052 RF-8 si no quedo activo ni lleva rasante el resultado no lo dice`() {
        show(state(result = AxisSurveyResult("Camino", 10.0, activated = false, hasGrade = false)))

        compose.onNodeWithText("«Camino», longitud 10,000 m.").assertIsDisplayed()
        compose.onAllNodesWithText("Queda como eje activo.").assertCountEquals(0)
        compose.onAllNodesWithText("Incluye la rasante de los puntos.").assertCountEquals(0)
    }
}
