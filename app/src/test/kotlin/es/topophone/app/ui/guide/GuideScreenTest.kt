package es.topophone.app.ui.guide

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.core.designsystem.theme.TopoPhoneTheme
import es.topophone.core.model.guide.CheckStatus
import es.topophone.core.model.guide.ConfigListId
import es.topophone.core.model.guide.ConfigLists
import es.topophone.core.model.preferences.EffectiveTheme
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** La Guía: las listas con sus contadores, los pasos para marcar y el reinicio (S043/RF-1 a RF-4). Interfaz en es-ES. */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "es-rES")
class GuideScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val calls = mutableListOf<String>()
    private val actions = GuideActions(
        onSetDone = { list, step, done -> calls += "done:${list.name}:$step:$done" },
        onReset = { calls += "reset:${it.name}" },
    )

    private val real = GuideUiState(applies = true, receiverKey = "AA:BB")

    private fun show(state: GuideUiState) {
        compose.setContent {
            TopoPhoneTheme(EffectiveTheme.LIGHT) {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) { GuideCard(state, actions) }
            }
        }
    }

    private fun open(id: ConfigListId) = compose.onNodeWithTag(guideListTag(id)).performScrollTo().performClick()

    @Test
    fun `S043 RF-1 la Guia ofrece las cuatro listas con su nombre`() {
        show(real)

        ConfigListId.entries.forEach { compose.onNodeWithTag(guideListTag(it)).performScrollTo().assertIsDisplayed() }
    }

    @Test
    fun `S043 RF-1 con una fuente que no es un receptor se dice que no aplica y no hay listas`() {
        show(GuideUiState(applies = false))

        compose.onNodeWithTag(GUIDE_NOT_APPLICABLE_TAG).assertIsDisplayed()
        compose.onNodeWithTag(guideListTag(ConfigListId.NTRIP_ROVER)).assertDoesNotExist()
    }

    @Test
    fun `S043 RF-4 cada lista dice cuantos pasos estan hechos y cuantos comprobados`() {
        val list = ConfigLists.ntripRover
        show(
            real.copy(
                done = mapOf(ConfigListId.NTRIP_ROVER to setOf("ntrip_output", "ntrip_rate")),
                stepStatus = mapOf("ntrip_rate" to CheckStatus.OK),
            ),
        )

        val checkable = list.steps.count { it.checkable }
        compose.onNodeWithTag(guideCountsTag(ConfigListId.NTRIP_ROVER), useUnmergedTree = true).performScrollTo()
            .assertTextEquals("2 de ${list.steps.size} pasos hechos · 1 de $checkable comprobados")
    }

    @Test
    fun `S043 RF-2 al abrir una lista salen sus pasos y la nota del firmware`() {
        show(real)

        open(ConfigListId.WIFI_ROVER)

        compose.onNodeWithTag(GUIDE_FIRMWARE_NOTE_TAG, useUnmergedTree = true).performScrollTo().assertIsDisplayed()
        ConfigLists.wifiRover.steps.forEach { compose.onNodeWithTag(guideStepTag(it.id)).performScrollTo().assertIsDisplayed() }
    }

    @Test
    fun `S043 RF-3 marcar un paso lo pide con su lista y desmarcarlo tambien`() {
        show(real.copy(done = mapOf(ConfigListId.NTRIP_ROVER to setOf("ntrip_fix"))))
        open(ConfigListId.NTRIP_ROVER)

        compose.onNodeWithTag(guideStepTag("ntrip_rate")).performScrollTo().assertIsOff().performClick()
        compose.onNodeWithTag(guideStepTag("ntrip_fix")).performScrollTo().assertIsOn().performClick()

        calls shouldBe listOf("done:NTRIP_ROVER:ntrip_rate:true", "done:NTRIP_ROVER:ntrip_fix:false")
    }

    @Test
    fun `S043 RF-3 reiniciar pide confirmacion y solo entonces lo hace`() {
        show(real)
        open(ConfigListId.BASE)

        compose.onNodeWithTag(GUIDE_RESET_TAG).performScrollTo().performClick()
        calls shouldBe emptyList()
        compose.onNodeWithTag(GUIDE_RESET_CONFIRM_TAG).assertIsDisplayed().performClick()

        calls shouldBe listOf("reset:BASE")
    }

    @Test
    fun `S043 RF-1 volver lleva otra vez a las listas`() {
        show(real)
        open(ConfigListId.TILT_ROVER)

        compose.onNodeWithTag(GUIDE_BACK_TAG).performScrollTo().performClick()

        compose.onNodeWithTag(guideListTag(ConfigListId.TILT_ROVER)).performScrollTo().assertIsDisplayed()
    }
}
