package es.topophone.app.ui.receiver

import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.app.R
import es.topophone.app.ui.text
import es.topophone.core.designsystem.theme.TopoPhoneTheme
import es.topophone.core.gnss.nmea.GgaFix
import es.topophone.core.gnss.nmea.SolutionStatus
import es.topophone.core.model.point.TiltMode
import es.topophone.core.model.preferences.EffectiveTheme
import es.topophone.core.model.preferences.ThemeState
import es.topophone.data.receiver.ReceiverTiltSettings
import es.topophone.receiver.session.ConnectionState
import es.topophone.receiver.session.ReceiverSnapshot
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Apartado "Inclinación" del menú del receptor (S016/RF-1, RF-3, RF-4). Interfaz en español (es-ES). */
@RunWith(AndroidJUnit4::class)
class TiltScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val fix = GgaFix("101530.00", 40.4, -3.7, SolutionStatus.FIX, 24, 0.6, 650.0, 51.0, 1.0, "0001")
    private val receiving = ReceiverUiState(snapshot = ReceiverSnapshot(ConnectionState.Receiving("reach-RS3"), gga = fix))

    private fun show(settings: ReceiverTiltSettings, actions: TiltActions = TiltActions()) {
        compose.setContent {
            TopoPhoneTheme(EffectiveTheme.LIGHT) {
                ReceiverPageScreen(
                    ReceiverPage.TILT,
                    ThemeState.DEFAULT,
                    {},
                    receiving,
                    ReceiverActions(),
                    {},
                    tilt = TiltUiState(settings),
                    tiltActions = actions,
                )
            }
        }
    }

    @Test
    fun `S016 RF-1 hay tres modos que se eligen como radio`() {
        show(ReceiverTiltSettings(TiltMode.APP, verified = true, detected = TiltMode.APP))

        listOf(TiltMode.APP to R.string.tilt_mode_app, TiltMode.RECEIVER to R.string.tilt_mode_receiver).forEach { (mode, label) ->
            compose.onNodeWithTag(tiltModeTag(mode)).performScrollTo().assert(hasText(text(label), substring = true))
                .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton))
        }
        compose.onNodeWithTag(tiltModeTag(TiltMode.VERTICAL)).performScrollTo().assert(hasText(text(R.string.tilt_mode_vertical)))
        compose.onNodeWithTag(tiltModeTag(TiltMode.APP)).assertIsSelected()
        compose.onNodeWithTag(tiltModeTag(TiltMode.RECEIVER)).assertIsNotSelected()
        compose.onNodeWithTag(TILT_MODE_STATE_TAG).assert(hasText(text(R.string.tilt_mode_state_verified)))
    }

    @Test
    fun `S016 RF-3 al tocar un modo se elige a mano`() {
        var chosen: TiltMode? = null
        show(ReceiverTiltSettings.NONE, TiltActions(onChoose = { chosen = it }))

        compose.onNodeWithTag(TILT_MODE_STATE_TAG).assert(hasText(text(R.string.tilt_mode_state_none)))
        compose.onNodeWithTag(tiltModeTag(TiltMode.RECEIVER)).performScrollTo().performClick()

        chosen shouldBe TiltMode.RECEIVER
    }

    @Test
    fun `S016 RF-3 elegido a mano dice sin comprobar`() {
        show(ReceiverTiltSettings(TiltMode.RECEIVER, verified = false))

        compose.onNodeWithTag(TILT_MODE_STATE_TAG).assert(hasText(text(R.string.tilt_mode_state_unverified)))
    }

    @Test
    fun `S016 RF-4 jalon vertical con un receptor que compensa avisa`() {
        show(ReceiverTiltSettings(TiltMode.VERTICAL, verified = false, detected = TiltMode.RECEIVER))

        compose.onNodeWithTag(TILT_VERTICAL_WARNING_TAG).performScrollTo().assertIsDisplayed()
            .assert(hasText(text(R.string.tilt_vertical_warning)))
    }

    @Test
    fun `S016 RF-4 sin haber encontrado que compensa no avisa`() {
        show(ReceiverTiltSettings(TiltMode.VERTICAL, verified = false, detected = TiltMode.APP))

        compose.onNodeWithTag(TILT_VERTICAL_WARNING_TAG).assertDoesNotExist()
    }

    @Test
    fun `S016 RF-1 desde el apartado se abre el asistente`() {
        var opened = false
        show(ReceiverTiltSettings.NONE, TiltActions(onOpenWizard = { opened = true }))

        compose.onNodeWithTag(TILT_WIZARD_OPEN_TAG).performScrollTo().assert(hasText(text(R.string.tilt_wizard_open))).performClick()

        opened shouldBe true
    }
}
