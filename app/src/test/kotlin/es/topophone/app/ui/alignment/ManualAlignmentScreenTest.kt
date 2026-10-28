package es.topophone.app.ui.alignment

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.core.designsystem.theme.TopoPhoneTheme
import es.topophone.core.model.alignment.AlignmentIssue
import es.topophone.core.model.alignment.ProfileIssue
import es.topophone.core.model.preferences.EffectiveTheme
import es.topophone.core.model.preferences.ThemeState
import es.topophone.core.survey.PiIssue
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** La pantalla de definir un eje a mano: campos, resumen, problemas y resultado (S049/RF-8, RF-10, RF-12). */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "es-rES")
class ManualAlignmentScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val ready = ManualAlignmentUiState(
        loaded = true,
        name = "Zanja",
        vertices = listOf(VertexRow("440000", "4474000"), VertexRow("440100", "4474000", "0", "0")),
        problem = null,
        lengthM = 100.0,
        startChainageM = 250.0,
    )

    private fun show(state: ManualAlignmentUiState, actions: ManualAlignmentActions = ManualAlignmentActions()) {
        compose.setContent {
            TopoPhoneTheme(EffectiveTheme.LIGHT) { ManualAlignmentScreen(ThemeState.DEFAULT, {}, state, actions, {}) }
        }
    }

    @Test
    fun `S049 RF-8 la pantalla ofrece el nombre el PK inicial y los campos de cada vertice`() {
        show(ready)

        compose.onNodeWithTag(MANUAL_NAME_TAG).assertIsDisplayed()
        compose.onNodeWithTag(MANUAL_START_TAG).assertIsDisplayed()
        for (field in listOf(ManualField.EASTING, ManualField.NORTHING, ManualField.RADIUS, ManualField.SPIRAL)) {
            compose.onNodeWithTag(manualVertexTag(0, field)).performScrollTo().assertExists()
            compose.onNodeWithTag(manualVertexTag(1, field)).performScrollTo().assertExists()
        }
        compose.onAllNodesWithText("Este (m)").fetchSemanticsNodes().size shouldBe 2
        compose.onAllNodesWithText("Radio de la curva (m)").fetchSemanticsNodes().size shouldBe 2
        compose.onAllNodesWithText("Longitud de las clotoides (m)").fetchSemanticsNodes().size shouldBe 2
    }

    @Test
    fun `S049 RF-8 escribir en un campo avisa con la fila cambiada`() {
        var changed: Pair<Int, VertexRow>? = null
        show(ready, ManualAlignmentActions(onVertex = { index, row -> changed = index to row }))

        compose.onNodeWithTag(manualVertexTag(1, ManualField.RADIUS)).performScrollTo().performTextInput("150")

        changed!!.first shouldBe 1
        changed!!.second.radius shouldBe "1500" // el campo ya traía "0" y se escribe delante del cursor
        changed!!.second.easting shouldBe "440100"
    }

    @Test
    fun `S049 RF-8 se puede anadir y quitar un vertice y quitar solo cuando hay mas de dos`() {
        var added = 0
        var removed = -1
        show(
            ready.copy(vertices = ready.vertices + VertexRow("440100", "4474100")),
            ManualAlignmentActions(onAddVertex = { added++ }, onRemoveVertex = { removed = it }),
        )

        compose.onNodeWithTag(MANUAL_ADD_VERTEX_TAG).performScrollTo().performClick()
        compose.onNodeWithTag(manualRemoveVertexTag(2)).performScrollTo().performClick()

        added shouldBe 1
        removed shouldBe 2
    }

    @Test
    fun `S049 RF-8 con solo dos vertices no se ofrece quitarlos`() {
        show(ready)

        compose.onNodeWithTag(manualRemoveVertexTag(0)).assertDoesNotExist()
    }

    @Test
    fun `S049 RF-8 la rasante se escribe con PK cota y Kv y se pueden anadir y quitar sus vertices`() {
        var added = 0
        var removed = -1
        var changed: Pair<Int, ProfileRow>? = null
        show(
            ready.copy(profile = listOf(ProfileRow("250", "100"), ProfileRow("350", "101", ""))),
            ManualAlignmentActions(onAddProfile = {
                added++
            }, onRemoveProfile = { removed = it }, onProfile = { i, r -> changed = i to r }),
        )

        compose.onNodeWithTag(manualProfileTag(1, ManualField.KV)).performScrollTo().performTextInput("2000")
        compose.onNodeWithTag(MANUAL_ADD_PROFILE_TAG).performScrollTo().performClick()
        compose.onNodeWithTag(manualRemoveProfileTag(0)).performScrollTo().performClick()

        changed shouldBe (1 to ProfileRow("350", "101", "2000"))
        added shouldBe 1
        removed shouldBe 0
    }

    @Test
    fun `S049 RF-8 el resumen da la longitud y el PK final con el formato del proyecto`() {
        show(ready)

        compose.onNodeWithTag(MANUAL_SUMMARY_TAG).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Longitud del eje: 100,0 m, del PK 0+250,00 al 0+350,00.").assertExists()
    }

    @Test
    fun `S049 RF-8 con todo bien el boton crea y sin nada que decir no se ve ningun problema`() {
        var created = 0
        show(ready, ManualAlignmentActions(onCreate = { created++ }))

        compose.onNodeWithTag(MANUAL_PROBLEM_TAG).assertDoesNotExist()
        compose.onNodeWithTag(MANUAL_CREATE_TAG).performScrollTo().assertIsEnabled().performClick()

        created shouldBe 1
    }

    @Test
    fun `S049 RF-8 el nombre vacio no se reprocha antes de escribir nada pero impide crear`() {
        show(ready.copy(name = "", problem = ManualProblem.NameBlank))

        compose.onNodeWithTag(MANUAL_PROBLEM_TAG).assertDoesNotExist()
        compose.onNodeWithTag(MANUAL_CREATE_TAG).performScrollTo().assertIsNotEnabled()
    }

    @Test
    fun `S049 RF-8 cada problema de los vertices se dice con su texto`() {
        val cases = listOf(
            ManualProblem.NameBlank to "Escribe el nombre",
            ManualProblem.InvalidStart to "El PK inicial no es un número",
            ManualProblem.TooFewVertices to "al menos dos vértices",
            ManualProblem.InvalidNumber(2, ManualField.NORTHING, profile = false) to "Vértice 2, Norte: no es un número",
            ManualProblem.InvalidNumber(1, ManualField.KV, profile = true) to "Vértice 1 de la rasante, Kv: no es un número",
            ManualProblem.Geometry(PiIssue.CoincidentVertices(3)) to "El vértice 3 coincide con el anterior",
            ManualProblem.Geometry(PiIssue.InvalidParameter(2)) to "Vértice 2: el radio y las clotoides",
            ManualProblem.Geometry(PiIssue.SpiralTooLong(2)) to "clotoides son demasiado largas",
            ManualProblem.Geometry(PiIssue.TangentsOverlap(2)) to "no cabe entre sus vértices vecinos",
            ManualProblem.ProfileTooFew to "La rasante necesita al menos dos vértices",
            ManualProblem.Profile(ProfileIssue.RepeatedChainage(2, 100.0)) to "Rasante: el vértice 2 repite el PK 0+100,00",
            ManualProblem.Profile(ProfileIssue.OutsideAxis(2, 900.0)) to "Rasante: el vértice 2 (PK 0+900,00) está fuera del eje",
            ManualProblem.Profile(ProfileIssue.CurveDoesNotFit(2, 50.0)) to "el acuerdo del vértice 2 (PK 0+050,00) no cabe",
            ManualProblem.GeographicProject to "coordenadas geográficas",
        )
        val state = androidx.compose.runtime.mutableStateOf(ready.copy(name = "Zanja", problem = ManualProblem.InvalidStart))
        compose.setContent {
            TopoPhoneTheme(EffectiveTheme.LIGHT) {
                ManualAlignmentScreen(ThemeState.DEFAULT, {}, state.value, ManualAlignmentActions(), {})
            }
        }
        for ((problem, text) in cases) {
            state.value = state.value.copy(problem = problem)
            compose.waitForIdle()
            compose.onNodeWithTag(MANUAL_PROBLEM_TAG).performScrollTo().assertIsDisplayed()
            compose.onNodeWithText(text, substring = true).assertExists()
        }
    }

    @Test
    fun `S049 RF-8 el resultado dice el eje creado y cierra`() {
        show(ready.copy(result = ManualAlignmentResult.Created("Zanja", 100.0, 250.0, hasProfile = true)))

        compose.onNodeWithTag(MANUAL_RESULT_TAG).assertIsDisplayed()
        compose.onNodeWithText("Eje «Zanja» creado: 100,0 m, del PK 0+250,00 al 0+350,00.").assertIsDisplayed()
        compose.onNodeWithText("Lleva rasante.").assertIsDisplayed()
    }

    @Test
    fun `S049 RF-12 un eje rechazado o un fallo al guardar se dicen`() {
        show(ready.copy(result = ManualAlignmentResult.Rejected(AlignmentIssue.OutsideCrsArea)))
        compose.onNodeWithTag(MANUAL_ERROR_TAG).assertIsDisplayed()
        compose.onNodeWithText("fuera del ámbito", substring = true).assertExists()
    }
}
