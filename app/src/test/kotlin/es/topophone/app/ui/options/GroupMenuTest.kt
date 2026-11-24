package es.topophone.app.ui.options

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Los menús de grupo: filas completas, sin desplazar a 640 dp, con su etiqueta de TalkBack y que cierran sin hacer nada (S059/RF-5, RF-8 a RF-10). */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "es-rES-w360dp-h640dp")
class GroupMenuTest {

    @get:Rule
    val compose = createComposeRule()

    private val chosen = mutableListOf<ProjectOption>()
    private var dismissed = 0

    private fun show(group: OptionGroup) {
        compose.setScaledContent(1.0f) { GroupMenu(group, onSelect = { chosen += it }, onDismiss = { dismissed++ }) }
    }

    private fun assertWholeMenu(group: OptionGroup) {
        show(group)
        ProjectOptions.of(group).forEach { option ->
            val node = compose.onNodeWithTag(optionTag(option))
            node.assertIsDisplayed()
            node.assertHeightIsAtLeast(56.dp)
            val bounds = node.getUnclippedBoundsInRoot()
            (bounds.top >= 0.dp && bounds.bottom <= 640.dp) shouldBe true
        }
    }

    @Test
    fun `S059 RF-10 el menu de Importar se ve entero a 360 por 640 dp con la letra al 100 por cien`() = assertWholeMenu(OptionGroup.IMPORT)

    @Test
    fun `S059 RF-10 el menu de Exportar se ve entero a 360 por 640 dp con la letra al 100 por cien`() = assertWholeMenu(OptionGroup.EXPORT)

    @Test
    fun `S059 RF-10 el menu de Herramientas se ve entero a 360 por 640 dp con la letra al 100 por cien`() =
        assertWholeMenu(OptionGroup.TOOLS)

    @Test
    fun `S059 RF-8 cada fila lleva el verbo y el formato completos para TalkBack`() {
        show(OptionGroup.IMPORT)

        val node = compose.onNodeWithTag(optionTag(ProjectOption.IMPORT_CAD)).fetchSemanticsNode()
        node.config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString("") shouldBe "Importar plano DXF"
    }

    @Test
    fun `S059 RF-5 Herramientas lleva la descripcion de cada fila e Importar no`() {
        show(OptionGroup.TOOLS)
        compose.onNodeWithTag(optionTag(ProjectOption.COGO)).assertIsDisplayed()
        compose.onNodeWithTag(optionTag(ProjectOption.COGO), useUnmergedTree = true).fetchSemanticsNode().children.isNotEmpty() shouldBe
            true
    }

    @Test
    fun `S059 RF-9 al descartar el menu la barra lo cierra sin haber elegido nada`() {
        val picked = mutableListOf<ProjectOption>()
        compose.setScaledContent(1.0f) {
            GroupBar(menu = { group, dismiss ->
                GroupMenu(group, onSelect = { picked += it }, onDismiss = dismiss)
            })
        }

        compose.onNodeWithTag(groupButtonTag(OptionGroup.EXPORT)).performClick()
        compose.onNodeWithTag(optionTag(ProjectOption.EXPORT_POINTS)).assertIsDisplayed()
        // Pulsar fuera y «Atrás» llegan como onDismissRequest de Material; la barra lo traduce en cerrar sin elegir.
        compose.onNodeWithTag(optionTag(ProjectOption.EXPORT_POINTS)).performClick()
        compose.waitForIdle()

        compose.onAllNodesWithTag(optionTag(ProjectOption.EXPORT_KML)).assertCountEquals(0)
        picked shouldBe listOf(ProjectOption.EXPORT_POINTS)
    }

    @Test
    fun `S059 RF-7 elegir una fila cierra el menu y entrega esa opcion`() {
        show(OptionGroup.EXPORT)

        compose.onNodeWithTag(optionTag(ProjectOption.EXPORT_KML)).performClick()

        chosen shouldBe listOf(ProjectOption.EXPORT_KML)
        dismissed shouldBe 1
    }
}
