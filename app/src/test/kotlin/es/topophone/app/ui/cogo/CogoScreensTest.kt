package es.topophone.app.ui.cogo

import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.core.designsystem.theme.TopoPhoneTheme
import es.topophone.core.model.cogo.LegKind
import es.topophone.core.model.preferences.EffectiveTheme
import es.topophone.core.model.preferences.ThemeState
import es.topophone.core.survey.cogo.ClosureError
import es.topophone.core.survey.cogo.CogoPoint
import es.topophone.core.survey.cogo.TraverseStep
import es.topophone.core.survey.cogo.TraverseStop
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** La pantalla de la poligonal: tramos, puntos calculados, cierre y avisos (S038/RF-10 a RF-12, RF-31, RNF-T7, RNF-T8). */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "es-rES")
class CogoScreensTest {

    @get:Rule
    val compose = createComposeRule()

    private fun leg(
        number: Int,
        kind: LegKind = LegKind.AZIMUTH,
        step: TraverseStep? = null,
        height: Double? = null,
        valueInvalid: Boolean = false,
    ) = TraverseLegUi(number, kind, "90", "100", "", valueInvalid, false, false, step, height)

    private fun step(number: Int, east: Double, north: Double) = TraverseStep(number, east, north, 1.5707963, 100.0, null)

    private fun ui(legs: List<TraverseLegUi> = emptyList(), block: TraverseUi.() -> TraverseUi = { this }) = TraverseUi(
        loaded = true,
        start = CogoSlotValue.Found(CogoPoint(440_000.0, 4_474_000.0, 650.0), "P1"),
        legs = legs,
    ).block()

    private fun show(state: TraverseUi, actions: TraverseActions = TraverseActions()) {
        compose.setContent {
            TopoPhoneTheme(EffectiveTheme.LIGHT) { TraverseScreen(ThemeState.DEFAULT, {}, state, actions, onBack = {}) }
        }
    }

    private fun scrollTo(tag: String) {
        compose.onNodeWithTag(TRAVERSE_LIST_TAG).performScrollToNode(hasTestTag(tag))
    }

    private fun count(tag: String) = compose.onAllNodesWithTag(tag).fetchSemanticsNodes().size

    @Test
    fun `S038 RF-10 la pantalla muestra el inicio, la referencia y el cierre, y anade un tramo`() {
        var added = 0
        show(ui(), TraverseActions(onAddLeg = { added++ }))

        compose.onNodeWithTag(cogoPointTag(SLOT_START)).assertTextContains("P1", substring = true)
        scrollTo(cogoPointTag(SLOT_REFERENCE))
        compose.onNodeWithTag(cogoPointTag(SLOT_REFERENCE)).assertIsDisplayed()
        scrollTo(TRAVERSE_ADD_TAG)
        compose.onNodeWithTag(TRAVERSE_ADD_TAG).assertHeightIsAtLeast(56.dp).performClick()
        scrollTo(cogoPointTag(SLOT_CLOSURE))
        compose.onNodeWithTag(cogoPointTag(SLOT_CLOSURE)).assertIsDisplayed()

        added shouldBe 1
    }

    @Test
    fun `S038 RF-10 un tramo tiene su clase, sus campos y se quita`() {
        val removed = mutableListOf<Int>()
        val kinds = mutableListOf<Pair<Int, LegKind>>()
        show(
            ui(listOf(leg(1), leg(2, LegKind.DEFLECTION_LEFT))),
            TraverseActions(onRemoveLeg = { removed += it }, onKind = { i, k ->
                kinds +=
                    i to k
            }),
        )

        scrollTo(traverseLegTag(2))
        compose.onNodeWithTag(traverseKindTag(2)).assertTextContains("Deflexión a la izquierda", substring = true).performClick()
        compose.onNodeWithTag(traverseKindOptionTag(LegKind.ANGLE_RIGHT)).performClick()
        compose.onNodeWithTag(cogoNumberTag(traverseFieldKey(2, "value"))).assertIsDisplayed()
        compose.onNodeWithTag(cogoNumberTag(traverseFieldKey(2, "distance"))).assertIsDisplayed()
        compose.onNodeWithTag(cogoNumberTag(traverseFieldKey(2, "dh"))).assertIsDisplayed()
        compose.onNodeWithTag(traverseRemoveTag(2)).assertHeightIsAtLeast(56.dp).performClick()

        kinds shouldBe listOf(1 to LegKind.ANGLE_RIGHT)
        removed shouldBe listOf(1)
    }

    @Test
    fun `S038 RF-11 el punto de cada tramo sale con su Este, su Norte y su altura o sin ella`() {
        show(ui(listOf(leg(1, step = step(1, 440_100.5, 4_474_000.0), height = 651.5), leg(2, step = step(2, 440_100.5, 4_474_050.0)))))

        scrollTo(traverseStepTag(1))
        compose.onNodeWithTag(traverseStepTag(1)).assertTextContains("440.100,500", substring = true)
            .assertTextContains("651,500", substring = true)
        scrollTo(traverseStepTag(2))
        compose.onNodeWithTag(traverseStepTag(2)).assertTextContains("Sin altura", substring = true)
    }

    @Test
    fun `S038 RF-10 un tramo sin calcular no muestra punto y un campo no valido se dice con texto`() {
        show(ui(listOf(leg(1, valueInvalid = true))))

        scrollTo(traverseLegTag(1))
        count(traverseStepTag(1)) shouldBe 0
        compose.onNodeWithTag(
            cogoNumberErrorTag(traverseFieldKey(1, "value")),
            useUnmergedTree = true,
        ).assertTextContains("no válido", substring = true)
    }

