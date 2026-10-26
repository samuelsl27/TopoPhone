package es.topophone.app.ui.collector

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.core.designsystem.theme.TopoPhoneTheme
import es.topophone.core.gnss.nmea.SolutionStatus
import es.topophone.core.model.preferences.EffectiveTheme
import es.topophone.core.model.project.LinearUnit
import es.topophone.core.model.surface.SurfaceHeight
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** La cota de la superficie bajo la punta en el panel del recolector, sin replantear (S042/RF-33). Interfaz en es-ES. */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "es-rES")
class CollectorSurfacePanelTest {

    @get:Rule
    val compose = createComposeRule()

    private val ready = CollectorUiState(
        loaded = true,
        name = "P12",
        poleHeightText = "1,800",
        live = LiveQuality(SolutionStatus.FIX, 0.010, 0.015),
    )

    private fun show(reading: SurfaceReading?, unit: LinearUnit = LinearUnit.METRE) {
        compose.setContent {
            TopoPhoneTheme(EffectiveTheme.LIGHT) { CollectorPanel(ready, unit, CollectorActions(), onClose = {}, surface = reading) }
        }
    }

    @Test
    fun `S042 RF-33 sin superficie activa el recolector no muestra nada de ella`() {
        show(null)

        compose.onNodeWithTag(COLLECTOR_SURFACE_TAG).assertDoesNotExist()
        compose.onNodeWithTag(COLLECTOR_SURFACE_LEVEL_TAG).assertDoesNotExist()
    }

    @Test
    fun `S042 RF-33 con la punta por encima de la superficie dice desmonte con la diferencia`() {
        show(SurfaceReading(SurfaceHeight.Value(601.25), offsetM = 0.3, tipHeightM = 601.67))

        compose.onNodeWithTag(COLLECTOR_SURFACE_TAG).assertIsDisplayed().assertTextContains("601,550 m", substring = true)
        compose.onNodeWithTag(COLLECTOR_SURFACE_TAG).assertTextContains("desplazamiento 0,300 m", substring = true)
        compose.onNodeWithTag(COLLECTOR_SURFACE_LEVEL_TAG).assertIsDisplayed().assertTextEquals("Desmonte 0,120 m")
    }

    @Test
    fun `S042 RF-33 con la punta por debajo de la superficie dice terraplen con la diferencia`() {
        show(SurfaceReading(SurfaceHeight.Value(601.25), offsetM = 0.0, tipHeightM = 600.75))

        compose.onNodeWithTag(COLLECTOR_SURFACE_LEVEL_TAG).assertIsDisplayed().assertTextEquals("Terraplén 0,500 m")
    }

    @Test
    fun `S042 RF-33 justo en la cota dice en cota`() {
        show(SurfaceReading(SurfaceHeight.Value(601.25), offsetM = 0.0, tipHeightM = 601.25))

        compose.onNodeWithTag(COLLECTOR_SURFACE_LEVEL_TAG).assertIsDisplayed().assertTextEquals("En cota")
    }

    @Test
    fun `S042 RF-33 las cifras van en la unidad del proyecto`() {
        show(SurfaceReading(SurfaceHeight.Value(100.0), offsetM = 0.3048, tipHeightM = 100.6096), LinearUnit.INTERNATIONAL_FOOT)

        compose.onNodeWithTag(COLLECTOR_SURFACE_TAG).assertTextContains("desplazamiento 1,000 ft", substring = true)
        compose.onNodeWithTag(COLLECTOR_SURFACE_LEVEL_TAG).assertTextEquals("Desmonte 1,000 ft")
    }

    @Test
    fun `S042 RF-33 fuera de la superficie lo dice en texto y no da diferencia`() {
        show(SurfaceReading(SurfaceHeight.Outside, offsetM = 0.3, tipHeightM = 601.67))

        compose.onNodeWithTag(COLLECTOR_SURFACE_TAG).assertIsDisplayed().assertTextEquals("Fuera de la superficie")
        compose.onNodeWithTag(COLLECTOR_SURFACE_LEVEL_TAG).assertDoesNotExist()
    }

    @Test
    fun `S042 RF-33 mientras carga dice que se calcula y no que esta fuera`() {
        show(SurfaceReading(SurfaceHeight.Loading, offsetM = 0.0, tipHeightM = 601.67))

        compose.onNodeWithTag(COLLECTOR_SURFACE_TAG).assertIsDisplayed().assertTextContains("Calculando", substring = true)
        compose.onNodeWithTag(COLLECTOR_SURFACE_LEVEL_TAG).assertDoesNotExist()
    }
}
