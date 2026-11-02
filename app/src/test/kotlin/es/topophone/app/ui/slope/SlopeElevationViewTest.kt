package es.topophone.app.ui.slope

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.core.designsystem.theme.TopoPhoneTheme
import es.topophone.core.model.alignment.slope.SlopeKind
import es.topophone.core.model.alignment.slope.SlopeRule
import es.topophone.core.model.alignment.slope.SlopeSide
import es.topophone.core.model.preferences.EffectiveTheme
import es.topophone.core.survey.AxisPoint
import es.topophone.core.survey.SlopeCatchResult
import es.topophone.core.survey.SlopeEdgeResult
import es.topophone.core.survey.SlopeLiveValue
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** El alzado del talud con la punta en vivo: el borde, el talud de diseño, el terreno y la punta (S051/RF-10, RF-11; RNF-T8). */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "es-rES")
class SlopeElevationViewTest {

    @get:Rule
    val compose = createComposeRule()

    private val rule = SlopeRule(SlopeSide.RIGHT, 0.0, 200.0, 1.5, 2.0, "BD")
    private val axisPoint = AxisPoint(105.0, 3.5, 441_105.0, 4_473_996.5, 441_105.0, 4_474_000.0, 0.0, 0.0)
    private val edge = SlopeEdgeResult.Edge(rule, 105.0, 3.5, 100.0, axisPoint)
    private val found = SlopeCatchResult.Found(SlopeKind.CUT, 1.5, 5.0, 101.0, 1.0, axisPoint)

    // La punta a 0,9 m del borde: el talud de diseño pasa ahí por 100,6 y la punta está 0,2 m por encima.
    private val live = SlopeLiveValue(SlopeKind.CUT, 100.6, 0.2, 0.3, 0.1, onLine = true, found = false)

    private fun state(outcome: SlopePointOutcome?, live: SlopeLiveValue? = null) =
        SlopeStakeUiState(loaded = true, started = true, outcome = outcome, live = live)

    @Test
    fun `S051 RF-10 el alzado pone el punto de talud y la punta respecto al borde`() {
        val model = SlopeElevation.of(state(SlopePointOutcome.Found(edge, found, null), live))

        model.shouldNotBeNull()
        model.kind shouldBe SlopeKind.CUT
        model.ratio shouldBe 1.5
        model.edgeHeightM shouldBe 100.0
        model.catchT.shouldNotBeNull() shouldBe (1.5 plusOrMinus 1e-9)
        model.catchHeightM.shouldNotBeNull() shouldBe (101.0 plusOrMinus 1e-9)
        model.tipT.shouldNotBeNull() shouldBe (0.9 plusOrMinus 1e-9)
        model.tipHeightM.shouldNotBeNull() shouldBe (100.8 plusOrMinus 1e-9)
        model.slopeAt(1.5) shouldBe (101.0 plusOrMinus 1e-9)
    }

    @Test
    fun `S051 RF-10 en un terraplen el talud de diseno baja hacia fuera`() {
        val fill = SlopeCatchResult.Found(SlopeKind.FILL, 3.4, 6.9, 98.3, 1.7, axisPoint)
        val model = SlopeElevation.of(state(SlopePointOutcome.Found(edge, fill, null)))

        model.shouldNotBeNull()
        model.kind shouldBe SlopeKind.FILL
        model.ratio shouldBe 2.0
        model.slopeAt(3.4) shouldBe (98.3 plusOrMinus 1e-9)
        model.tipT.shouldBeNull()
    }

    @Test
    fun `S051 RF-10 sin terreno el tipo lo dice la punta y sin punta no hay talud que dibujar`() {
        val noTerrain = SlopePointOutcome.NoTerrain(edge)

        SlopeElevation.of(state(noTerrain)).shouldBeNull()
        SlopeElevation.of(state(noTerrain, live.copy(kind = SlopeKind.FILL, slopeHeightM = 99.7)))?.kind shouldBe SlopeKind.FILL
    }

    @Test
    fun `S051 RF-8 sin borde no hay alzado`() {
        SlopeElevation.of(state(SlopePointOutcome.Unavailable(SlopeEdgeResult.NoRule), live)).shouldBeNull()
        SlopeElevation.of(state(null, live)).shouldBeNull()
    }

    @Test
    fun `S051 RNF-T8 el alzado se dice con texto para TalkBack`() {
        val model = SlopeElevation.of(state(SlopePointOutcome.Found(edge, found, null), live))
        compose.setContent {
            TopoPhoneTheme(EffectiveTheme.LIGHT) { SlopeElevationView(model.shouldNotBeNull(), "Punta 0,200 m por encima del talud") }
        }

        compose.onNodeWithTag(SLOPE_ELEVATION_TAG).assertIsDisplayed()
    }
}
