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
import es.topophone.core.model.alignment.Alignment
import es.topophone.core.model.alignment.AlignmentElement
import es.topophone.core.model.alignment.AlignmentExtent
import es.topophone.core.model.alignment.AlignmentSource
import es.topophone.core.model.alignment.section.SectionIssue
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
 * El panel del perfil transversal con estados ya hechos: el PK y la tolerancia de sección, el alzado con la sección de diseño, los puntos
 * fuera de sección, el código del siguiente punto, quitar, exportar, guardar con su aviso del historial y los rechazos
 * (S052/RF-16 a RF-22; RNF-T7, RNF-T8).
 */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "es-rES")
class CrossPanelTest {

    @get:Rule
    val compose = createComposeRule()

    private val axis = Alignment(
        "a1", "p1", "Eje C", null, 0L, AlignmentSource.MANUAL, listOf(AlignmentElement.line(0.0, 200.0, 441_000.0, 4_474_000.0, 1.0)),
        null, true, AlignmentExtent(0.0, 1.0, 0.0, 1.0),
    )

    private fun row(i: Int, flag: SurveyFlag = SurveyFlag.NONE, code: String? = null) = CrossRow(
        pointId = "id$i",
        name = "X$i",
        chainageM = 105.0,
        offsetM = i - 3.0,
        groundM = 100.0 + i,
        designM = 100.0,
        dHeightM = i.toDouble(),
        flag = flag,
        included = true,
        code = code,
    )

    private fun chartOf(rows: List<CrossRow>) = ProfileChart(
        ProfileAxis.OFFSET,
        rows.map { ProfileChartPoint(it.name, it.offsetM!!, it.groundM!!, it.designM, it.dHeightM, it.inProfile, it.flag) },
        reference = listOf(-10.0 to 99.0, 10.0 to 99.0),
    )

    private val survey = LinearSurvey("t1", "p1", LinearSurveyKind.CROSS, "a1", "Eje C", chainage = 105.0, guided = true, createdAtMs = 1)

    private fun state(
        open: Boolean = true,
        rows: List<CrossRow> = emptyList(),
        hasDesign: Boolean = true,
        setup: CrossSetup = CrossSetup(),
        problem: CrossProblem? = null,
        saved: CrossSaved? = null,
    ) = CrossUiState(
        loading = false,
        unit = LinearUnit.METRE,
        alignment = axis,
        survey = survey.takeIf { open },
        rows = rows,
        chart = chartOf(rows),
        hasDesign = hasDesign,
        setup = setup,
        problem = problem,
        saved = saved,
    )

    private val calls = mutableListOf<String>()

    private fun actions(setup: CrossSetup = CrossSetup()) = CrossActions(
        onClose = { calls += "close" },
        onSetup = { change -> calls += "setup:" + change(setup) },
        onBegin = { calls += "begin" },
        onOpenGuide = { calls += "guide" },
        onRemove = { calls += "remove:$it" },
        onSave = { calls += "save" },
        onDiscard = { calls += "discard" },
        onDismissSaved = { calls += "dismissSaved" },
    )

    private fun show(state: CrossUiState) = compose.setContent {
        TopoPhoneTheme(EffectiveTheme.LIGHT) { CrossPanel(state, "Obra", actions(state.setup), { true }) }
    }

    @Test
    fun `S052 RF-16 antes de empezar pide el PK de la seccion y la tolerancia y empezar mide 56 dp`() {
        show(state(open = false))

        compose.onNodeWithTag(CROSS_CHAINAGE_TAG).assertIsDisplayed().assertHeightIsAtLeast(56.dp)
        compose.onNodeWithTag(CROSS_TOLERANCE_TAG).assertIsDisplayed().assertHeightIsAtLeast(56.dp)
        compose.onNodeWithTag(CROSS_BEGIN_TAG).assertHeightIsAtLeast(56.dp).performClick()
        calls shouldBe listOf("begin")
    }

    @Test
    fun `S052 RF-16 escribir el PK y la tolerancia cambia el borrador`() {
        show(state(open = false))

        compose.onNodeWithTag(CROSS_CHAINAGE_TAG).performTextReplacement("1+05")
        compose.onNodeWithTag(CROSS_TOLERANCE_TAG).performTextReplacement("0,15")

        calls.any { it.contains("chainage=1+05") } shouldBe true
        calls.any { it.contains("tolerance=0,15") } shouldBe true
    }

    @Test
    fun `S052 RF-18 el resumen cuenta los puntos del perfil y los fuera de seccion y estos se listan con su marca`() {
        val rows = listOf(row(1), row(2), row(3, SurveyFlag.OUT_OF_SECTION))
        show(state(rows = rows))

        compose.onNodeWithTag(CROSS_SUMMARY_TAG).assertTextContains("2 puntos en el perfil, 1 fuera de sección")
        compose.onNodeWithTag(CROSS_REJECTED_TAG).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("X3: Fuera de sección").assertIsDisplayed()
    }

