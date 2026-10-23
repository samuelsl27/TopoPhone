package es.topophone.app.ui.collector

import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.app.R
import es.topophone.app.ui.text
import es.topophone.core.designsystem.theme.TopoPhoneTheme
import es.topophone.core.gnss.nmea.SolutionStatus
import es.topophone.core.model.point.CollectorSettings
import es.topophone.core.model.point.PrecisionControl
import es.topophone.core.model.point.PrecisionFailure
import es.topophone.core.model.point.TiltMode
import es.topophone.core.model.preferences.EffectiveTheme
import es.topophone.core.model.project.LinearUnit
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** La inclinación máxima en el control de precisión del recolector (S016/RF-20). Interfaz en es-ES. */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "es-rES")
class CollectorTiltDialogTest {

    @get:Rule
    val compose = createComposeRule()

    private val ready =
        CollectorUiState(loaded = true, name = "P12", poleHeightText = "1,800", live = LiveQuality(SolutionStatus.FIX, 0.01, 0.015))
    private var chosen: CollectorSettings? = null

    private fun show(state: CollectorUiState) {
        compose.setContent {
            TopoPhoneTheme(EffectiveTheme.LIGHT) {
                CollectorPanel(state, LinearUnit.METRE, CollectorActions(onSettings = { chosen = it }), onClose = {})
            }
        }
    }

    @Test
    fun `S016 RF-20 con la inclinacion el resumen dice la inclinacion maxima`() {
        show(ready.copy(tiltMode = TiltMode.APP))

        compose.onNodeWithTag(PRECISION_SUMMARY_TAG).performScrollTo().assert(hasText("inclinación ≤ 60°", substring = true))
    }

    @Test
    fun `S016 RF-20 la inclinacion maxima se ajusta de 1 a 60 grados`() {
        show(ready.copy(tiltMode = TiltMode.APP))

        compose.onNodeWithTag(PRECISION_ADJUST_TAG).performScrollTo().performClick()
        compose.onNodeWithTag(PRECISION_TILT_VALUE_TAG).performTextReplacement("61")
        compose.onNodeWithTag(PRECISION_SAVE_TAG).assertIsNotEnabled()
        compose.onNodeWithTag(PRECISION_TILT_VALUE_TAG).performTextReplacement("25")
        compose.onNodeWithTag(PRECISION_SAVE_TAG).performClick()

        chosen.shouldNotBeNull().precision.maxTiltRad.shouldNotBeNull() shouldBe (Math.toRadians(25.0) plusOrMinus 1e-12)
    }

    @Test
    fun `S016 RF-20 la inclinacion maxima se puede desactivar`() {
        show(ready.copy(tiltMode = TiltMode.RECEIVER))

        compose.onNodeWithTag(PRECISION_ADJUST_TAG).performScrollTo().performClick()
        compose.onNodeWithTag(PRECISION_TILT_SWITCH_TAG).performClick()
        compose.onNodeWithTag(PRECISION_SAVE_TAG).performClick()

        chosen.shouldNotBeNull().precision.maxTiltRad shouldBe null
    }

    @Test
    fun `S016 RF-20 en jalon vertical no se ve ni se toca`() {
        val custom = CollectorSettings(precision = PrecisionControl(maxTiltRad = Math.toRadians(30.0)))
        show(ready.copy(settings = custom, tiltMode = TiltMode.VERTICAL))

        compose.onNodeWithTag(PRECISION_ADJUST_TAG).performScrollTo().performClick()
        compose.onNodeWithTag(PRECISION_TILT_SWITCH_TAG).assertDoesNotExist()
        compose.onNodeWithTag(PRECISION_SAVE_TAG).performClick()

        chosen.shouldNotBeNull().precision.maxTiltRad shouldBe Math.toRadians(30.0)
    }

    @Test
    fun `S016 RF-20 fuera de tolerancia dice inclinacion maxima superada`() {
        show(ready.copy(pending = PendingPoint(setOf(PrecisionFailure.TILT))))

        compose.onNode(hasText(text(R.string.out_of_tolerance_tilt), substring = true)).assertExists()
    }

    @Test
    fun `S016 RF-19 durante la medida dice que no cuentan las epocas`() {
        show(ready.copy(measuring = true, tiltLost = es.topophone.core.survey.TiltGuide.FAST_MOTION))

        compose.onNodeWithTag(COLLECTOR_TILT_LOST_TAG).assert(hasText(text(R.string.collector_tilt_lost)))
    }

    @Test
    fun `S016 RF-28 la medida cancelada por el modo lo dice`() {
        show(ready.copy(error = CollectorError.TILT_MODE_CHANGED))

        compose.onNodeWithTag(COLLECTOR_TILT_CHANGED_TAG).assert(hasText(text(R.string.collector_tilt_mode_changed)))
    }
}
