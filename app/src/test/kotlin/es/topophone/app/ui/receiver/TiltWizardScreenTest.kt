package es.topophone.app.ui.receiver

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.app.R
import es.topophone.app.ui.text
import es.topophone.core.designsystem.theme.TopoPhoneTheme
import es.topophone.core.model.point.TiltMode
import es.topophone.core.model.preferences.EffectiveTheme
import es.topophone.core.survey.WizardInconclusive
import es.topophone.core.survey.WizardMissing
import es.topophone.core.survey.WizardState
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** El asistente del modo de inclinación en pantalla (S016/RF-5 a RF-10; RNF-T8). Interfaz en español (es-ES). */
@RunWith(AndroidJUnit4::class)
class TiltWizardScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val calls = mutableListOf<String>()
    private val actions = TiltActions(
        onStartTest = { calls += "start" },
        onConfirm = { calls += "confirm" },
        onRepeat = { calls += "repeat" },
        onCloseWizard = { calls += "close" },
    )

    private fun show(wizard: WizardState) {
        compose.setContent {
            TopoPhoneTheme(EffectiveTheme.LIGHT) {
                Column(Modifier.verticalScroll(rememberScrollState())) { TiltCard(TiltUiState(wizard = wizard), actions) }
            }
        }
    }

    private fun step() = compose.onNodeWithTag(TILT_WIZARD_STEP_TAG).performScrollTo()

    @Test
    fun `S016 RF-5 dice que falta y no deja empezar`() {
        show(WizardState.Ready(setOf(WizardMissing.FIX, WizardMissing.TILT)))

        val missing = compose.onAllNodesWithTag(TILT_WIZARD_MISSING_TAG)
        missing[0].assert(hasText(text(R.string.tilt_wizard_missing_fix)))
        missing[1].assert(hasText(text(R.string.tilt_wizard_missing_tilt)))
        compose.onNodeWithTag(TILT_WIZARD_START_TAG).performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag(TILT_WIZARD_OPEN_TAG).assertDoesNotExist()
    }

    @Test
    fun `S016 RF-6 sin nada que falte se empieza la prueba`() {
        show(WizardState.Ready(emptySet()))

        compose.onNodeWithTag(TILT_WIZARD_START_TAG).performScrollTo().assertIsEnabled().performClick()

        calls shouldBe listOf("start")
    }

    @Test
    fun `S016 RF-6 los pasos de la prueba se leen con TalkBack`() {
        show(WizardState.Reference(0.4))

        step().assert(hasText(text(R.string.tilt_wizard_reference)))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite))
    }

    @Test
    fun `S016 RF-6 pide inclinar y, si no basta, inclinar mas`() {
        show(WizardState.Tilt(Math.toRadians(20.0), 0.1, tiltMore = true))

        step().assert(hasText(text(R.string.tilt_wizard_tilt_more)))
        compose.onNode(hasText(text(R.string.tilt_wizard_current_tilt, "20°"))).assertExists()
    }

    @Test
    fun `S016 RF-7 propone Compensada por el receptor y se confirma o se repite`() {
        show(WizardState.Proposed(TiltMode.RECEIVER, movedM = 0.003, expectedM = 0.512))

        step().assert(hasText(text(R.string.tilt_wizard_proposed_receiver)))
        compose.onNode(hasText(text(R.string.tilt_wizard_movement, "0,003 m", "0,512 m"))).assertExists()
        compose.onNodeWithTag(TILT_WIZARD_CONFIRM_TAG).performScrollTo().performClick()
        compose.onNodeWithTag(TILT_WIZARD_REPEAT_TAG).performScrollTo().performClick()

        calls shouldBe listOf("confirm", "repeat")
    }

    @Test
    fun `S016 RF-8 propone Calculada por la app`() {
        show(WizardState.Proposed(TiltMode.APP, movedM = 0.505, expectedM = 0.512))

        step().assert(hasText(text(R.string.tilt_wizard_proposed_app)))
    }

    @Test
    fun `S016 RF-9 no concluyente dice por que y deja repetir y cerrar`() {
        show(WizardState.Inconclusive(WizardInconclusive.TIMEOUT))

        step().assert(hasText(text(R.string.tilt_wizard_inconclusive, text(R.string.tilt_wizard_reason_timeout))))
        compose.onNodeWithTag(TILT_WIZARD_REPEAT_TAG).performScrollTo().performClick()
        compose.onNodeWithTag(TILT_WIZARD_CLOSE_TAG).performScrollTo().performClick()

        calls shouldBe listOf("repeat", "close")
    }
}
