package es.topophone.app.ui.guide

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.core.designsystem.theme.TopoPhoneTheme
import es.topophone.core.model.guide.CheckCause
import es.topophone.core.model.guide.CheckId
import es.topophone.core.model.guide.CheckStatus
import es.topophone.core.model.guide.ConfigListId
import es.topophone.core.model.guide.ReceiverCheck
import es.topophone.core.model.preferences.EffectiveTheme
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** «Comprobar» en la Guía: el resultado de cada comprobación con texto y símbolo, la causa y el paso que lo resuelve (S043/RF-5, RF-9, RF-10). */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "es-rES")
class GuideChecksScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val calls = mutableListOf<String>()
    private val actions = GuideActions(onCheck = { calls += "check:${it?.name}" })

    private fun show(state: GuideUiState) {
        compose.setContent {
            TopoPhoneTheme(EffectiveTheme.LIGHT) {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) { GuideCard(state, actions) }
            }
        }
    }

    private val real = GuideUiState(applies = true, receiverKey = "AA:BB", canCheck = true)

    private fun openNtrip() {
        compose.onNodeWithTag(guideListTag(ConfigListId.NTRIP_ROVER)).performScrollTo().performClick()
    }

    private val results = listOf(
        ReceiverCheck(CheckId.GGA_RATE, CheckStatus.OK, measured = "5,0 Hz"),
        ReceiverCheck(CheckId.SENTENCES, CheckStatus.WARNING, "GGA, GST", listOf(CheckCause.MISSING_GSV), "ntrip_messages"),
        ReceiverCheck(CheckId.SOLUTION, CheckStatus.FAIL, "FLOAT", listOf(CheckCause.SOLUTION_NOT_FIX), "ntrip_fix"),
        ReceiverCheck(CheckId.UTC_TIME, CheckStatus.NO_DATA, null, listOf(CheckCause.NO_DATA), "ntrip_time"),
    )

    @Test
    fun `S043 RF-5 pulsar Comprobar en una lista pide las comprobaciones de esa lista`() {
        show(real)
        openNtrip()

        compose.onNodeWithTag(GUIDE_CHECK_TAG).performScrollTo().assertIsEnabled().performClick()

        calls shouldBe listOf("check:NTRIP_ROVER")
    }

    @Test
    fun `S043 RF-5 antes de comprobar se dice que aun no se ha comprobado nada`() {
        show(real)
        openNtrip()

        compose.onNodeWithTag(GUIDE_CHECK_NONE_TAG).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `S043 RF-5 mientras comprueba el boton espera y se avisa`() {
        show(real.copy(checking = true))
        openNtrip()

        compose.onNodeWithTag(GUIDE_CHECK_TAG).performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag(GUIDE_CHECKING_TAG).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `S043 RF-5 cada estado sale con su simbolo y su texto, no solo con el color`() {
        show(real.copy(results = results, checkedList = ConfigListId.NTRIP_ROVER))
        openNtrip()

        compose.onNodeWithTag(guideCheckStatusTag(CheckId.GGA_RATE)).performScrollTo().assertTextContains("✔ Correcto")
        compose.onNodeWithTag(guideCheckStatusTag(CheckId.SENTENCES)).performScrollTo().assertTextContains("⚠ Aviso")
        compose.onNodeWithTag(guideCheckStatusTag(CheckId.SOLUTION)).performScrollTo().assertTextContains("✖ Fallo")
        compose.onNodeWithTag(guideCheckStatusTag(CheckId.UTC_TIME)).performScrollTo().assertTextContains("… Sin datos")
    }

    @Test
    fun `S043 RF-9 un fallo muestra su causa probable y el paso de la lista que lo resuelve`() {
        show(real.copy(results = results, checkedList = ConfigListId.NTRIP_ROVER))
        openNtrip()

        compose.onNodeWithTag(guideCheckCauseTag(CheckId.SOLUTION)).performScrollTo()
            .assertTextContains("La solución no es FIX", substring = true)
        compose.onNodeWithTag(guideCheckStepTag(CheckId.SOLUTION)).performScrollTo()
            .assertTextContains("Solución FIX", substring = true)
        compose.onNodeWithTag(guideCheckStepTag(CheckId.SENTENCES)).performScrollTo()
            .assertTextContains("Frases NMEA", substring = true)
    }

    @Test
    fun `S043 RF-10 Sin datos remite al diagnostico de la conexion`() {
        show(real.copy(results = results, checkedList = ConfigListId.NTRIP_ROVER))
        openNtrip()

        compose.onNodeWithTag(guideCheckCauseTag(CheckId.UTC_TIME)).performScrollTo()
            .assertTextContains("diagnóstico de la conexión", substring = true)
    }

    @Test
    fun `S043 RF-5 lo medido se ve junto a la comprobacion y lo correcto no pide ningun paso`() {
        show(real.copy(results = results, checkedList = ConfigListId.NTRIP_ROVER))
        openNtrip()

        compose.onNodeWithTag(guideCheckTag(CheckId.GGA_RATE)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag(guideCheckStepTag(CheckId.GGA_RATE)).assertDoesNotExist()
        compose.onNodeWithTag(guideCheckCauseTag(CheckId.GGA_RATE)).assertDoesNotExist()
    }

    @Test
    fun `S043 RF-23 con el virtual no hay listas pero se puede comprobar y se ven todas las comprobaciones`() {
        show(GuideUiState(applies = false, receiverKey = "virtual", canCheck = true, results = results))

        compose.onNodeWithTag(GUIDE_NOT_APPLICABLE_TAG).assertIsDisplayed()
        compose.onNodeWithTag(GUIDE_CHECK_ONLY_TAG).assertIsDisplayed()
        compose.onNodeWithTag(GUIDE_CHECK_TAG).performScrollTo().performClick()
        compose.onNodeWithTag(guideCheckStatusTag(CheckId.SOLUTION)).performScrollTo().assertTextContains("✖ Fallo")

        calls shouldBe listOf("check:null")
    }

    @Test
    fun `S043 RF-22 con el movil no se ofrece comprobar`() {
        show(GuideUiState(applies = false, receiverKey = "phone", canCheck = false))

        compose.onNodeWithTag(GUIDE_NOT_APPLICABLE_TAG).assertIsDisplayed()
        compose.onNodeWithTag(GUIDE_CHECK_TAG).assertDoesNotExist()
    }
}