    @Test
    fun `S052 RF-19 se ve el alzado y sin seccion de diseno se avisa de que solo hay terreno`() {
        show(state(rows = listOf(row(1)), hasDesign = false))

        compose.onNodeWithTag(PROFILE_VIEW_TAG).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag(CROSS_NO_DESIGN_TAG).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `S052 RF-19 con seccion de diseno no se avisa de nada`() {
        show(state(rows = listOf(row(1)), hasDesign = true))

        compose.onAllNodesWithTag(CROSS_NO_DESIGN_TAG).assertCountEquals(0)
    }

    @Test
    fun `S052 RF-17 el codigo del siguiente punto se escribe en el panel y la guia se vuelve a abrir`() {
        show(state(rows = listOf(row(1))))

        compose.onNodeWithTag(CROSS_CODE_TAG).performScrollTo().assertHeightIsAtLeast(56.dp).performTextReplacement("CUN")
        compose.onNodeWithTag(CROSS_GUIDE_TAG).performScrollTo().assertHeightIsAtLeast(56.dp).performClick()

        calls.any { it.contains("nextCode=CUN") } shouldBe true
        calls.last() shouldBe "guide"
    }

    @Test
    fun `S052 RF-17 cada punto muestra su lado con palabra y su codigo y se quita con un boton de 56 dp`() {
        show(state(rows = listOf(row(1, code = "D3"), row(5))))

        compose.onNodeWithTag(CROSS_LIST_TAG).performScrollTo()
        compose.onNodeWithText("X1 (D3) · 2,000 m a la izquierda", substring = true).assertIsDisplayed()
        compose.onNodeWithTag(crossRemoveTag("id1")).assertHeightIsAtLeast(56.dp).performClick()

        calls shouldBe listOf("remove:id1")
    }

    @Test
    fun `S052 RF-22 exportar solo se puede con puntos en el perfil`() {
        show(state(rows = listOf(row(1))))
        compose.onNodeWithTag(PROFILE_EXPORT_SAVE_TAG).performScrollTo().assertIsEnabled()
        compose.onNodeWithTag(PROFILE_EXPORT_FILE_TAG).assertTextContains("Obra_perfil-transversal_", substring = true)
    }

    @Test
    fun `S052 RF-22 con todos los puntos fuera de seccion los botones de exportar estan apagados`() {
        show(state(rows = listOf(row(1, SurveyFlag.OUT_OF_SECTION))))

        compose.onNodeWithTag(PROFILE_EXPORT_SAVE_TAG).performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag(PROFILE_EXPORT_SHARE_TAG).assertIsNotEnabled()
    }

    @Test
    fun `S052 RF-20 guardar y descartar avisan con botones de 56 dp y cerrar tambien`() {
        show(state(rows = listOf(row(1))))

        compose.onNodeWithTag(CROSS_SAVE_TAG).performScrollTo().assertHeightIsAtLeast(56.dp).performClick()
        compose.onNodeWithTag(CROSS_DISCARD_TAG).assertHeightIsAtLeast(56.dp).performClick()
        compose.onNodeWithTag(CROSS_CLOSE_TAG).assertHeightIsAtLeast(56.dp).performClick()

        calls shouldBe listOf("save", "discard", "close")
    }

    @Test
    fun `S052 RF-20 el aviso de perfil guardado dice cuantos puntos y que el anterior queda en el historial`() {
        show(state(open = false, saved = CrossSaved(6, replacedPrevious = true)))

        compose.onNodeWithText("El perfil de terreno de este PK se ha guardado con 6 puntos.").assertIsDisplayed()
        compose.onNodeWithText("El perfil anterior de este PK queda en el historial.").assertIsDisplayed()
        compose.onNodeWithTag(CROSS_SAVED_OK_TAG).assertHeightIsAtLeast(56.dp).performClick()
        calls shouldBe listOf("dismissSaved")
    }

    @Test
    fun `S052 RF-20 la primera vez en un PK no se habla del historial`() {
        show(state(open = false, saved = CrossSaved(2, replacedPrevious = false)))

        compose.onNodeWithText("El perfil de terreno de este PK se ha guardado con 2 puntos.").assertIsDisplayed()
        compose.onAllNodesWithText("El perfil anterior de este PK queda en el historial.").assertCountEquals(0)
    }

    @Test
    fun `S052 RF-16 cada motivo de rechazo se dice con palabras`() {
        var problem by mutableStateOf<CrossProblem?>(null)
        compose.setContent {
            TopoPhoneTheme(EffectiveTheme.LIGHT) { CrossPanel(state(open = false, problem = problem), "Obra", actions(), { true }) }
        }
        val cases = listOf(
            CrossProblem.InvalidNumber(CrossField.CHAINAGE) to "«PK de la sección» no es un número",
            CrossProblem.InvalidNumber(CrossField.TOLERANCE) to "«Tolerancia de sección» no es un número",
            CrossProblem.OutsideAxis to "«PK de la sección» queda fuera del eje",
            CrossProblem.NotPositive to "«Tolerancia de sección» debe ser mayor que cero",
            CrossProblem.NoPoints to "No hay puntos en el perfil",
            CrossProblem.SameOffset(1.0) to "mismo desplazamiento",
            CrossProblem.MissingHeight(1.0) to "no tiene cota",
            CrossProblem.Rejected(SectionIssue.ChainageOutsideAxis(9.0)) to "No se ha podido guardar el perfil",
        )

        for ((issue, expected) in cases) {
            problem = issue
            compose.waitForIdle()
            compose.onNodeWithTag(CROSS_PROBLEM_TAG).performScrollTo().assertTextContains(expected, substring = true)
        }
    }
}
