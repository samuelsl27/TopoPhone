package es.topophone.app.ui.options

import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.width
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** La barra de grupos: tres botones de igual ancho que se ven completos con 360 y 411 dp y la letra al 100 % y al 130 % (S059/RF-1). */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class GroupBarTest {

    @get:Rule
    val compose = createComposeRule()

    private fun check(widthDp: Int, fontScale: Float) {
        compose.setScaledContent(fontScale) { GroupBar() }

        val widths = BAR_GROUPS.map { group ->
            val node = compose.onNodeWithTag(groupButtonTag(group))
            node.assertHeightIsAtLeast(56.dp)
            val bounds = node.getUnclippedBoundsInRoot()
            (bounds.left >= 0.dp && bounds.right <= widthDp.dp) shouldBe true
            bounds.width.value
        }
        widths.distinct().size shouldBe 1 // igual ancho
        compose.assertNoClippedText()
    }

    @Test
    @Config(qualifiers = "es-rES-w360dp-h640dp")
    fun `S059 RF-1 a 360 dp con la letra al 100 por cien los tres botones se ven completos`() = check(360, 1.0f)

    @Test
    @Config(qualifiers = "es-rES-w360dp-h640dp")
    fun `S059 RF-1 a 360 dp con la letra al 130 por cien los tres botones se ven completos`() = check(360, 1.3f)

    @Test
    @Config(qualifiers = "es-rES-w411dp-h891dp")
    fun `S059 RF-1 a 411 dp con la letra al 100 por cien los tres botones se ven completos`() = check(411, 1.0f)

    @Test
    @Config(qualifiers = "es-rES-w411dp-h891dp")
    fun `S059 RF-1 a 411 dp con la letra al 130 por cien los tres botones se ven completos`() = check(411, 1.3f)

    @Test
    @Config(qualifiers = "es-rES-w360dp-h640dp")
    fun `S059 RF-1 cada boton abre el menu de su grupo`() {
        val opened = mutableListOf<OptionGroup>()
        compose.setScaledContent(1.0f) { GroupBar(menu = { group, _ -> opened += group }) }

        BAR_GROUPS.forEach { compose.onNodeWithTag(groupButtonTag(it)).performClick() }

        compose.waitForIdle()
        opened.distinct() shouldBe BAR_GROUPS
    }
}
