package es.topophone.app.ui.surface

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
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
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** El interruptor «Ver en el mapa» de cada superficie, independiente de la activa (S061/RF-3). Interfaz en es-ES. */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "es-rES")
class SurfacesSheetVisibilityTest {

    @get:Rule
    val compose = createComposeRule()

    private val calls = mutableListOf<String>()
    private val actions = SurfacesActions(onVisible = { id, visible -> calls += "visible:$id:$visible" })

    private fun surface(id: String, visible: Boolean) = Surface(
        id = id,
        projectId = "p1",
        name = "terreno $id",
        fileName = "$id.xml",
        importedAtMs = 1_792_000_000_000L,
        source = CadSourceCrs.Project,
        unit = LinearUnit.METRE,
        extent = SurfaceExtent(440_000.0, 440_120.5, 4_474_000.0, 4_474_100.0),
        minElevationM = 600.0,
        maxElevationM = 608.7,
        vertexCount = 42,
        triangleCount = 58,
        holeCount = 2,
        visible = visible,
    )

    private fun show(state: SurfacesUiState) {
        compose.setContent { TopoPhoneTheme(EffectiveTheme.LIGHT) { SurfacesSheet(state, actions, onDismiss = {}) } }
    }

    @Test
    fun `S061 RF-3 cada superficie tiene su interruptor con su estado aunque no sea la activa`() {
        show(SurfacesUiState(listOf(surface("s1", visible = false), surface("s2", visible = true)), activeId = "s1"))

        compose.onNodeWithTag(surfaceVisibleTag("s1")).performScrollTo().assertIsOff().assertHeightIsAtLeast(56.dp)
        compose.onNodeWithTag(surfaceVisibleTag("s2")).performScrollTo().assertIsOn()
        compose.onAllNodesWithText("Ver en el mapa", useUnmergedTree = true).assertCountEquals(2)
    }

    @Test
    fun `S061 RF-3 tocar el interruptor pide mostrar u ocultar esa superficie`() {
        show(SurfacesUiState(listOf(surface("s1", visible = false), surface("s2", visible = true)), activeId = "s1"))

        compose.onNodeWithTag(surfaceVisibleTag("s1")).performScrollTo().performClick()
        compose.onNodeWithTag(surfaceVisibleTag("s2")).performScrollTo().performClick()

        calls shouldBe listOf("visible:s1:true", "visible:s2:false")
    }
}
