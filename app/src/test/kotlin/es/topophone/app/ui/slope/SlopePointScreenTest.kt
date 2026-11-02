package es.topophone.app.ui.slope

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.core.designsystem.theme.TopoPhoneTheme
import es.topophone.core.model.alignment.slope.SlopeKind
import es.topophone.core.model.alignment.slope.SlopeRule
import es.topophone.core.model.alignment.slope.SlopeSide
import es.topophone.core.model.preferences.EffectiveTheme
import es.topophone.core.model.preferences.ThemeState
import es.topophone.core.survey.AxisPoint
import es.topophone.core.survey.SlopeCatchResult
import es.topophone.core.survey.SlopeEdgeResult
import es.topophone.core.survey.TerrainSource
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** La pantalla del punto de talud teórico: cabeza o pie, altura, coordenadas y los avisos sin terreno o sin intersección (S051/RF-5 a RF-8). */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "es-rES")
class SlopePointScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val rule = SlopeRule(SlopeSide.RIGHT, 0.0, 200.0, 1.5, 2.0, "BD")
    private val edge = SlopeEdgeResult.Edge(
        rule,
        105.0,
        3.5,
        100.0,
        AxisPoint(105.0, 3.5, 441_105.0, 4_473_996.5, 441_105.0, 4_474_000.0, 0.0, 0.0),
    )
    private val base = SlopePointUiState(loaded = true, alignmentName = "Eje C", endChainageM = 200.0, chainageText = "105")

    private fun show(state: SlopePointUiState, actions: SlopePointActions = SlopePointActions()) {
        compose.setContent {
            TopoPhoneTheme(EffectiveTheme.LIGHT) { SlopePointScreen(ThemeState.DEFAULT, {}, state, actions, {}) }
        }
    }

    @Test
    fun `S051 RF-5 la cabeza de desmonte dice el tipo la altura y las coordenadas`() {
        val found = SlopeCatchResult.Found(
            SlopeKind.CUT,
            1.5,
            5.0,
            101.0,
            1.0,
            AxisPoint(105.0, 5.0, 441_105.0, 4_473_995.0, 441_105.0, 4_474_000.0, 0.0, 0.0),
        )
        show(base.copy(outcome = SlopePointOutcome.Found(edge, found, TerrainSource.PROFILE)))

        compose.onNodeWithTag(SLOPE_POINT_KIND_TAG).assertIsDisplayed()
        compose.onNodeWithText("Cabeza de desmonte, talud 1,5 : 1").assertExists()
        compose.onNodeWithTag(SLOPE_POINT_HEIGHT_TAG).assertExists()
        compose.onNodeWithTag(SLOPE_POINT_EASTING_TAG).assertExists()
        compose.onNodeWithTag(SLOPE_POINT_SOURCE_TAG).assertExists()
    }

    @Test
    fun `S051 RF-5 el pie de terraplen se dice con su palabra`() {
        val found = SlopeCatchResult.Found(
            SlopeKind.FILL,
            3.4,
            6.9,
            98.3,
            1.7,
            AxisPoint(105.0, 6.9, 441_105.0, 4_473_993.1, 441_105.0, 4_474_000.0, 0.0, 0.0),
        )
        show(base.copy(outcome = SlopePointOutcome.Found(edge, found, TerrainSource.SURFACE)))

        compose.onNodeWithText("Pie de terraplén, talud 2 : 1").assertExists()
    }

    @Test
    fun `S051 RF-6 sin terreno y sin interseccion se dicen`() {
        show(base.copy(outcome = SlopePointOutcome.NoTerrain(edge)))
        compose.onNodeWithText("Sin terreno conocido: el punto de talud solo se puede buscar en vivo.").assertExists()
    }

    @Test
    fun `S051 RF-7 sin interseccion con el terreno se dice`() {
        show(base.copy(outcome = SlopePointOutcome.NoIntersection(edge)))
        compose.onNodeWithText("Sin intersección con el terreno.").assertExists()
    }

    @Test
    fun `S051 RF-8 un PK sin talud o con un borde que no existe se dice`() {
        show(base.copy(outcome = SlopePointOutcome.Unavailable(SlopeEdgeResult.NoEdge("ZZ"))))
        compose.onNodeWithText("La sección no tiene el código ZZ del borde.").assertExists()
    }

    @Test
    fun `S051 RF-5 escribir el PK y elegir el lado avisan`() {
        val calls = mutableListOf<String>()
        show(base.copy(chainageText = ""), SlopePointActions(onChainage = { calls += "pk:$it" }, onSide = { calls += "side:${it.name}" }))

        compose.onNodeWithTag(SLOPE_POINT_CHAINAGE_TAG).performTextInput("105")
        compose.onNodeWithTag(slopePointSideTag(SlopeSide.LEFT)).performClick()

        calls shouldBe listOf("pk:105", "side:LEFT")
    }
}
