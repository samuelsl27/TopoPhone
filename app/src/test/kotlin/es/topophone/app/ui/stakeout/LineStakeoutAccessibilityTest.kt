package es.topophone.app.ui.stakeout

import androidx.compose.runtime.Composable
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.app.ui.lines.LINE_SHEET_AXIS_TAG
import es.topophone.app.ui.lines.LINE_SHEET_INFO_TAG
import es.topophone.app.ui.lines.LINE_SHEET_OPEN_TAG
import es.topophone.app.ui.lines.LINE_SHEET_STAKEOUT_TAG
import es.topophone.app.ui.lines.LineMapSheet
import es.topophone.core.designsystem.theme.TopoPhoneTheme
import es.topophone.core.model.cad.CadEntityKind
import es.topophone.core.model.codes.CodeSymbol
import es.topophone.core.model.codes.PointCode
import es.topophone.core.model.point.LineStakeoutMode
import es.topophone.core.model.preferences.EffectiveTheme
import es.topophone.core.model.project.AngularUnit
import es.topophone.core.model.project.CorrectionsDatum
import es.topophone.core.model.project.LinearUnit
import es.topophone.core.model.project.Project
import es.topophone.core.model.project.ProjectCrs
import es.topophone.core.survey.LineVertices
import es.topophone.core.survey.ProjectStakeLine
import es.topophone.core.survey.ProjectStakeLineResult
import es.topophone.core.survey.ProjectStakeVertex
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * El replanteo de una línea del proyecto cumple RNF-T7 y RNF-T8: todo control tiene texto o etiqueta, ninguno mide menos de 56 dp, los
 * avisos de cota y el cierre se dicen con texto y en inglés salen en inglés (S027).
 */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "es-rES")
class LineStakeoutAccessibilityTest {

    @get:Rule
    val compose = createComposeRule()

    private val project = Project(
        id = "p1",
        name = "Calle Mayor 12",
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

    private val built = ProjectStakeLine.of(
        listOf(
            ProjectStakeVertex("A", 0.0, 0.0, 650.0),
            ProjectStakeVertex("B", 30.0, 40.0, null),
            ProjectStakeVertex("C", 60.0, 80.0, 664.0),
        ),
        closed = false,
    ).shouldBeInstanceOf<ProjectStakeLineResult.Built>()

    private val bord = PointCode("BORD", "Bordillo", 0x1E88E5, CodeSymbol.CIRCLE)

    private val state = LineStakeoutUiState(
        project = project,
        element = LineElementInfo(
            layerId = "",
            layerName = "",
            drawingName = "",
            kind = CadEntityKind.POLYLINE,
            lengthM = 100.0,
            closed = false,
            minHeightM = 650.0,
            maxHeightM = 664.0,
            name = "BORD_1",
            projectLine = ProjectLineInfo("l1", "BORD_1", bord),
            missingHeightNames = listOf("B"),
        ),
        mode = LineStakeoutMode.BY_SEGMENTS,
        vertices = LineVertices.of(built.line, CadEntityKind.POLYLINE),
        vertexLabels = VertexLabels(built::name, built::isInterpolated),
        objectiveWithoutHeight = true,
    )

    private fun show(content: @Composable () -> Unit) {
        compose.setContent { TopoPhoneTheme(EffectiveTheme.LIGHT) { content() } }
    }

    private fun label(node: SemanticsNode): String {
        val text = node.config.getOrNull(SemanticsProperties.Text)?.joinToString(" ") { it.text }.orEmpty()
        val description = node.config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString(" ").orEmpty()
        return (text + description).trim()
    }

    private fun assertControls(screen: String) {
        val density = compose.density.density
        val nodes = compose.onAllNodes(hasClickAction()).fetchSemanticsNodes()
        (nodes.size > 0) shouldBe true
        nodes.filter { label(it).isEmpty() }.map { "$screen: sin etiqueta ${it.id}" }.shouldBeEmpty()
        nodes.filter {
            it.size.height / density < 55.5f
        }.map { "$screen: ${label(it)} mide ${it.size.height / density} dp" }.shouldBeEmpty()
    }

    @Test
    fun `S027 RNF-T8 el panel de una linea del proyecto tiene etiquetas y 56 dp`() {
        show { LineStakeoutPanel(state, LineStakeoutActions()) }

        assertControls("panel")
    }

    @Test
    fun `S027 RNF-T8 los avisos de cota y la forma de la linea se dicen con texto`() {
        show { LineStakeoutPanel(state, LineStakeoutActions()) }

        compose.onNodeWithTag(LINE_MISSING_HEIGHTS_TAG).assertExists()
        compose.onNodeWithText("Sin altura en los vértices: B", substring = true).assertExists()
        compose.onNodeWithText("Sin cota de diseño en este objetivo", substring = true).assertExists()
        compose.onNodeWithText("BORD_1 · Abierta").assertExists()
        compose.onNodeWithText("Código: BORD").assertExists()
    }

    @Test
    fun `S027 RNF-T8 el cierre por cambio de la linea se dice con texto`() {
        show { LineStakeoutPanel(state.copy(closed = LineClosedReason.LINE_CHANGED), LineStakeoutActions()) }

        compose.onNodeWithTag(LINE_CLOSED_TAG).assertExists()
        compose.onNodeWithText("ha cambiado", substring = true).assertExists()
    }

    @Test
    fun `S027 RNF-T8 la hoja del mapa de una linea tiene etiquetas y 56 dp`() {
        show { LineMapSheet("BORD_1", {}, {}, {}, {}, {}) }

        // El asa de arrastre de la hoja es del sistema; los botones son los nuestros.
        for (tag in listOf(LINE_SHEET_STAKEOUT_TAG, LINE_SHEET_OPEN_TAG, LINE_SHEET_INFO_TAG, LINE_SHEET_AXIS_TAG)) {
            compose.onNodeWithTag(tag).assertHeightIsAtLeast(56.dp).assertHasClickAction()
        }
    }

    @Test
    @Config(qualifiers = "en")
    fun `S027 RNF-T7 en ingles el panel y la hoja salen en ingles`() {
        show { LineStakeoutPanel(state, LineStakeoutActions()) }

        compose.onNodeWithText("BORD_1 · Open").assertExists()
        compose.onNodeWithText("No height on the vertices: B", substring = true).assertExists()
        compose.onNodeWithText("Code: BORD").assertExists()
    }

    @Test
    @Config(qualifiers = "en")
    fun `S027 RNF-T7 en ingles la hoja del mapa sale en ingles`() {
        show { LineMapSheet("BORD_1", {}, {}, {}, {}, {}) }

        compose.onNodeWithText("Open details").assertExists()
        compose.onNodeWithText("Create alignment").assertExists()
    }
}
