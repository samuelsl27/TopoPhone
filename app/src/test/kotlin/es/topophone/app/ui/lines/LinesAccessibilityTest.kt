package es.topophone.app.ui.lines

import androidx.compose.runtime.Composable
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.app.ui.collector.COLLECTOR_LINE_CONTINUE_TAG
import es.topophone.app.ui.collector.CollectorActions
import es.topophone.app.ui.collector.CollectorCodeUi
import es.topophone.app.ui.collector.CollectorLineUi
import es.topophone.app.ui.collector.CollectorSheet
import es.topophone.app.ui.collector.CollectorUiState
import es.topophone.app.ui.field.setContentExpanded
import es.topophone.app.ui.points.ObjectListActions
import es.topophone.app.ui.points.ObjectListScreen
import es.topophone.app.ui.points.ObjectListUiState
import es.topophone.app.ui.points.PointFixtures.measured
import es.topophone.app.ui.points.PointTrashActions
import es.topophone.app.ui.points.PointTrashScreen
import es.topophone.app.ui.points.PointTrashUiState
import es.topophone.core.designsystem.theme.TopoPhoneTheme
import es.topophone.core.model.codes.CodeGeometry
import es.topophone.core.model.codes.CodeSymbol
import es.topophone.core.model.codes.PointCode
import es.topophone.core.model.codes.SurveyCode
import es.topophone.core.model.line.LineChange
import es.topophone.core.model.line.LineHistoryItem
import es.topophone.core.model.line.LineHistoryLine
import es.topophone.core.model.line.LineSummary
import es.topophone.core.model.line.LineVertex
import es.topophone.core.model.line.ProjectLine
import es.topophone.core.model.point.PointOrigin
import es.topophone.core.model.point.PointSummary
import es.topophone.core.model.preferences.EffectiveTheme
import es.topophone.core.model.preferences.ThemeState
import es.topophone.core.model.project.LinearUnit
import es.topophone.data.line.TrashedLine
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Las pantallas de las líneas cumplen RNF-T7 y RNF-T8: todo control tiene un texto o una etiqueta para TalkBack, ninguno mide menos de
 * 56 dp de alto, abierta, polígono y activa se dicen con texto, y en inglés los textos salen en inglés (S025/RNF-T7, RNF-T8).
 */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "es-rES")
class LinesAccessibilityTest {

    @get:Rule
    val compose = createComposeRule()

    private val bord = PointCode("BORD", "Bordillo", 0x1E88E5, CodeSymbol.CIRCLE)
    private val line = ProjectLine(
        id = "l1",
        projectId = "p1",
        name = "BORD_1",
        code = bord,
        geometry = CodeGeometry.LINE,
        closed = false,
        hidden = false,
        active = true,
        vertices = listOf(LineVertex("a"), LineVertex("b"), LineVertex("c")),
        createdAtMs = 0,
    )
    private val summaries = listOf(
        LineSummary("l1", "BORD_1", bord, closed = false, hidden = false, active = true, vertexCount = 3),
        LineSummary("l2", "PARC_1", null, closed = true, hidden = true, active = false, vertexCount = 1),
    )

    private fun show(content: @Composable () -> Unit) {
        compose.setContentExpanded { TopoPhoneTheme(EffectiveTheme.LIGHT) { content() } }
    }

    private fun label(node: SemanticsNode): String {
        val text = node.config.getOrNull(SemanticsProperties.Text)?.joinToString(" ") { it.text }.orEmpty()
        val description = node.config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString(" ").orEmpty()
        val edit = node.config.getOrNull(SemanticsProperties.EditableText)?.text.orEmpty()
        val merged = (text + description + edit).trim()
        return merged
    }

    /** Todo lo que se puede pulsar tiene etiqueta (de sí mismo o de sus hijos) y mide al menos 56 dp de alto. */
    private fun assertControls(screen: String) {
        val density = compose.density.density
        val nodes = compose.onAllNodes(hasClickAction()).fetchSemanticsNodes()
        (nodes.size > 0) shouldBe true
        nodes.filter { label(it).isEmpty() }.map { "$screen: sin etiqueta ${it.id} (${it.config})" }.shouldBeEmpty()
        nodes.filter { it.size.height / density < 55.5f }
            .map { "$screen: ${label(it)} mide ${it.size.height / density} dp" }
            .shouldBeEmpty()
    }

    private val collectorState = CollectorUiState(
        loaded = true,
        name = "P12",
        poleHeightText = "1,800",
        code = CollectorCodeUi(selected = SurveyCode("c1", "BORD", "Bordillo", CodeGeometry.LINE, 0x1E88E5), totalCodes = 1),
        line = CollectorLineUi("l1", "BORD_1", 3, CodeGeometry.LINE, "bord"),
        openLines = summaries.take(1),
    )

    @Test
    fun `S025 RNF-T8 los botones de la linea activa en el recolector tienen etiqueta y 56 dp`() {
        show { CollectorSheet(collectorState, LinearUnit.METRE, CollectorActions(), onClose = {}) }
        compose.onNodeWithTag(COLLECTOR_LINE_CONTINUE_TAG).performScrollTo()

        assertControls("recolector")
    }

