package es.topophone.app.ui.collector

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.app.R
import es.topophone.app.ui.text
import es.topophone.core.designsystem.theme.TopoPhoneTheme
import es.topophone.core.model.preferences.EffectiveTheme
import es.topophone.core.survey.TiltGuide
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** La guía de la inclinación (S016/RF-12; RNF-T8, RNF-T14). Interfaz en español (es-ES). */
@RunWith(AndroidJUnit4::class)
class TiltGuideBannerTest {

    @get:Rule
    val compose = createComposeRule()

    private fun show(guide: TiltGuide) = compose.setContent { TopoPhoneTheme(EffectiveTheme.LIGHT) { TiltGuideBanner(guide) } }

    @Test
    fun `S016 RF-12 alinear pide el ocho con su ilustracion`() {
        show(TiltGuide.ALIGNMENT)

        compose.onNodeWithTag(TILT_GUIDE_TAG).assert(hasAnyDescendant(hasText(text(R.string.tilt_guide_alignment))))
        compose.onNodeWithTag(TILT_GUIDE_FIGURE_EIGHT_TAG)
            .assert(hasContentDescription(text(R.string.tilt_guide_figure_eight_description)))
    }

    @Test
    fun `S016 RF-12 el fallo del filtro tambien ensena el ocho`() {
        show(TiltGuide.FILTER_FAULT)

        compose.onNodeWithTag(TILT_GUIDE_TAG).assert(hasAnyDescendant(hasText(text(R.string.tilt_guide_filter_fault))))
        compose.onNodeWithTag(TILT_GUIDE_FIGURE_EIGHT_TAG).assertExists()
    }

    @Test
    fun `S016 RF-12 cada guia tiene su texto y sin ocho no hay ilustracion`() {
        val texts = mapOf(
            TiltGuide.NO_TILT to R.string.tilt_guide_no_tilt,
            TiltGuide.FATAL to R.string.tilt_guide_fatal,
            TiltGuide.SETUP to R.string.tilt_guide_setup,
            TiltGuide.FAST_MOTION to R.string.tilt_guide_fast_motion,
            TiltGuide.BAD_GNSS to R.string.tilt_guide_bad_gnss,
        )
        val state = mutableStateOf(TiltGuide.NO_TILT)
        compose.setContent { TopoPhoneTheme(EffectiveTheme.LIGHT) { TiltGuideBanner(state.value) } }

        texts.forEach { (shown, res) ->
            state.value = shown
            compose.onNodeWithTag(TILT_GUIDE_TAG).assert(hasAnyDescendant(hasText(text(res))))
            compose.onNodeWithTag(TILT_GUIDE_FIGURE_EIGHT_TAG).assertDoesNotExist()
        }
    }
}
