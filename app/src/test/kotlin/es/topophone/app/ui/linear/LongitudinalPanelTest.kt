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
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
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
 * El panel del perfil longitudinal con estados ya hechos: guiado o libre, los campos de partida y la tolerancia de eje, los puntos elegidos,
 * el alzado, los puntos fuera del perfil, quitar un punto, exportar el CSV y los motivos de rechazo (S052/RF-10 a RF-15, RF-21; RNF-T7, RNF-T8).
 */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "es-rES")
class LongitudinalPanelTest {

    @get:Rule
    val compose = createComposeRule()

    private val axis = Alignment(
        "a1", "p1", "Eje A", null, 0L, AlignmentSource.MANUAL, listOf(AlignmentElement.line(0.0, 420.0, 440_000.0, 4_474_000.0, 1.0)),
        null, true, AlignmentExtent(0.0, 1.0, 0.0, 1.0),
    )

    private fun row(i: Int, flag: SurveyFlag = SurveyFlag.NONE, chainage: Double? = i * 100.0) = LongitudinalRow(
        pointId = "id$i",
        name = "L$i",
        chainageM = chainage,
        offsetM = 0.1,
        groundM = 100.0 + i,
        referenceM = 100.0,
        dHeightM = i.toDouble(),
        flag = flag,
        included = true,
    )

    private fun chartOf(rows: List<LongitudinalRow>) = ProfileChart(
        ProfileAxis.CHAINAGE,
        rows.filter { it.chainageM != null }.map {
            ProfileChartPoint(it.name, it.chainageM!!, it.groundM!!, it.referenceM, it.dHeightM, it.inProfile, it.flag)
        },
        reference = listOf(0.0 to 100.0, 400.0 to 100.0),
    )

    private fun state(
        setup: LongitudinalSetup = LongitudinalSetup(guided = true),
        survey: LinearSurvey? = null,
        rows: List<LongitudinalRow> = emptyList(),
        selection: Int = 0,
        problem: LongitudinalProblem? = null,
        hasGrade: Boolean = true,
    ) = LongitudinalUiState(
        loading = false,
        unit = LinearUnit.METRE,
        alignment = axis,
        hasGrade = hasGrade,
        survey = survey,
        rows = rows,
        chart = chartOf(rows),
        setup = setup,
        problem = problem,
        selectionCount = selection,
    )

    private fun survey(guided: Boolean) =
        LinearSurvey("t1", "p1", LinearSurveyKind.LONGITUDINAL, "a1", "Eje A", guided = guided, createdAtMs = 1)

    private val calls = mutableListOf<String>()

    private fun actions(setup: LongitudinalSetup = LongitudinalSetup()) = LongitudinalActions(
        onClose = { calls += "close" },
        onSetup = { change -> calls += "setup:" + change(setup) },
        onBegin = { calls += "begin" },
        onUseSelection = { calls += "selection" },
        onOpenGuide = { calls += "guide" },
        onRemove = { calls += "remove:$it" },
        onFinish = { calls += "finish" },
        onDiscard = { calls += "discard" },
    )

    private fun show(state: LongitudinalUiState) = compose.setContent {
        TopoPhoneTheme(EffectiveTheme.LIGHT) {
            LongitudinalPanel(state, "Obra", actions(state.setup), { true })
        }
    }

    @Test
    fun `S052 RF-10 antes de empezar se elige guiado o libre y el guiado pide los PK y el intervalo`() {
        show(state(LongitudinalSetup(guided = true)))

        compose.onNodeWithTag(LONGITUDINAL_GUIDED_TAG).assertHeightIsAtLeast(56.dp).performClick()
        compose.onNodeWithTag(LONGITUDINAL_FREE_TAG).assertHeightIsAtLeast(56.dp).performClick()
        compose.onNodeWithTag(LONGITUDINAL_FROM_TAG).assertIsDisplayed().assertHeightIsAtLeast(56.dp)
        compose.onNodeWithTag(LONGITUDINAL_TO_TAG).assertIsDisplayed()
        compose.onNodeWithTag(LONGITUDINAL_INTERVAL_TAG).assertIsDisplayed()
        compose.onNodeWithTag(LONGITUDINAL_TOLERANCE_TAG).assertIsDisplayed()

        calls.map { it.substringAfter("guided=").substringBefore(",") } shouldBe listOf("true", "false")
    }

