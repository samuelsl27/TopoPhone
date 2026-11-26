package es.topophone.app.ui.surface

import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.core.designsystem.theme.TopoPhoneTheme
import es.topophone.core.model.cad.CadSourceCrs
import es.topophone.core.model.preferences.EffectiveTheme
import es.topophone.core.model.project.LinearUnit
import es.topophone.core.model.surface.Surface
import es.topophone.core.model.surface.SurfaceExtent
import es.topophone.core.model.surface.SurfaceKind
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** «Replantear» una superficie desde su ficha y desde la tarjeta del toque en el mapa (S062/RF-1, RF-2). Interfaz en es-ES. */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "es-rES")
class SurfaceStakeEntryTest {

    @get:Rule
    val compose = createComposeRule()

    private val calls = mutableListOf<String>()

    private val surface = Surface(
        id = "s1",
        projectId = "p1",
        name = "explanacion",
        fileName = "explanacion.xml",
        importedAtMs = 1_792_000_000_000L,
        source = CadSourceCrs.Project,
        unit = LinearUnit.METRE,
        extent = SurfaceExtent(440_000.0, 440_120.5, 4_474_000.0, 4_474_100.0),
        minElevationM = 600.0,
        maxElevationM = 608.7,
        vertexCount = 42,
        triangleCount = 58,
        holeCount = 2,
    )

    @Test
    fun `S062 RF-1 la ficha de cada superficie tiene Replantear`() {
        compose.setContent {
            TopoPhoneTheme(EffectiveTheme.LIGHT) {
                SurfacesSheet(SurfacesUiState(listOf(surface)), SurfacesActions(onStake = { calls += "stake:${it.id}" }), onDismiss = {})
            }
        }

        compose.onNodeWithTag(surfaceStakeTag("s1")).performScrollTo().assertHeightIsAtLeast(56.dp).performClick()

        calls shouldBe listOf("stake:s1")
    }

    @Test
    fun `S062 RF-2 la tarjeta del toque tiene Replantear en cada superficie`() {
        val ui = SurfaceTapUi(
            listOf(
                SurfaceTapRow("s1", "explanacion", SurfaceKind.TIN, active = true, heightM = 602.0),
                SurfaceTapRow("r1", "mdt", SurfaceKind.RASTER, active = false, heightM = null),
            ),
        )
        compose.setContent {
            TopoPhoneTheme(EffectiveTheme.LIGHT) { SurfaceTapSheet(ui, LinearUnit.METRE, onDismiss = {}) { calls += "stake:$it" } }
        }

        compose.onNodeWithTag(surfaceTapStakeTag("r1")).performScrollTo().assertHeightIsAtLeast(56.dp).performClick()
        compose.onNodeWithTag(surfaceTapStakeTag("s1")).performScrollTo().performClick()

        calls shouldBe listOf("stake:r1", "stake:s1")
    }
}
