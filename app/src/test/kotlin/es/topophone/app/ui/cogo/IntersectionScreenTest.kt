package es.topophone.app.ui.cogo

import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.core.designsystem.theme.TopoPhoneTheme
import es.topophone.core.model.cogo.IntersectionSide
import es.topophone.core.model.preferences.EffectiveTheme
import es.topophone.core.model.preferences.ThemeState
import es.topophone.core.survey.cogo.IntersectionResult
import es.topophone.core.survey.cogo.NoSolutionReason
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** La pantalla de la intersección: clases, soluciones con texto, motivos, altura y marcas (S038/RF-13 a RF-19, RNF-T8). */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "es-rES")
class IntersectionScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private fun solution(side: IntersectionSide, east: Double, north: Double, behind: Boolean = false, marked: Boolean = false) =
        IntersectionSolutionUi(side, east, north, 60.0, 80.0, behind, "", null, false, marked)

    private fun ui(
        kind: IntersectionKind = IntersectionKind.DISTANCE_DISTANCE,
        result: IntersectionResult? = null,
        solutions: List<IntersectionSolutionUi> = emptyList(),
    ) = IntersectionUi(loaded = true, kind = kind, result = result, solutions = solutions)

    private fun show(state: IntersectionUi, actions: IntersectionActions = IntersectionActions()) {
        compose.setContent {
            TopoPhoneTheme(EffectiveTheme.LIGHT) { IntersectionScreen(ThemeState.DEFAULT, {}, state, actions, onBack = {}) }
        }
    }

    private fun count(tag: String) = compose.onAllNodesWithTag(tag).fetchSemanticsNodes().size

    @Test
    fun `S038 RF-13 ofrece las tres clases y avisa de la elegida`() {
        val kinds = mutableListOf<IntersectionKind>()
        show(ui(), IntersectionActions(onKind = { kinds += it }))

        for (kind in IntersectionKind.entries) {
            compose.onNodeWithTag(intersectionKindTag(kind)).performScrollTo().assertHeightIsAtLeast(56.dp).performClick()
        }

        kinds shouldBe IntersectionKind.entries
    }

    @Test
    fun `S038 RF-14 las dos soluciones se dicen con texto, a la izquierda y a la derecha`() {
        val result = IntersectionResult.Solutions(emptyList())
        show(
            ui(
                result = result,
                solutions = listOf(
                    solution(IntersectionSide.LEFT, 440_036.5, 4_474_048.25),
                    solution(IntersectionSide.RIGHT, 440_036.5, 4_473_951.0),
                ),
            ),
        )

        compose.onNodeWithTag(intersectionSolutionTag(IntersectionSide.LEFT)).performScrollTo()
            .assertTextContains("izquierda", substring = true).assertTextContains("440.036,500", substring = true)
        compose.onNodeWithTag(
            intersectionSolutionTag(IntersectionSide.RIGHT),
        ).performScrollTo().assertTextContains("derecha", substring = true)
    }

    @Test
    fun `S038 RF-16 las soluciones de distancia-direccion son primera y segunda`() {
        val result = IntersectionResult.Solutions(emptyList())
        show(
            ui(
                IntersectionKind.DISTANCE_DIRECTION,
                result,
                listOf(solution(IntersectionSide.FIRST, 1.0, 2.0), solution(IntersectionSide.SECOND, 3.0, 4.0)),
            ),
        )

        compose.onNodeWithTag(
            intersectionSolutionTag(IntersectionSide.FIRST),
        ).performScrollTo().assertTextContains("Primera", substring = true)
        compose.onNodeWithTag(
            intersectionSolutionTag(IntersectionSide.SECOND),
        ).performScrollTo().assertTextContains("Segunda", substring = true)
    }

    @Test
    fun `S038 RF-15 una solucion detras de un punto lo avisa con texto`() {
        show(
            ui(
                result = IntersectionResult.Solutions(emptyList()),
                solutions = listOf(solution(IntersectionSide.SINGLE, 1.0, 2.0, behind = true)),
            ),
        )

        compose.onNodeWithTag(
            intersectionBehindTag(IntersectionSide.SINGLE),
            useUnmergedTree = true,
        ).assertTextContains("detrás", substring = true)
    }

    @Test
    fun `S038 RF-17 sin solucion dice el motivo`() {
        show(ui(result = IntersectionResult.NoSolution(NoSolutionReason.TOO_FAR)))

        compose.onNodeWithTag(INTERSECTION_NO_SOLUTION_TAG).performScrollTo().assertTextContains("Sin solución", substring = true)
            .assertTextContains("no llegan a cortarse", substring = true)
        count(intersectionSolutionTag(IntersectionSide.LEFT)) shouldBe 0
    }

    @Test
    fun `S038 RF-17 direcciones paralelas tienen su motivo`() {
        show(ui(IntersectionKind.DIRECTION_DIRECTION, IntersectionResult.NoSolution(NoSolutionReason.PARALLEL)))

        compose.onNodeWithTag(INTERSECTION_NO_SOLUTION_TAG).performScrollTo().assertTextContains("paralelas o coinciden", substring = true)
    }

    @Test
    fun `S038 RF-13 sin datos la pantalla pide los puntos y las condiciones`() {
        show(ui())

        compose.onNodeWithTag(INTERSECTION_HINT_TAG).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `S038 RF-19 marcar una solucion avisa y la altura tiene su campo`() {
        val marks = mutableListOf<IntersectionSide>()
        show(
            ui(result = IntersectionResult.Solutions(emptyList()), solutions = listOf(solution(IntersectionSide.LEFT, 1.0, 2.0))),
            IntersectionActions(onToggle = { marks += it }),
        )

        compose.onNodeWithTag(intersectionMarkTag(IntersectionSide.LEFT)).performScrollTo().assertHeightIsAtLeast(56.dp).performClick()
        compose.onNodeWithTag(cogoNumberTag("height_left")).performScrollTo().assertIsDisplayed()

        marks shouldBe listOf(IntersectionSide.LEFT)
    }

    @Test
    fun `S038 RF-4 una condicion no valida se dice con texto`() {
        show(ui().copy(value1Text = "abc", value1Invalid = true))

        compose.onNodeWithTag(cogoNumberErrorTag("value1"), useUnmergedTree = true).assertExists()
    }
}
