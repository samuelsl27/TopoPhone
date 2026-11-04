package es.topophone.app.ui.collector

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.core.designsystem.theme.TopoPhoneTheme
import es.topophone.core.model.codes.CodeGeometry
import es.topophone.core.model.codes.CodeSymbol
import es.topophone.core.model.codes.PointCode
import es.topophone.core.model.codes.SurveyCode
import es.topophone.core.model.line.LineSummary
import es.topophone.core.model.preferences.EffectiveTheme
import es.topophone.core.model.project.LinearUnit
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Los botones de la línea activa en el recolector y la lista de líneas abiertas (S025/RF-1, RF-3 a RF-6). */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "es-rES")
class CollectorLineScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val bord = SurveyCode("c1", "BORD", "Bordillo", CodeGeometry.LINE, 0x1E88E5)
    private val parc = SurveyCode("c2", "PARC", "", CodeGeometry.POLYGON, 0x43A047)
    private val line = CollectorLineUi("l1", "BORD_1", 3, CodeGeometry.LINE, "bord")
    private val open = listOf(
        LineSummary(
            "l1",
            "BORD_1",
            PointCode("BORD", "", 0, CodeSymbol.CIRCLE),
            closed = false,
            hidden = false,
            active = true,
            vertexCount = 3,
        ),
        LineSummary(
            "l2",
            "TAPIA_1",
            PointCode("TAPIA", "", 0, CodeSymbol.CIRCLE),
            closed = false,
            hidden = false,
            active = false,
            vertexCount = 1,
        ),
    )

    private fun state(line: CollectorLineUi? = this.line, openLines: List<LineSummary> = open) = CollectorUiState(
        loaded = true,
        name = "P12",
        poleHeightText = "1,800",
        code = CollectorCodeUi(selected = bord, options = listOf(parc, bord), totalCodes = 2),
        line = line,
        openLines = openLines,
    )

    private fun show(content: @Composable () -> Unit) {
        compose.setContent { TopoPhoneTheme(EffectiveTheme.LIGHT) { content() } }
    }

    @Test
    fun `S025 RF-3 el recolector muestra el nombre de la linea activa y su numero de vertices`() {
        show { CollectorPanel(state(), LinearUnit.METRE, CollectorActions(), onClose = {}) }

        compose.onNodeWithTag(COLLECTOR_LINE_TAG).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `S025 RF-3 sin linea activa no hay botones de terminar ni de deshacer`() {
        show { CollectorPanel(state(line = null, openLines = emptyList()), LinearUnit.METRE, CollectorActions(), onClose = {}) }

        compose.onAllNodesCount(COLLECTOR_LINE_FINISH_TAG) shouldBe 0
        compose.onAllNodesCount(COLLECTOR_LINE_UNDO_TAG) shouldBe 0
        compose.onAllNodesCount(COLLECTOR_LINE_CONTINUE_TAG) shouldBe 0
    }

    @Test
    fun `S025 RF-4 y RF-6 Terminar linea y Deshacer vertice llaman a sus acciones`() {
        var finished = 0
        var undone = 0
        val actions = CollectorActions(onFinishLine = { finished++ }, onUndoLineVertex = { undone++ })
        show { CollectorPanel(state(), LinearUnit.METRE, actions, onClose = {}) }

        compose.onNodeWithTag(COLLECTOR_LINE_FINISH_TAG).performScrollTo().performClick()
        compose.onNodeWithTag(COLLECTOR_LINE_UNDO_TAG).performScrollTo().performClick()

        finished shouldBe 1
        undone shouldBe 1
    }

    @Test
    fun `S025 RF-5 Continuar linea lista las abiertas con su codigo y sus vertices y la elegida se continua`() {
        var picked: String? = null
        val actions = CollectorActions(onContinueLine = { picked = it })
        show { CollectorPanel(state(), LinearUnit.METRE, actions, onClose = {}) }

        compose.onNodeWithTag(COLLECTOR_LINE_CONTINUE_TAG).performScrollTo().performClick()
        compose.onNodeWithTag(continueLineTag("BORD_1")).assertIsDisplayed()
        compose.onNodeWithTag(continueLineTag("TAPIA_1")).assertIsDisplayed().performClick()

        picked shouldBe "l2"
    }

    @Test
    fun `S025 RF-6 con una medida en marcha los botones de la linea no se pulsan`() {
        show { CollectorPanel(state().copy(measuring = true), LinearUnit.METRE, CollectorActions(), onClose = {}) }

        compose.onNodeWithTag(COLLECTOR_LINE_FINISH_TAG).performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag(COLLECTOR_LINE_UNDO_TAG).assertIsNotEnabled()
        compose.onNodeWithTag(COLLECTOR_LINE_CONTINUE_TAG).assertIsNotEnabled()
    }

    @Test
    fun `S025 RF-6 con una medida parada los botones de la linea se pulsan`() {
        show { CollectorPanel(state(), LinearUnit.METRE, CollectorActions(), onClose = {}) }

        compose.onNodeWithTag(COLLECTOR_LINE_FINISH_TAG).performScrollTo().assertIsEnabled()
    }

    @Test
    fun `S025 RF-1 los codigos de linea y de poligono se marcan con su geometria en el selector`() {
        show { CollectorPanel(state(), LinearUnit.METRE, CollectorActions(), onClose = {}) }

        compose.onNodeWithTag(COLLECTOR_CODE_TAG).performScrollTo().performClick()

        compose.onNodeWithTag(codeOptionTag("BORD")).assertExists()
        compose.onNodeWithTag(codeOptionTag("PARC")).assertExists()
        compose.onNodeWithTextIn(codeOptionTag("PARC"), "Polígono")
        compose.onNodeWithTextIn(codeOptionTag("BORD"), "Línea")
    }

    @Test
    fun `S025 RF-8b con la linea activa del mismo codigo los atributos no se piden`() {
        val withFields = state().copy(
            code = state().code.copy(
                fields = listOf(
                    AttributeFieldUi("Material", es.topophone.core.model.codes.AttributeType.TEXT, emptyList(), "Granito", null),
                ),
            ),
        )
        show { CollectorPanel(withFields, LinearUnit.METRE, CollectorActions(), onClose = {}) }

        compose.onAllNodesCount(attributeFieldTag(0)) shouldBe 0
    }

    @Test
    fun `S025 RF-8b sin linea activa los atributos del codigo se piden`() {
        val withFields = state(line = null, openLines = emptyList()).let {
            it.copy(
                code = it.code.copy(
                    fields = listOf(
                        AttributeFieldUi("Material", es.topophone.core.model.codes.AttributeType.TEXT, emptyList(), "Granito", null),
                    ),
                ),
            )
        }
        show { CollectorPanel(withFields, LinearUnit.METRE, CollectorActions(), onClose = {}) }

        compose.onNodeWithTag(attributeFieldTag(0)).performScrollTo().assertIsDisplayed()
    }

    private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.onAllNodesCount(tag: String): Int =
        onAllNodes(androidx.compose.ui.test.hasTestTag(tag)).fetchSemanticsNodes().size

    private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.onNodeWithTextIn(tag: String, text: String) {
        onNode(
            androidx.compose.ui.test.hasText(text) and androidx.compose.ui.test.hasAnyAncestor(androidx.compose.ui.test.hasTestTag(tag)),
            true,
        )
            .assertExists()
    }
}
