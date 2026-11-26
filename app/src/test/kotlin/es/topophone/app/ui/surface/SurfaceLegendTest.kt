package es.topophone.app.ui.surface

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.core.designsystem.theme.TopoPhoneTheme
import es.topophone.core.model.preferences.EffectiveTheme
import es.topophone.core.model.project.LinearUnit
import es.topophone.core.model.surface.ElevationRamp
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** La leyenda de colores por cota, con sus cifras en la unidad del proyecto (S061/RF-11, RNF-T8, RNF-T9). Interfaz en es-ES. */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "es-rES")
class SurfaceLegendTest {

    @get:Rule
    val compose = createComposeRule()

    private fun show(unit: LinearUnit) {
        compose.setContent { TopoPhoneTheme(EffectiveTheme.LIGHT) { SurfaceLegend(ElevationRamp(600.0, 635.4), unit) } }
    }

    private fun described(text: String) = SemanticsMatcher.expectValue(SemanticsProperties.ContentDescription, listOf(text))

    @Test
    fun `S061 RF-11 en metros la leyenda da la cota minima y la maxima`() {
        show(LinearUnit.METRE)

        compose.onNodeWithTag(SURFACE_LEGEND_TAG).assert(described("Colores por cota, de 600,0 m a 635,4 m"))
    }

    @Test
    fun `S061 RF-11 en pies las cifras pasan a pies`() {
        show(LinearUnit.INTERNATIONAL_FOOT)

        // 600 m = 1.968,5 ft y 635,4 m = 2.084,6 ft.
        compose.onNodeWithTag(SURFACE_LEGEND_TAG).assert(described("Colores por cota, de 1.968,5 ft a 2.084,6 ft"))
    }
}
