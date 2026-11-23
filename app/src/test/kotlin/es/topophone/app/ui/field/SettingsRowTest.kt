package es.topophone.app.ui.field

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.core.designsystem.theme.TopoPhoneTheme
import es.topophone.core.model.preferences.EffectiveTheme
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Las pastillas de ajustes y su fila (S060/RF-9, RF-27, RNF-T8). */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "es-rES")
class SettingsRowTest {

    @get:Rule
    val compose = createComposeRule()

    private val tags = listOf("p_pole", "p_time", "p_mode", "p_tilt")
    private val clicks = mutableListOf<String>()

    private fun show(widthDp: Int, fontScale: Float, enabled: Boolean = true) {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                TopoPhoneTheme(EffectiveTheme.LIGHT) {
                    Box(Modifier.width(widthDp.dp)) {
                        SettingsRow(
                            listOf(
                                SettingPillData("Jalón", "1,800 m", "p_pole", enabled) { clicks += "pole" },
                                SettingPillData("Tiempo", "Instantáneo", "p_time", enabled) { clicks += "time" },
                                SettingPillData("Modo", "Promediado", "p_mode", enabled) { clicks += "mode" },
                                SettingPillData("Inclinación", "Calculada por la app", "p_tilt", enabled) { clicks += "tilt" },
                            ),
                        )
                    }
                }
            }
        }
    }

    private fun assertFits(widthDp: Int) {
        tags.forEach { tag ->
            val node = compose.onNodeWithTag(tag, useUnmergedTree = true)
            node.assertHeightIsAtLeast(56.dp)
            val bounds = node.getUnclippedBoundsInRoot()
            (bounds.left >= 0.dp && bounds.right <= widthDp.dp) shouldBe true
        }
        // Ningún texto se corta: cada línea de texto queda dentro de su pastilla, que crece con ella.
        val pills = tags.associateWith { compose.onNodeWithTag(it, useUnmergedTree = true).getUnclippedBoundsInRoot() }
        val texts = compose.onAllNodes(hasText("", substring = true), useUnmergedTree = true).fetchSemanticsNodes()
        (texts.size >= 8) shouldBe true
        texts.forEach { text ->
            val box = text.boundsInRoot
            pills.values.any { p ->
                val dens = box.left >= p.left.value - 0.5f &&
                    box.right <= p.right.value + 0.5f &&
                    box.top >= p.top.value - 0.5f &&
                    box.bottom <= p.bottom.value + 0.5f
                dens
            } shouldBe true
        }
    }

    @Test
    @Config(qualifiers = "es-rES-w360dp-h640dp")
    fun `S060 RF-9 a 360 dp con letra al 100 por cien las cuatro pastillas caben en una fila`() {
        show(360, 1f)

        assertFits(360)
        val tops = tags.map { compose.onNodeWithTag(it, useUnmergedTree = true).getUnclippedBoundsInRoot().top }
        tops.distinct().size shouldBe 1
    }

    @Test
    @Config(qualifiers = "es-rES-w411dp-h640dp")
    fun `S060 RF-9 a 411 dp con letra al 100 por cien las cuatro pastillas caben en una fila`() {
        show(411, 1f)

        assertFits(411)
        val tops = tags.map { compose.onNodeWithTag(it, useUnmergedTree = true).getUnclippedBoundsInRoot().top }
        tops.distinct().size shouldBe 1
    }

    @Test
    @Config(qualifiers = "es-rES-w360dp-h640dp")
    fun `S060 RNF con letra al 130 por cien a 360 dp la fila ocupa una o dos filas sin cortar texto`() {
        show(360, 1.3f)

        assertFits(360)
        val tops = tags.map { compose.onNodeWithTag(it, useUnmergedTree = true).getUnclippedBoundsInRoot().top }
        // Robolectric mide el texto más pequeño que un móvil: el salto real a dos filas se ve en la demo del emulador (T24).
        (tops.distinct().size in 1..2) shouldBe true
    }

    @Test
    @Config(qualifiers = "es-rES-w411dp-h640dp")
    fun `S060 RNF con letra al 130 por cien a 411 dp tampoco se corta ningun texto`() {
        show(411, 1.3f)

        assertFits(411)
    }

    @Test
    @Config(qualifiers = "es-rES-w411dp-h640dp")
    fun `S060 RNF-T8 cada pastilla es un boton con su nombre y su valor para TalkBack y responde al toque`() {
        show(411, 1f)

        val node = compose.onNodeWithTag("p_pole", useUnmergedTree = false).fetchSemanticsNode()
        node.config.getOrNull(SemanticsProperties.Role) shouldBe Role.Button
        node.config.getOrNull(SemanticsProperties.ContentDescription)?.single() shouldBe "Jalón, 1,800 m"
        compose.onNodeWithTag("p_pole").performClick()
        clicks shouldBe listOf("pole")
    }

    @Test
    @Config(qualifiers = "es-rES-w411dp-h640dp")
    fun `S060 RF-15 una pastilla desactivada no se puede tocar`() {
        show(411, 1f, enabled = false)

        compose.onNodeWithTag("p_time").assertIsNotEnabled()
    }

    @Test
    @Config(qualifiers = "es-rES-w411dp-h640dp")
    fun `S060 RF-9 una pastilla activa esta habilitada`() {
        show(411, 1f)

        compose.onNodeWithTag("p_time").assertIsEnabled()
    }
}