    @Test
    fun `S025 RNF-T8 la lista de lineas abiertas para continuar tiene etiquetas y 56 dp`() {
        show { CollectorSheet(collectorState, LinearUnit.METRE, CollectorActions(), onClose = {}) }
        compose.onNodeWithTag(COLLECTOR_LINE_CONTINUE_TAG).performScrollTo().performClick()

        assertControls("continuar")
    }

    @Test
    fun `S025 RNF-T8 el asistente Linea desde puntos tiene etiquetas y 56 dp`() {
        val points = listOf("1", "2").map {
            PointSummary(
                id = "id-$it",
                name = it,
                description = "",
                origin = PointOrigin.MANUAL,
                latitudeDeg = 40.0,
                longitudeDeg = -3.0,
                hasHeight = false,
                outOfTolerance = false,
                edited = false,
                hidden = false,
                createdAtMs = 0,
            )
        }
        val state = LineFromPointsUi(
            loaded = true,
            points = points,
            selected = listOf(WizardPoint("id-1", "1")),
            name = "BORD_1",
            issue = LineWizardIssue.TOO_FEW_VERTICES,
        )
        show { LineFromPointsScreen(ThemeState.DEFAULT, {}, state, LineFromPointsActions(), onBack = {}) }

        assertControls("asistente")
    }

    private val details = LineDetailsUi(
        loaded = true,
        line = line,
        vertices = listOf(VertexRow(0, "a", "1", 0.0), VertexRow(1, "b", "2", 12.5), VertexRow(2, "c", "3", 20.0)),
    )

    @Test
    fun `S025 RNF-T8 la ficha de la linea tiene etiquetas y 56 dp, y dice abierta y activa con texto`() {
        show { LineDetailsScreen(ThemeState.DEFAULT, {}, details, LineDetailsActions(), onBack = {}) }

        compose.onNodeWithText("Abierta · Activa").assertExists()
        assertControls("ficha")
    }

    @Test
    fun `S025 RNF-T8 el menu de un vertice tiene etiquetas y 56 dp`() {
        show { LineDetailsScreen(ThemeState.DEFAULT, {}, details, LineDetailsActions(), onBack = {}) }
        compose.onNode(hasScrollAction()).performScrollToNode(hasTestTag(lineVertexMenuTag(1)))
        compose.onNodeWithTag(lineVertexMenuTag(1)).performClick()

        assertControls("menu del vertice")
    }

    @Test
    fun `S025 RNF-T8 los dialogos de la ficha tienen etiquetas y 56 dp`() {
        val dialogs = details.copy(
            pick = VertexPick.Append,
            candidates = listOf(WizardPoint("a", "1"), WizardPoint("b", "2")),
            renaming = "BORD_1",
            attributes = LineAttributesEdit(emptyList()),
        )
        show { LineDetailsScreen(ThemeState.DEFAULT, {}, dialogs, LineDetailsActions(), onBack = {}) }

        assertControls("dialogos")
    }

    @Test
    fun `S025 RNF-T8 el historial de la linea se lee con texto`() {
        val item = LineHistoryItem(
            1_792_346_639_419,
            LineChange.VERTICES,
            listOf(LineHistoryLine.Vertices(3, 2, removed = listOf("3"), added = emptyList())),
        )
        show { LineDetailsScreen(ThemeState.DEFAULT, {}, details.copy(history = listOf(item)), LineDetailsActions(), onBack = {}) }
        compose.onNodeWithTag(LINE_HISTORY_BUTTON_TAG).performScrollTo().performClick()

        compose.onNodeWithText("Vértices: 3 → 2. Quitados: 3").assertIsDisplayed()
        assertControls("historial")
    }

    @Test
    fun `S025 RNF-T8 las lineas en la lista de objetos y en la papelera tienen etiquetas y 56 dp`() {
        show {
            ObjectListScreen(
                ThemeState.DEFAULT,
                {},
                ObjectListUiState(loaded = true, points = listOf(measured("2").summary()), total = 1, lines = summaries),
                ObjectListActions(),
                onBack = {},
            )
        }
        assertControls("lista")
    }

    @Test
    fun `S025 RNF-T8 la papelera con lineas tiene etiquetas y 56 dp`() {
        show {
            PointTrashScreen(
                ThemeState.DEFAULT,
                {},
                PointTrashUiState(loaded = true, lines = listOf(TrashedLine(line.copy(deletedAtMs = 1), 20))),
                PointTrashActions(),
                onBack = {},
            )
        }

        assertControls("papelera")
    }

    @Test
    @Config(qualifiers = "en")
    fun `S025 RNF-T7 en ingles la ficha y el asistente salen en ingles`() {
        show { LineDetailsScreen(ThemeState.DEFAULT, {}, details, LineDetailsActions(), onBack = {}) }

        compose.onNodeWithText("Open · Active").assertExists()
        compose.onNodeWithText("Close as polygon").assertExists()
    }

    @Test
    @Config(qualifiers = "en")
    fun `S025 RNF-T7 en ingles el recolector dice la linea activa en ingles`() {
        show { CollectorSheet(collectorState, LinearUnit.METRE, CollectorActions(), onClose = {}) }

        compose.onNodeWithText("Active line: BORD_1").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("3 vertices").assertExists()
        compose.onNodeWithText("Finish line").assertExists()
    }
}
