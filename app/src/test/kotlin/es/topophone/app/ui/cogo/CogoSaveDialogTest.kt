package es.topophone.app.ui.cogo

import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.core.designsystem.theme.TopoPhoneTheme
import es.topophone.core.model.preferences.EffectiveTheme
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** La hoja de guardado: nombres editables, problemas dichos con texto y resultado del guardado (S038/RF-19 a RF-21, RNF-T7, RNF-T8). */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "es-rES")
class CogoSaveDialogTest {

    @get:Rule
    val compose = createComposeRule()

    private val labels = mapOf("leg1" to "Punto al final del tramo 1", "leg2" to "Punto al final del tramo 2")

    private fun ui(
        status: CogoSaveStatus = CogoSaveStatus.Idle,
        rows: List<CogoNameRow> = listOf(CogoNameRow("leg1", "P4"), CogoNameRow("leg2", "P5")),
    ) = CogoSaveUi(open = true, rows = rows, status = status)

    private fun show(
        ui: CogoSaveUi,
        names: MutableList<Pair<String, String>> = mutableListOf(),
        events: MutableList<String> = mutableListOf(),
    ) {
        compose.setContent {
            TopoPhoneTheme(EffectiveTheme.LIGHT) {
                CogoSaveDialog(
                    ui,
                    labels,
                    onName = { key, name -> names += key to name },
                    onSave = { events += "save" },
                    onDismiss = { events += "dismiss" },
                )
            }
        }
    }

    private fun count(tag: String) = compose.onAllNodesWithTag(tag).fetchSemanticsNodes().size

    @Test
    fun `S038 RF-19 la hoja muestra un nombre editable por punto y guarda o cancela`() {
        val names = mutableListOf<Pair<String, String>>()
        val events = mutableListOf<String>()
        show(ui(), names, events)

        compose.onNodeWithTag(cogoSaveNameTag("leg1")).assertTextContains("P4", substring = true).assertHeightIsAtLeast(56.dp)
        compose.onNodeWithTag(cogoSaveNameTag("leg2")).assertTextContains("P5", substring = true)
        compose.onNodeWithTag(cogoSaveNameTag("leg1")).performTextInput("x")
        compose.onNodeWithTag(COGO_SAVE_CONFIRM_TAG).assertHeightIsAtLeast(56.dp).assertIsEnabled().performClick()

        names.single().first shouldBe "leg1"
        events shouldBe listOf("save")
    }

    @Test
    fun `S038 RF-20 un nombre con problema lo dice con texto y avisa de que no se ha guardado ninguno`() {
        show(
            ui(
                CogoSaveStatus.NameProblems,
                listOf(CogoNameRow("leg1", "P1", NameProblem.EXISTS), CogoNameRow("leg2", "", NameProblem.EMPTY)),
            ),
        )

        compose.onNodeWithTag(COGO_SAVE_STATUS_TAG).assertTextContains("No se ha guardado ningún punto", substring = true)
        compose.onNodeWithTag(cogoSaveProblemTag("leg1"), useUnmergedTree = true).assertTextContains("Ya existe", substring = true)
        compose.onNodeWithTag(cogoSaveProblemTag("leg2"), useUnmergedTree = true).assertTextContains("Escribe un nombre")
    }

    @Test
    fun `S038 RF-20 un nombre repetido se dice`() {
        show(
            ui(
                CogoSaveStatus.NameProblems,
                listOf(CogoNameRow("leg1", "X", NameProblem.REPEATED), CogoNameRow("leg2", "X", NameProblem.REPEATED)),
            ),
        )

        compose.onNodeWithTag(cogoSaveProblemTag("leg1"), useUnmergedTree = true).assertTextContains("repetido", substring = true)
    }

    @Test
    fun `S038 RF-21 fuera del ambito dice cual de los puntos y que no se ha guardado ninguno`() {
        show(ui(CogoSaveStatus.OutOfScope(listOf("leg2"))))

        compose.onNodeWithTag(COGO_SAVE_STATUS_TAG).assertTextContains("fuera del ámbito", substring = true)
            .assertTextContains("tramo 2", substring = true)
    }

    @Test
    fun `S038 RF-19 al guardar bien se dice cuantos y solo queda cerrar`() {
        val events = mutableListOf<String>()
        show(ui(CogoSaveStatus.Saved(2)), events = events)

        compose.onNodeWithTag(COGO_SAVE_STATUS_TAG).assertTextContains("Se han guardado 2 puntos", substring = true)
        count(COGO_SAVE_CONFIRM_TAG) shouldBe 0
        count(cogoSaveNameTag("leg1")) shouldBe 0
        compose.onNodeWithTag(COGO_SAVE_CLOSE_TAG).assertIsDisplayed().performClick()

        events shouldBe listOf("dismiss")
    }

    @Test
    fun `S038 RF-19 con un solo punto el aviso va en singular`() {
        show(ui(CogoSaveStatus.Saved(1)))

        compose.onNodeWithTag(COGO_SAVE_STATUS_TAG).assertTextContains("Se ha guardado 1 punto en el proyecto", substring = true)
    }

    @Test
    fun `S038 RF-19 guardando no se puede volver a pulsar Guardar`() {
        show(ui(CogoSaveStatus.Saving))

        compose.onNodeWithTag(COGO_SAVE_CONFIRM_TAG).assertIsNotEnabled()
    }

    @Test
    fun `S038 RF-19 el boton de abrir la hoja dice cuantos hay marcados y no se puede pulsar sin ninguno`() {
        var opened = 0
        compose.setContent {
            TopoPhoneTheme(EffectiveTheme.LIGHT) {
                androidx.compose.foundation.layout.Column {
                    CogoSaveButton(count = 2, enabled = true) { opened++ }
                }
            }
        }

        compose.onNodeWithTag(COGO_SAVE_BUTTON_TAG).assertTextContains("Guardar puntos (2)").assertHeightIsAtLeast(56.dp).performClick()

        opened shouldBe 1
    }

    @Test
    fun `S038 RF-19 sin ningun marcado el boton esta desactivado`() {
        compose.setContent { TopoPhoneTheme(EffectiveTheme.LIGHT) { CogoSaveButton(count = 0, enabled = true) {} } }

        compose.onNodeWithTag(COGO_SAVE_BUTTON_TAG).assertIsNotEnabled()
    }
}
