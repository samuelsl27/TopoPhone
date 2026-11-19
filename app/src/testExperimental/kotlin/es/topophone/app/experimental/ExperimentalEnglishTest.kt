package es.topophone.app.experimental

import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.app.R
import es.topophone.app.ui.text
import es.topophone.core.model.preferences.ThemeState
import es.topophone.receiver.experimental.AntennaHeightProposal
import es.topophone.receiver.experimental.DiscoveryPhase
import es.topophone.receiver.experimental.ReachEndpoint
import es.topophone.receiver.experimental.ReachLiveState
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** S044 (RNF-T7): la interfaz del módulo también está en inglés, con las mismas claves que en español. */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "en")
class ExperimentalEnglishTest {

    @get:Rule
    val compose = createComposeRule()

    private val ready = ReachSource.Ready("wifi:a", ReachEndpoint.parse("192.168.42.1")!!)

    @Test
    fun `S044 RNF-T7 los textos clave salen en ingles`() {
        text(R.string.experimental_settings_title) shouldBe "Experimental module"
        text(R.string.experimental_incompatible) shouldBe "Receiver not compatible"
        text(R.string.experimental_live_lost) shouldBe "No receiver status"
        text(R.string.experimental_logs_start) shouldBe "Start log"
        text(R.string.experimental_logs_stop) shouldBe "Stop log"
        text(R.string.experimental_discovery_title) shouldBe "Search for receivers"
        text(R.string.experimental_antenna_title) shouldBe "Send antenna height"
        text(R.string.experimental_battery, 87) shouldBe "Battery: 87 %"
    }

    @Test
    fun `S044 RNF-T7 el aviso legal sale en ingles y no nombra al fabricante`() {
        val notice = text(R.string.experimental_notice_body)

        notice.startsWith("This module uses receiver features") shouldBe true
        notice.lowercase().contains("emlid") shouldBe false
    }

    @Test
    fun `S044 RNF-T7 las pantallas pintan los textos en ingles`() {
        compose.setContent {
            AntennaHeightScreen(
                AntennaUiState(
                    source = ready,
                    canSend = true,
                    proposal = AntennaHeightProposal(0.0, warnDoubleCount = true),
                    text = "0",
                    valueM = 0.0,
                ),
                AntennaActions(),
                ThemeState(),
                {},
                {},
            )
        }

        compose.onNodeWithTag(ANTENNA_DOUBLE_COUNT_TAG).assertTextContains("already subtracts", substring = true)
    }

    @Test
    fun `S044 RNF-T7 el estado en directo y la busqueda salen en ingles`() {
        compose.setContent {
            androidx.compose.foundation.layout.Column {
                LiveStatusLines(ReachLiveState())
                DiscoveryScreen(DiscoveryUiState(phase = DiscoveryPhase.Empty), DiscoveryActions(), ThemeState(), {}, {})
            }
        }

        compose.onNodeWithTag(LIVE_LOST_TAG).assertTextContains("No receiver status")
        compose.onNodeWithTag(DISCOVERY_EMPTY_TAG).assertTextContains("No receiver was found.")
    }
}
