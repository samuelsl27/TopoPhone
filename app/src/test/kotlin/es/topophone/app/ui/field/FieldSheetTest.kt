package es.topophone.app.ui.field

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.core.designsystem.theme.TopoPhoneTheme
import es.topophone.core.model.preferences.EffectiveTheme
import io.kotest.matchers.comparables.shouldBeLessThanOrEqualTo
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** La hoja de campo: dos alturas, asa, accesibilidad, altura guardada y «Cerrar» (S060/RF-1 a RF-6). */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "es-rES-w411dp-h891dp")
class FieldSheetTest {

    @get:Rule
    val compose = createComposeRule()

    private var closed = 0

    @Composable
    private fun Content() {
        TopoPhoneTheme(EffectiveTheme.LIGHT) {
            var state by rememberSaveable { mutableStateOf(FieldSheetValue.Folded) }
            Box(Modifier.fillMaxSize()) {
                FieldSheet(
                    value = state,
                    onValueChange = { state = it },
                    onClose = { closed++ },
                    modifier = Modifier.align(Alignment.BottomCenter),
                    folded = { Text("plegado", Modifier.height(100.dp)) },
                    expanded = { Text("expandido", Modifier.height(100.dp)) },
                )
            }
        }
    }

    private fun show() = compose.setContent { Content() }

    private fun expandedShown() = compose.onAllNodesWithTag(FIELD_SHEET_EXPANDED_TAG).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun `S060 RF-3 la hoja abre plegada y sin la parte expandida`() {
        show()

        compose.onNodeWithTag(FIELD_SHEET_TAG).assertIsDisplayed()
        expandedShown() shouldBe false
    }

    @Test
    fun `S060 RF-4 tocar el asa alterna entre plegada y expandida`() {
        show()

        compose.onNodeWithTag(FIELD_SHEET_HANDLE_TAG).performClick()
        expandedShown() shouldBe true
        compose.onNodeWithTag(FIELD_SHEET_HANDLE_TAG).performClick()
        expandedShown() shouldBe false
    }

    @Test
    fun `S060 RF-4 arrastrar el asa hacia arriba expande y hacia abajo pliega`() {
        show()

        compose.onNodeWithTag(FIELD_SHEET_HANDLE_TAG).performTouchInput { swipeUp() }
        expandedShown() shouldBe true
        compose.onNodeWithTag(FIELD_SHEET_HANDLE_TAG).performTouchInput { swipeDown() }
        expandedShown() shouldBe false
    }

    @Test
    fun `S060 RF-4 con TalkBack las acciones Expandir y Plegar cambian la altura`() {
        show()

        fun actions() = compose.onNodeWithTag(FIELD_SHEET_HANDLE_TAG).fetchSemanticsNode().config
            .getOrNull(SemanticsActions.CustomActions).orEmpty()

        actions().map { it.label } shouldBe listOf("Expandir")
        compose.runOnUiThread { actions().single().action() }
        compose.waitForIdle()
        expandedShown() shouldBe true
        actions().map { it.label } shouldBe listOf("Plegar")
        compose.runOnUiThread { actions().single().action() }
        compose.waitForIdle()
        expandedShown() shouldBe false
    }

    @Test
    fun `S060 RF-5 la altura se conserva al recrear la actividad`() {
        val restoration = StateRestorationTester(compose)
        restoration.setContent { Content() }
        compose.onNodeWithTag(FIELD_SHEET_HANDLE_TAG).performClick()

        restoration.emulateSavedInstanceStateRestore()

        expandedShown() shouldBe true
    }

    @Test
    fun `S060 RF-6 Cerrar avisa de que se cierra la hoja`() {
        show()

        compose.onNodeWithTag(FIELD_SHEET_CLOSE_TAG).performClick()

        closed shouldBe 1
    }

    @Test
    fun `S060 RF-1 la hoja plegada mide lo que su contenido y la expandida no pasa del 70 por ciento`() {
        show()
        val folded = compose.onNodeWithTag(FIELD_SHEET_TAG).getUnclippedBoundsInRoot()
        compose.onNodeWithTag(FIELD_SHEET_HANDLE_TAG).performClick()
        val expanded = compose.onNodeWithTag(FIELD_SHEET_TAG).getUnclippedBoundsInRoot()
        val screen = compose.onRoot().getUnclippedBoundsInRoot()

        (expanded.height > folded.height) shouldBe true
        (expanded.height / screen.height) shouldBeLessThanOrEqualTo 0.7f
    }
}
