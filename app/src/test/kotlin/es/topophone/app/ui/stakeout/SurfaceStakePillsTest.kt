package es.topophone.app.ui.stakeout

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.core.designsystem.theme.TopoPhoneTheme
import es.topophone.core.model.point.StakeoutSettings
import es.topophone.core.model.preferences.EffectiveTheme
import es.topophone.core.model.project.LinearUnit
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Las pastillas del replanteo de la superficie: tolerancia vertical y desplazamiento, bloqueadas midiendo (S062/RF-10, RF-11, RF-23). */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "es-rES")
class SurfaceStakePillsTest {

    @get:Rule
    val compose = createComposeRule()

    private val offsets = mutableListOf<String>()

    private fun show(enabled: Boolean, unit: LinearUnit = LinearUnit.METRE) {
        compose.setContent {
            TopoPhoneTheme(EffectiveTheme.LIGHT) {
                SurfaceStakePills(
                    unit = unit,
                    settings = StakeoutSettings(toleranceVM = 0.03),
                    offsetM = 0.1,
                    enabled = enabled,
                    onToleranceV = { true },
                    onOffset = {
                        offsets += it
                        true
                    },
                )
            }
        }
    }

    @Test
    fun `S062 RF-10 las pastillas dan la tolerancia vertical y el desplazamiento en la unidad del proyecto`() {
        show(enabled = true)

        compose.onNodeWithTag(STAKEOUT_PILL_TOLERANCE_V_TAG, useUnmergedTree = false).assertTextContains("0,030 m", substring = true)
        compose.onNodeWithTag(SURFACE_STAKE_PILL_OFFSET_TAG).assertTextContains("+0,100 m", substring = true)
        compose.onNodeWithTag(STAKEOUT_PILL_TOLERANCE_TAG).assertDoesNotExist()
        compose.onNodeWithTag(STAKEOUT_PILL_MODE_TAG).assertDoesNotExist()
        compose.onNodeWithTag(STAKEOUT_PILL_ELEVATION_TAG).assertDoesNotExist()
    }

    @Test
    fun `S062 RF-11 cambiar el desplazamiento en la pastilla lo escribe`() {
        show(enabled = true)

        compose.onNodeWithTag(SURFACE_STAKE_PILL_OFFSET_TAG).assertIsEnabled().performClick()
        compose.onNodeWithText("Desplazamiento (m)").performTextClearance()
        compose.onNodeWithText("Desplazamiento (m)").performTextInput("-0,25")
        compose.onNodeWithText("Aplicar").performClick()

        offsets shouldBe listOf("-0,25")
    }

    @Test
    fun `S062 RF-23 midiendo no se cambian la tolerancia ni el desplazamiento`() {
        show(enabled = false)

        compose.onNodeWithTag(SURFACE_STAKE_PILL_OFFSET_TAG).assertIsNotEnabled()
        compose.onNodeWithTag(STAKEOUT_PILL_TOLERANCE_V_TAG).assertIsNotEnabled()
    }
}