    @Test
    fun `S052 RF-10 el modo libre no pide PK ni intervalo pero si la tolerancia de eje`() {
        show(state(LongitudinalSetup(guided = false)))

        compose.onAllNodesWithTag(LONGITUDINAL_FROM_TAG).assertCountEquals(0)
        compose.onAllNodesWithTag(LONGITUDINAL_TO_TAG).assertCountEquals(0)
        compose.onAllNodesWithTag(LONGITUDINAL_INTERVAL_TAG).assertCountEquals(0)
        compose.onNodeWithTag(LONGITUDINAL_TOLERANCE_TAG).assertIsDisplayed()
    }

    @Test
    fun `S052 RF-10 escribir los campos y empezar avisan`() {
        show(state(LongitudinalSetup(guided = true)))

        compose.onNodeWithTag(LONGITUDINAL_FROM_TAG).performTextReplacement("0")
        compose.onNodeWithTag(LONGITUDINAL_TOLERANCE_TAG).performScrollTo().performTextReplacement("0,4")
        compose.onNodeWithTag(LONGITUDINAL_BEGIN_TAG).performScrollTo().assertHeightIsAtLeast(56.dp).performClick()

        calls.any { it.contains("from=0") } shouldBe true
        calls.any { it.contains("tolerance=0,4") } shouldBe true
        calls.last() shouldBe "begin"
    }

    @Test
    fun `S052 RF-14 con puntos elegidos en la lista se ofrece usarlos y sin ellos se explica como`() {
        show(state(selection = 3))
        compose.onNodeWithTag(LONGITUDINAL_SELECTION_TAG).performScrollTo().assertTextContains("3 puntos", substring = true).performClick()
        calls shouldBe listOf("selection")
    }

    @Test
    fun `S052 RF-14 sin puntos elegidos no hay boton de seleccion`() {
        show(state(selection = 0))

        compose.onAllNodesWithTag(LONGITUDINAL_SELECTION_TAG).assertCountEquals(0)
    }

    @Test
    fun `S052 RF-12 con el perfil abierto el resumen cuenta los puntos que entran y los que no y se listan con su marca`() {
        val rows = listOf(row(1), row(2), row(3, SurveyFlag.OUT_OF_AXIS), row(4, SurveyFlag.NO_PROJECTION, chainage = null))
        show(state(survey = survey(guided = false), rows = rows))

        compose.onNodeWithTag(LONGITUDINAL_SUMMARY_TAG).assertTextContains("2 puntos en el perfil, 2 fuera")
        compose.onNodeWithTag(LONGITUDINAL_REJECTED_TAG).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("L3: Fuera del eje").assertIsDisplayed()
        compose.onNodeWithText("L4: Sin proyección sobre el eje").assertIsDisplayed()
    }

    @Test
    fun `S052 RF-13 con el perfil abierto se ve el alzado y sin rasante se avisa de que solo hay terreno`() {
        show(state(survey = survey(guided = false), rows = listOf(row(1), row(2)), hasGrade = false))

        compose.onNodeWithTag(PROFILE_VIEW_TAG).assertIsDisplayed()
        compose.onNodeWithText("Este eje no tiene rasante: solo se dibuja el terreno.").assertIsDisplayed()
    }

    @Test
    fun `S052 RF-10 el boton de la guia solo sale en un perfil guiado`() {
        show(state(survey = survey(guided = true), rows = listOf(row(1))))
        compose.onNodeWithTag(LONGITUDINAL_GUIDE_TAG).performScrollTo().assertHeightIsAtLeast(56.dp).performClick()
        calls shouldBe listOf("guide")
    }

    @Test
    fun `S052 RF-10 un perfil libre no tiene boton de guia`() {
        show(state(survey = survey(guided = false), rows = listOf(row(1))))

        compose.onAllNodesWithTag(LONGITUDINAL_GUIDE_TAG).assertCountEquals(0)
    }

