package es.topophone.app.ui.collector

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.app.ui.stakeout.Guidance
import es.topophone.app.ui.stakeout.GuidanceOff
import es.topophone.app.ui.stakeout.GuidanceState
import es.topophone.core.designsystem.theme.TopoPhoneTheme
import es.topophone.core.model.point.AdmittedSolutions
import es.topophone.core.model.point.CollectorSettings
import es.topophone.core.model.point.PrecisionControl
import es.topophone.core.model.point.SolutionType
import es.topophone.core.model.point.StakeoutSettings
import es.topophone.core.model.preferences.EffectiveTheme
import es.topophone.core.model.project.LinearUnit
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** El indicador de que no solo se admite FIX, en el recolector y en el replanteo (S047/RF-14, RNF-T8). */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "es-rES")
class AdmittedBadgeTest {

    @get:Rule
    val compose = createComposeRule()

    private val ready = CollectorUiState(loaded = true)

    private fun showCollector(admitted: AdmittedSolutions) {
        val state = ready.copy(settings = CollectorSettings(precision = PrecisionControl(admitted = admitted)))
        compose.setContent {
            TopoPhoneTheme(EffectiveTheme.LIGHT) { CollectorPanel(state, LinearUnit.METRE, CollectorActions(), onClose = {}) }
        }
    }

    private fun showGuidance(admitted: AdmittedSolutions) {
        compose.setContent {
            TopoPhoneTheme(EffectiveTheme.LIGHT) {
                Guidance(null, StakeoutSettings(), GuidanceState.Off(GuidanceOff.NO_POSITION), admitted = admitted)
            }
        }
    }

    @Test
    fun `S047 RF-14 con solo FIX no hay indicador ni en el recolector ni en el replanteo`() {
        showCollector(AdmittedSolutions.FIX_ONLY)
        compose.onAllNodesWithTag(ADMITTED_BADGE_TAG).fetchSemanticsNodes().size shouldBe 0
    }

    @Test
    fun `S047 RF-14 en el replanteo con solo FIX tampoco hay indicador`() {
        showGuidance(AdmittedSolutions.FIX_ONLY)

        compose.onAllNodesWithTag(ADMITTED_BADGE_TAG).fetchSemanticsNodes().size shouldBe 0
    }

    @Test
    fun `S047 RF-14 en el recolector con FLOAT admitido el indicador dice que no solo se admite FIX con texto`() {
        showCollector(AdmittedSolutions.of(setOf(SolutionType.FIX, SolutionType.FLOAT)))

        compose.onNodeWithTag(ADMITTED_BADGE_TAG).assertIsDisplayed()
            .assertTextContains("No solo FIX", substring = true)
            .assertTextContains("FIX, FLOAT", substring = true)
    }

    @Test
    fun `S047 RF-14 en el replanteo con SINGLE admitido el indicador esta junto a la guia`() {
        showGuidance(AdmittedSolutions.ALL)

        compose.onNodeWithTag(ADMITTED_BADGE_TAG).assertIsDisplayed().assertTextContains("No solo FIX", substring = true)
    }
}
