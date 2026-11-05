package es.topophone.app.ui.geometry

import androidx.compose.runtime.Composable
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.app.ui.cad.DESIGN_INFORMATION_TAG
import es.topophone.app.ui.cad.DesignElementSheet
import es.topophone.app.ui.cad.DesignSelection
import es.topophone.core.designsystem.theme.TopoPhoneTheme
import es.topophone.core.model.cad.CadEntity
import es.topophone.core.model.cad.CadEntityKind
import es.topophone.core.model.cad.CadEntityRef
import es.topophone.core.model.geometry.DistanceMode
import es.topophone.core.model.preferences.EffectiveTheme
import es.topophone.core.model.preferences.ThemeState
import es.topophone.core.model.project.AngularUnit
import es.topophone.core.model.project.LinearUnit
import es.topophone.core.survey.geometry.AnglePoint
import es.topophone.core.survey.geometry.AngleResult
import es.topophone.core.survey.geometry.GeometryCalculator
import es.topophone.core.survey.geometry.GeometryResult
import es.topophone.core.survey.geometry.GeometryVertex
import es.topophone.core.survey.geometry.SegmentScale
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Las pantallas de la información geométrica cumplen RNF-T7 y RNF-T8: todo control tiene texto o etiqueta, ninguno mide menos de 56 dp,
 * la distancia activa se dice con texto, la tabla va en tipografía monoespaciada y en inglés los textos salen en inglés (S026).
 */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "es-rES")
class GeometryAccessibilityTest {

    @get:Rule
    val compose = createComposeRule()

    private val info = (
        GeometryCalculator.of(
            listOf(GeometryVertex(0.0, 0.0, 650.0), GeometryVertex(30.0, 0.0, 650.0), GeometryVertex(30.0, 40.0, 664.0)),
            true,
            SegmentScale.Grid,
        ) as GeometryResult.Info
        ).info

    private val state = GeometryInfoUi(
        loaded = true,
        projectName = "Parcela",
        lineName = "BORD_1",
        closed = true,
        mode = DistanceMode.GRID,
        linearUnit = LinearUnit.METRE,
        angularUnit = AngularUnit.DECIMAL_DEGREES,
        info = info,
        names = listOf("P1", "P2", "P3"),
    )

    private fun show(content: @Composable () -> Unit) {
        compose.setContent { TopoPhoneTheme(EffectiveTheme.LIGHT) { content() } }
    }

    private fun label(node: SemanticsNode): String {
        val text = node.config.getOrNull(SemanticsProperties.Text)?.joinToString(" ") { it.text }.orEmpty()
        val description = node.config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString(" ").orEmpty()
        return (text + description).trim()
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

    private val chosen = AnglePointOption("a", "P1")
    private val angleState = ThreePointAngleUi(
        loaded = true,
        a = chosen,
        b = AnglePointOption("b", "P2"),
        c = AnglePointOption("c", "P3"),
        candidates = listOf(chosen, AnglePointOption("b", "P2")),
        result = AngleResult.Angle(Math.PI / 2, 0.0, Math.PI / 2, 100.0, 120.0, emptySet()),
    )

    @Test
    fun `S026 RNF-T8 la informacion tiene etiquetas y 56 dp, y dice la distancia activa con texto`() {
        show { GeometryInfoScreen(ThemeState.DEFAULT, {}, state, GeometryInfoActions(), onBack = {}) }

        compose.onNodeWithText("Distancias de cuadrícula").assertExists()
        assertControls("informacion")
    }

    @Test
    fun `S026 RNF-T8 la tabla de segmentos va en tipografia monoespaciada`() {
        show { GeometryInfoScreen(ThemeState.DEFAULT, {}, state, GeometryInfoActions(), onBack = {}) }

        compose.onNodeWithTag(GEOMETRY_INFO_TAG).performScrollToNode(hasTestTag(geometrySegmentTag(0)))
        val results = mutableListOf<TextLayoutResult>()
        val cell = compose.onAllNodes(hasText("P1"), useUnmergedTree = true).fetchSemanticsNodes()
            .first { it.config.getOrNull(SemanticsActions.GetTextLayoutResult) != null }
        cell.config[SemanticsActions.GetTextLayoutResult].action?.invoke(results)

        results.first().layoutInput.style.fontFamily shouldBe FontFamily.Monospace
    }

    @Test
    fun `S026 RNF-T8 el angulo tiene etiquetas y 56 dp con el selector cerrado y abierto`() {
        show { ThreePointAngleScreen(ThemeState.DEFAULT, {}, angleState, ThreePointAngleActions(), onBack = {}) }
        assertControls("angulo")
    }

    @Test
    fun `S026 RNF-T8 el selector de puntos del angulo tiene etiquetas y 56 dp`() {
        show {
            ThreePointAngleScreen(ThemeState.DEFAULT, {}, angleState.copy(picking = AnglePoint.A), ThreePointAngleActions(), onBack = {})
        }
        assertControls("selector")
    }

    @Test
    fun `S026 RNF-T8 el boton Informacion de la ficha del elemento tiene etiqueta y 56 dp`() {
        val entity = CadEntity(
            CadEntityKind.POLYLINE,
            doubleArrayOf(440_000.0, 440_030.0),
            doubleArrayOf(4_474_000.0, 4_474_000.0),
            null,
            null,
        )
        val selection = DesignSelection(CadEntityRef("l1", 1L, 0), entity, "Ejes", "calle.dxf", false)
        show { DesignElementSheet(selection, LinearUnit.METRE, "m", geographic = false, onDismiss = {}, onInformation = {}) }

        compose.onNodeWithText("Información").assertExists()
        compose.onNodeWithTag(DESIGN_INFORMATION_TAG).assertHeightIsAtLeast(56.dp).assertHasClickAction()
    }

    @Test
    @Config(qualifiers = "en")
    fun `S026 RNF-T7 en ingles la informacion sale en ingles`() {
        show { GeometryInfoScreen(ThemeState.DEFAULT, {}, state, GeometryInfoActions(), onBack = {}) }

        compose.onNodeWithText("Grid distances").assertExists()
        compose.onNodeWithText("Ground").assertExists()
        compose.onNodeWithTag(GEOMETRY_INFO_TAG).performScrollToNode(hasText("Segments"))
        compose.onNodeWithText("Segments").assertExists()
    }

    @Test
    @Config(qualifiers = "en")
    fun `S026 RNF-T7 en ingles el angulo sale en ingles`() {
        show { ThreePointAngleScreen(ThemeState.DEFAULT, {}, angleState, ThreePointAngleActions(), onBack = {}) }

        compose.onNodeWithText("Angle at B").assertExists()
        compose.onNodeWithText("Point A: P1").assertExists()
    }
}