    @Test
    fun `S052 RF-21 cada punto del perfil se puede quitar con un boton de 56 dp`() {
        show(state(survey = survey(guided = false), rows = listOf(row(1), row(2))))

        compose.onNodeWithTag(LONGITUDINAL_LIST_TAG).performScrollTo()
        compose.onNodeWithTag(longitudinalRemoveTag("id2")).assertHeightIsAtLeast(56.dp).performClick()

        calls shouldBe listOf("remove:id2")
    }

    @Test
    fun `S052 RF-15 exportar solo se puede si hay puntos en el perfil y el nombre sale del proyecto`() {
        show(state(survey = survey(guided = false), rows = listOf(row(1))))
        compose.onNodeWithTag(PROFILE_EXPORT_SAVE_TAG).performScrollTo().assertIsEnabled().assertHeightIsAtLeast(56.dp)
        compose.onNodeWithTag(PROFILE_EXPORT_SHARE_TAG).assertIsEnabled().assertHeightIsAtLeast(56.dp)
        compose.onNodeWithTag(PROFILE_EXPORT_FILE_TAG).assertTextContains("Obra_perfil-longitudinal_", substring = true)
        compose.onNodeWithTag(PROFILE_EXPORT_FILE_TAG).assertTextContains(".csv", substring = true)
    }

    @Test
    fun `S052 RF-15 sin puntos en el perfil los botones de exportar estan apagados`() {
        show(state(survey = survey(guided = false), rows = listOf(row(1, SurveyFlag.OUT_OF_AXIS))))

        compose.onNodeWithTag(PROFILE_EXPORT_SAVE_TAG).performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag(PROFILE_EXPORT_SHARE_TAG).assertIsNotEnabled()
    }

    @Test
    fun `S052 RF-22 terminar y descartar avisan y cerrar tambien`() {
        show(state(survey = survey(guided = false), rows = listOf(row(1))))

        compose.onNodeWithTag(LONGITUDINAL_FINISH_TAG).performScrollTo().assertHeightIsAtLeast(56.dp).performClick()
        compose.onNodeWithTag(LONGITUDINAL_DISCARD_TAG).assertHeightIsAtLeast(56.dp).performClick()
        compose.onNodeWithTag(LONGITUDINAL_CLOSE_TAG).assertHeightIsAtLeast(56.dp).performClick()

        calls shouldBe listOf("finish", "discard", "close")
    }

    @Test
    fun `S052 RF-10 cada motivo de rechazo nombra el campo`() {
        var problem by mutableStateOf<LongitudinalProblem?>(null)
        compose.setContent {
            TopoPhoneTheme(EffectiveTheme.LIGHT) { LongitudinalPanel(state(problem = problem), "Obra", actions(), { true }) }
        }
        val cases = listOf(
            LongitudinalProblem.InvalidNumber(LongitudinalField.INTERVAL) to "«Intervalo» no es un número",
            LongitudinalProblem.OutsideAxis(LongitudinalField.TO) to "«PK final» queda fuera del eje",
            LongitudinalProblem.OutsideAxis(LongitudinalField.FROM) to "«PK inicial» queda fuera del eje",
            LongitudinalProblem.NotPositive(LongitudinalField.TOLERANCE) to "«Tolerancia de eje» debe ser mayor que cero",
            LongitudinalProblem.NoPoints to "ningún punto elegido",
        )

        for ((issue, expected) in cases) {
            problem = issue
            compose.waitForIdle()
            compose.onNodeWithTag(LONGITUDINAL_PROBLEM_TAG).performScrollTo().assertTextContains(expected, substring = true)
        }
    }

    @Test
    fun `S052 RF-10 mientras carga el panel solo tiene el titulo y cerrar`() {
        compose.setContent {
            TopoPhoneTheme(EffectiveTheme.LIGHT) { LongitudinalPanel(LongitudinalUiState(), "Obra", actions(), { true }) }
        }

        compose.onNodeWithTag(LONGITUDINAL_CLOSE_TAG).assertIsDisplayed()
        compose.onAllNodesWithTag(LONGITUDINAL_BEGIN_TAG).assertCountEquals(0)
    }
}
