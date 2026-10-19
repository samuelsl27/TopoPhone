package es.topophone.app.ui.collector

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.app.receiver.VisibleNotices
import es.topophone.app.ui.receiver.BASE_CHANGED_NOTICE_TAG
import es.topophone.app.ui.receiver.LONG_BASELINE_NOTICE_TAG
import es.topophone.app.ui.receiver.noticeCloseTag
import es.topophone.core.designsystem.theme.TopoPhoneTheme
import es.topophone.core.gnss.nmea.SolutionStatus
import es.topophone.core.model.preferences.EffectiveTheme
import es.topophone.core.model.project.LinearUnit
import es.topophone.core.survey.BaseChange
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Avisos de la base en el panel del recolector y medida empezada de nuevo (S015/RF-15, RF-17, RF-18). Interfaz en es-ES. */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "es-rES")
class CollectorBaseNoticesTest {

    @get:Rule
    val compose = createComposeRule()

    private val ready =
        CollectorUiState(loaded = true, name = "P12", poleHeightText = "1,800", live = LiveQuality(SolutionStatus.FIX, 0.01, 0.015))
    private val calls = mutableListOf<String>()
    private val actions = CollectorActions(
        onDismissBaseChange = { calls += "change $it" },
        onDismissLongBaseline = { calls += "long $it" },
    )

    private fun show(state: CollectorUiState) {
        compose.setContent { TopoPhoneTheme(EffectiveTheme.LIGHT) { CollectorPanel(state, LinearUnit.METRE, actions, onClose = {}) } }
    }

    @Test
    fun `S015 RF-15 RF-17 los avisos de la base se ven en el recolector, se cierran y dejan medir`() {
        show(ready.copy(notices = VisibleNotices(longBaselineM = 25_000.0, change = BaseChange(1.85, count = 4), baseSerial = 9)))

        compose.onNodeWithTag(LONG_BASELINE_NOTICE_TAG).assertIsDisplayed()
        compose.onNodeWithTag(BASE_CHANGED_NOTICE_TAG).assertIsDisplayed()
        compose.onNodeWithTag(COLLECTOR_MEASURE_TAG).assertIsEnabled()
        compose.onNodeWithTag(noticeCloseTag(LONG_BASELINE_NOTICE_TAG)).performClick()
        compose.onNodeWithTag(noticeCloseTag(BASE_CHANGED_NOTICE_TAG)).performClick()

        calls shouldBe listOf("long 9", "change 4")
    }

    @Test
    fun `S015 RF-18 se dice que la medida ha empezado de nuevo con la base nueva`() {
        show(ready.copy(measuring = true, restarted = true))

        compose.onNodeWithTag(COLLECTOR_RESTARTED_TAG).assertIsDisplayed()
    }
}
