package es.topophone.app.ui.collector

import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.core.designsystem.theme.TopoPhoneTheme
import es.topophone.core.gnss.nmea.SolutionStatus
import es.topophone.core.model.point.CollectorSettings
import es.topophone.core.model.point.PrecisionControl
import es.topophone.core.model.point.PrecisionFailure
import es.topophone.core.model.preferences.EffectiveTheme
import es.topophone.core.model.project.LinearUnit
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Ajustes del control de precisión y confirmación de fuera de tolerancia (S010/RF-13 a RF-15). Interfaz en es-ES. */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "es-rES")
class CollectorDialogsTest {

    @get:Rule
    val compose = createComposeRule()

    private val ready =
        CollectorUiState(loaded = true, name = "P12", poleHeightText = "1,800", live = LiveQuality(SolutionStatus.FIX, 0.01, 0.015))
    private var chosen: CollectorSettings? = null
    private val calls = mutableListOf<String>()
    private val actions = CollectorActions(
        onSettings = { chosen = it },
        onConfirm = { calls += "confirm" },
        onDiscard = { calls += "discard" },
    )

    private fun show(state: CollectorUiState, unit: LinearUnit = LinearUnit.METRE) {
        compose.setContent { TopoPhoneTheme(EffectiveTheme.LIGHT) { CollectorPanel(state, unit, actions, onClose = {}) } }
    }

    @Test
    fun `S010 RF-13 el panel resume el control de precision y se ajusta en su dialogo`() {
        show(ready)
        compose.onNodeWithTag(PRECISION_SUMMARY_TAG).performScrollTo().assert(hasText("Solo FIX", substring = true))

        compose.onNodeWithTag(PRECISION_ADJUST_TAG).performScrollTo().performClick()
        compose.onNodeWithTag(PRECISION_FIX_ONLY_TAG).performClick()
        compose.onNodeWithTag(PRECISION_HORIZONTAL_SWITCH_TAG).performClick()
        compose.onNodeWithTag(PRECISION_HORIZONTAL_VALUE_TAG).performTextReplacement("0,02")
        compose.onNodeWithTag(PRECISION_SAVE_TAG).performClick()

        val precision = chosen.shouldNotBeNull().precision
        precision.fixOnly shouldBe false
        precision.horizontalLimitM.shouldNotBeNull() shouldBe (0.02 plusOrMinus 1e-12)
        precision.verticalLimitM shouldBe null
    }

    @Test
    fun `S010 RF-13 los limites se escriben en la unidad del proyecto y se guardan en metros`() {
        show(ready, LinearUnit.INTERNATIONAL_FOOT)

        compose.onNodeWithTag(PRECISION_ADJUST_TAG).performScrollTo().performClick()
        compose.onNodeWithTag(PRECISION_VERTICAL_SWITCH_TAG).performClick()
        compose.onNodeWithTag(PRECISION_VERTICAL_VALUE_TAG).performTextReplacement("0,1")
        compose.onNodeWithTag(PRECISION_SAVE_TAG).performClick()

        chosen.shouldNotBeNull().precision.verticalLimitM.shouldNotBeNull() shouldBe (0.03048 plusOrMinus 1e-12)
    }

    @Test
    fun `S010 RF-13 un limite no valido no se puede guardar`() {
        show(ready)

        compose.onNodeWithTag(PRECISION_ADJUST_TAG).performScrollTo().performClick()
        compose.onNodeWithTag(PRECISION_HORIZONTAL_SWITCH_TAG).performClick()
        compose.onNodeWithTag(PRECISION_HORIZONTAL_VALUE_TAG).performTextReplacement("abc")

        compose.onNodeWithTag(PRECISION_SAVE_TAG).assertIsNotEnabled()
    }

    @Test
    fun `S010 RF-13 con limites el resumen los da en la unidad del proyecto`() {
        show(
            ready.copy(
                settings = CollectorSettings(precision = PrecisionControl(fixOnly = true, horizontalLimitM = 0.02, verticalLimitM = 0.03)),
            ),
        )

        compose.onNodeWithTag(PRECISION_SUMMARY_TAG).performScrollTo()
            .assert(hasText("H ≤ 0,020 m", substring = true))
            .assert(hasText("V ≤ 0,030 m", substring = true))
    }

    @Test
    fun `S010 RF-14 y RF-15 una medida fuera de tolerancia dice que falla y se guarda solo si se confirma`() {
        show(ready.copy(pending = PendingPoint(setOf(PrecisionFailure.NOT_FIX, PrecisionFailure.VERTICAL))))

        compose.onNodeWithText("La medida no cumple el control de precisión:").assertIsDisplayed()
        compose.onNodeWithText("No es FIX", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Precisión vertical mayor que el límite", substring = true).assertIsDisplayed()
        compose.onNodeWithTag(OUT_OF_TOLERANCE_SAVE_TAG).performClick()

        calls shouldBe listOf("confirm")
    }

    @Test
    fun `S010 RF-14 una medida fuera de tolerancia se puede descartar`() {
        show(ready.copy(pending = PendingPoint(setOf(PrecisionFailure.NO_ACCURACY))))

        compose.onNodeWithText("Sin precisión del receptor", substring = true).assertIsDisplayed()
        compose.onNodeWithTag(OUT_OF_TOLERANCE_DISCARD_TAG).performClick()

        calls shouldBe listOf("discard")
    }
}