    @Test
    fun `S038 RF-31 la visual atras indefinida se dice y dice el tramo`() {
        show(ui(listOf(leg(1, LegKind.ANGLE_RIGHT))) { copy(stoppedAt = 1, stopReason = TraverseStop.BACKSIGHT_UNDEFINED) })

        scrollTo(TRAVERSE_STOP_TAG)
        compose.onNodeWithTag(
            TRAVERSE_STOP_TAG,
        ).assertTextContains("tramo 1", substring = true).assertTextContains("indefinida", substring = true)
    }

    @Test
    fun `S038 RF-10 sin referencia se pide el punto de referencia`() {
        show(ui(listOf(leg(1, LegKind.ANGLE_LEFT))) { copy(needsReference = true, stoppedAt = 1, stopReason = TraverseStop.NO_REFERENCE) })

        scrollTo(TRAVERSE_NEEDS_REFERENCE_TAG)
        compose.onNodeWithTag(TRAVERSE_NEEDS_REFERENCE_TAG).assertIsDisplayed()
        scrollTo(TRAVERSE_STOP_TAG)
        compose.onNodeWithTag(TRAVERSE_STOP_TAG).assertTextContains("punto de referencia", substring = true)
    }

    @Test
    fun `S038 RF-10 un tramo a medio escribir lo dice sin ser un error`() {
        show(ui(listOf(leg(1))) { copy(stoppedAt = 1, stopReason = TraverseStop.INCOMPLETE_LEG) })

        scrollTo(TRAVERSE_STOP_TAG)
        compose.onNodeWithTag(TRAVERSE_STOP_TAG).assertTextContains("Completa", substring = true)
    }

    @Test
    fun `S038 RF-12 el error de cierre sale en Este y Norte, lineal y con la precision relativa`() {
        show(ui(listOf(leg(1, step = step(1, 1.0, 2.0)))) { copy(closure = ClosureError(-3.0, 4.0, 5.0, 100.0, 20.0)) })

        scrollTo(TRAVERSE_CLOSURE_TAG)
        compose.onNodeWithTag(TRAVERSE_CLOSURE_TAG).assertTextContains("Error de cierre", substring = true)
            .assertTextContains("-3,000 m", substring = true).assertTextContains("4,000 m", substring = true)
            .assertTextContains("5,000 m", substring = true).assertTextContains("1:20", substring = true)
    }

    @Test
    fun `S038 RF-12 una poligonal que cierra lo dice y no da precision`() {
        show(ui(listOf(leg(1, step = step(1, 1.0, 2.0)))) { copy(closure = ClosureError(0.0, 0.0, 0.0, 100.0, null)) })

        scrollTo(TRAVERSE_CLOSURE_TAG)
        compose.onNodeWithTag(TRAVERSE_CLOSES_TAG, useUnmergedTree = true).assertTextContains("cierra", substring = true)
        compose.onNodeWithTag(TRAVERSE_CLOSURE_TAG).assertTextContains("0,000 m", substring = true)
    }

    @Test
    fun `S038 RF-10 quitar el punto de referencia o de cierre avisa y solo sale si hay uno elegido`() {
        val cleared = mutableListOf<String>()
        val found = CogoSlotValue.Found(CogoPoint(1.0, 2.0), "R")
        show(ui { copy(reference = found, closurePoint = found) }, TraverseActions(onClear = { cleared += it }))

        scrollTo(TRAVERSE_CLEAR_REFERENCE_TAG)
        compose.onNodeWithTag(TRAVERSE_CLEAR_REFERENCE_TAG).assertHeightIsAtLeast(56.dp).performClick()
        scrollTo(TRAVERSE_CLEAR_CLOSURE_TAG)
        compose.onNodeWithTag(TRAVERSE_CLEAR_CLOSURE_TAG).performClick()

        cleared shouldBe listOf(SLOT_REFERENCE, SLOT_CLOSURE)
    }

    @Test
    fun `S038 RF-10 sin punto de referencia ni de cierre no hay botones de quitar`() {
        show(ui())

        count(TRAVERSE_CLEAR_REFERENCE_TAG) shouldBe 0
        count(TRAVERSE_CLEAR_CLOSURE_TAG) shouldBe 0
    }

    @Test
    fun `S038 RF-27 descartar el borrador pide confirmacion y solo entonces avisa`() {
        var discards = 0
        show(ui(listOf(leg(1))) { copy(hasDraft = true) }, TraverseActions(onDiscard = { discards++ }))

        scrollTo(TRAVERSE_DISCARD_TAG)
        compose.onNodeWithTag(TRAVERSE_DISCARD_TAG).assertHeightIsAtLeast(56.dp).performClick()
        compose.onNodeWithTag(TRAVERSE_DISCARD_DIALOG_TAG).assertTextContains("No se ha guardado ningún punto", substring = true)
        discards shouldBe 0
        compose.onNodeWithTag(TRAVERSE_DISCARD_CONFIRM_TAG).performClick()

        discards shouldBe 1
    }

    @Test
    fun `S038 RF-27 sin nada escrito no se ofrece descartar`() {
        show(ui { copy(hasDraft = false) })

        count(TRAVERSE_DISCARD_TAG) shouldBe 0
    }
}
