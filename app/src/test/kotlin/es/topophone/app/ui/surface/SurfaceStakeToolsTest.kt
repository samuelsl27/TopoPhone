package es.topophone.app.ui.surface

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.app.R
import es.topophone.app.ui.options.GroupMenu
import es.topophone.app.ui.options.OptionGroup
import es.topophone.app.ui.options.ProjectOption
import es.topophone.app.ui.options.optionTag
import es.topophone.core.designsystem.theme.TopoPhoneTheme
import es.topophone.core.model.preferences.EffectiveTheme
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** «Replantear superficie» en «Herramientas»: la activa, la lista para elegir o desactivada sin superficies (S062/RF-3 a RF-5). */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "es-rES")
class SurfaceStakeToolsTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `S062 RF-3 la fila esta en Herramientas y se puede pulsar con superficies`() {
        val selected = mutableListOf<ProjectOption>()
        compose.setContent {
            TopoPhoneTheme(EffectiveTheme.LIGHT) { GroupMenu(OptionGroup.TOOLS, onSelect = { selected += it }, onDismiss = {}) }
        }

        compose.onNodeWithTag(optionTag(ProjectOption.SURFACE_STAKE)).assertIsEnabled().performClick()

        selected shouldBe listOf(ProjectOption.SURFACE_STAKE)
    }

    @Test
    fun `S062 RF-5 sin superficies la fila esta desactivada con el motivo`() {
        compose.setContent {
            TopoPhoneTheme(EffectiveTheme.LIGHT) {
                GroupMenu(
                    OptionGroup.TOOLS,
                    onSelect = {},
                    onDismiss = {},
                    disabled = mapOf(ProjectOption.SURFACE_STAKE to R.string.surface_stake_none),
                )
            }
        }

        compose.onNodeWithTag(optionTag(ProjectOption.SURFACE_STAKE)).assertIsNotEnabled()
        compose.onNodeWithText("Este proyecto no tiene superficies").fetchSemanticsNode()
    }

    @Test
    fun `S062 RF-3 RF-4 con activa se replantea esa y sin activa se abre la lista para elegir`() {
        val calls = mutableListOf<String>()

        SurfaceStakeTools.open("s1", onStake = { calls += "stake:$it" }, onChoose = { calls += "choose" })
        SurfaceStakeTools.open(null, onStake = { calls += "stake:$it" }, onChoose = { calls += "choose" })

        calls shouldBe listOf("stake:s1", "choose")
    }
}
