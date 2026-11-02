package es.topophone.app.ui.slope

import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.core.designsystem.theme.TopoPhoneTheme
import es.topophone.core.model.alignment.AlignmentExtent
import es.topophone.core.model.alignment.AlignmentSource
import es.topophone.core.model.alignment.AlignmentSummary
import es.topophone.core.model.alignment.section.HeightKind
import es.topophone.core.model.alignment.slope.ManualEdge
import es.topophone.core.model.alignment.slope.SlopeIssue
import es.topophone.core.model.alignment.slope.SlopeRule
import es.topophone.core.model.alignment.slope.SlopeSide
import es.topophone.core.model.preferences.EffectiveTheme
import es.topophone.core.model.preferences.ThemeState
import es.topophone.data.slope.StoredSlope
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** La pantalla «Taludes» de un eje: la lista, el alta con borde manual y el rechazo junto a Guardar (S051/RF-1 a RF-3). */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "es-rES")
class SlopesScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val axis = AlignmentSummary(
        "a", "p", "Eje A", null, 1L, AlignmentSource.MANUAL, 0.0, 420.0, true, true, false, AlignmentExtent(0.0, 1.0, 0.0, 1.0),
    )

    private fun show(state: SlopesUiState, actions: SlopesActions = SlopesActions()) {
        compose.setContent {
            TopoPhoneTheme(EffectiveTheme.LIGHT) { SlopesScreen(ThemeState.DEFAULT, {}, state, actions, {}) }
        }
    }

    private val full = SlopesUiState(
        alignment = axis,
        loading = false,
        rules = listOf(
            StoredSlope("r1", SlopeRule(SlopeSide.RIGHT, 0.0, 100.0, 1.5, 2.0, "CD"), 1L),
            StoredSlope("r2", SlopeRule(SlopeSide.LEFT, 0.0, 100.0, 1.0, 2.5), 2L),
            StoredSlope(
                "r3",
                SlopeRule(SlopeSide.RIGHT, 100.0, 200.0, 1.5, 2.0, manualEdge = ManualEdge(4.0, -0.5, HeightKind.RELATIVE)),
                3L,
            ),
        ),
    )

    @Test
    fun `S051 RF-1 sin taludes la lista lo dice`() {
        show(SlopesUiState(alignment = axis, loading = false))

        compose.onNodeWithTag(SLOPES_EMPTY_TAG).assertIsDisplayed()
    }

    @Test
    fun `S051 RF-1 cada talud dice el lado con palabra el rango las relaciones y el borde`() {
        show(full)

        compose.onNodeWithText(
            "Derecha · de PK 0+000,00 a PK 0+100,00 · desmonte 1,5 : 1 · terraplén 2 : 1 · borde: código CD",
        ).assertExists()
        compose.onNodeWithText(
            "Izquierda · de PK 0+000,00 a PK 0+100,00 · desmonte 1 : 1 · terraplén 2,5 : 1 · borde: exterior de la sección",
        ).assertExists()
        compose.onNodeWithText("borde manual a 4,000 m, cota -0,500 m", substring = true).assertExists()
    }

    @Test
    fun `S051 RF-1 borrar lleva el identificador y anadir abre el alta`() {
        val calls = mutableListOf<String>()
        show(full, SlopesActions(onAdd = { calls += "add" }, onDelete = { calls += "del:$it" }))

        compose.onNodeWithTag(slopesDeleteTag("r2")).performScrollTo().assertHeightIsAtLeast(56.dp).performClick()
        compose.onNodeWithTag(SLOPES_ADD_TAG).performScrollTo().assertHeightIsAtLeast(56.dp).performClick()

        calls shouldBe listOf("del:r2", "add")
    }

    @Test
    fun `S051 RF-1 el alta pide el lado el rango las dos pendientes y el codigo del borde`() {
        val edits = mutableListOf<SlopeDraft>()
        show(
            SlopesUiState(alignment = axis, loading = false, draft = SlopeDraft()),
            SlopesActions(onDraft = { change -> edits += change(SlopeDraft()) }),
        )

        compose.onNodeWithTag(SLOPES_EDITOR_TAG).assertIsDisplayed()
        compose.onNodeWithTag(slopesSideTag(SlopeSide.LEFT)).performClick()
        compose.onNodeWithTag(SLOPES_FROM_TAG).performTextInput("0")
        compose.onNodeWithTag(SLOPES_CUT_TAG).performTextInput("1,5")
        compose.onNodeWithTag(SLOPES_CODE_TAG).performScrollTo().assertExists()

        edits.first().side shouldBe SlopeSide.LEFT
        edits.any { it.from == "0" } shouldBe true
        edits.any { it.cut == "1,5" } shouldBe true
    }

    @Test
    fun `S051 RF-2 con borde manual se piden el desplazamiento y la cota y no el codigo`() {
        show(SlopesUiState(alignment = axis, loading = false, draft = SlopeDraft(manual = true)))

        compose.onNodeWithTag(SLOPES_OFFSET_TAG).performScrollTo().assertExists()
        compose.onNodeWithTag(SLOPES_HEIGHT_TAG).performScrollTo().assertExists()
        compose.onNodeWithTag(SLOPES_RELATIVE_TAG).performScrollTo().assertExists()
        compose.onNodeWithTag(SLOPES_CODE_TAG).assertDoesNotExist()
    }

    @Test
    fun `S051 RF-3 un rechazo dice el motivo junto al boton Guardar`() {
        show(
            SlopesUiState(
                alignment = axis,
                loading = false,
                draft = SlopeDraft(),
                problem = SlopesProblem.Rejected(SlopeIssue.OverlappingRanges(SlopeSide.RIGHT, 50.0, 150.0)),
            ),
        )

        compose.onNodeWithTag(SLOPES_PROBLEM_TAG).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("El rango de PK 0+050,00 a 0+150,00 se solapa con otro talud del lado Derecha.").assertExists()
        compose.onNodeWithTag(SLOPES_SAVE_TAG).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `S051 RF-3 una pendiente invalida y un numero mal escrito se dicen`() {
        show(
            SlopesUiState(
                alignment = axis,
                loading = false,
                draft = SlopeDraft(),
                problem = SlopesProblem.Rejected(SlopeIssue.InvalidRatio(SlopeSide.LEFT, 0.0)),
            ),
        )
        compose.onNodeWithText("La pendiente 0 del lado Izquierda no vale: tiene que ser mayor que 0.").assertExists()
    }

    @Test
    fun `S051 RF-3 un campo que no es un numero dice cual`() {
        show(SlopesUiState(alignment = axis, loading = false, draft = SlopeDraft(), problem = SlopesProblem.InvalidNumber(SlopeField.CUT)))

        compose.onNodeWithText("Desmonte no es un número.").assertExists()
    }

    @Test
    fun `S051 RF-1 guardar y cancelar avisan`() {
        val calls = mutableListOf<String>()
        show(
            SlopesUiState(alignment = axis, loading = false, draft = SlopeDraft()),
            SlopesActions(onSave = { calls += "save" }, onCancel = { calls += "cancel" }),
        )

        compose.onNodeWithTag(SLOPES_SAVE_TAG).performScrollTo().performClick()
        compose.onNodeWithTag(SLOPES_CANCEL_TAG).performScrollTo().performClick()

        calls shouldBe listOf("save", "cancel")
    }
}
