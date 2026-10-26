package es.topophone.app.ui.cad

import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.app.ui.map.FakeMapController
import es.topophone.app.ui.map.fakeMap
import es.topophone.app.ui.projects.ProjectViewScreen
import es.topophone.app.ui.projects.ProjectViewUiState
import es.topophone.core.designsystem.theme.TopoPhoneTheme
import es.topophone.core.model.cad.CadColor
import es.topophone.core.model.cad.CadDrawing
import es.topophone.core.model.cad.CadSourceCrs
import es.topophone.core.model.cad.DesignLayer
import es.topophone.core.model.cad.DesignLayerKind
import es.topophone.core.model.preferences.EffectiveTheme
import es.topophone.core.model.preferences.ThemeState
import es.topophone.core.model.project.LinearUnit
import es.topophone.data.cad.CadDrawingWithLayers
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** El panel «Dibujos»: capas por tipo con su interruptor y el borrado con confirmación (S040/RF-26 a RF-28, RF-30). Interfaz en es-ES. */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "es-rES")
class DrawingsSheetTest {

    @get:Rule
    val compose = createComposeRule()

    private val calls = mutableListOf<String>()
    private val actions = DrawingsActions(
        onLayerVisible = { id, visible -> calls += "visible:$id:$visible" },
        onDeleteDrawing = { calls += "delete:$it" },
    )

    private fun drawing(id: String, file: String, incomplete: Boolean = false) = CadDrawing(
        id, "p1", file, 1_792_346_000_000L, CadSourceCrs.Project, LinearUnit.METRE, useElevations = true, hasElevations = true,
        incomplete = incomplete,
    )

    private fun layer(
        id: String,
        drawingId: String,
        name: String,
        kind: DesignLayerKind,
        count: Int,
        visible: Boolean = true,
        color: CadColor = CadColor.rgb(0xFF0000),
    ) = DesignLayer(id, drawingId, name, kind, color, visible, count)

    private val one = CadDrawingWithLayers(
        drawing("d1", "calle.dxf"),
        listOf(
            layer("l1", "d1", "Ejes", DesignLayerKind.LINES, 12),
            layer("l2", "d1", "Pozos", DesignLayerKind.POINTS, 1, visible = false),
            layer("l3", "d1", "Blanca", DesignLayerKind.LINES, 3, color = CadColor.AUTO_CONTRAST),
        ),
    )
    private val two =
        CadDrawingWithLayers(drawing("d2", "redes.dxf", incomplete = true), listOf(layer("l4", "d2", "Ejes", DesignLayerKind.LINES, 7)))

    private fun show(vararg drawings: CadDrawingWithLayers) {
        compose.setContent {
            TopoPhoneTheme(EffectiveTheme.LIGHT) { DrawingsSheet(drawings.toList(), actions, onDismiss = { calls += "dismiss" }) }
        }
    }

    @Test
    fun `S040 RF-26 las capas de cada dibujo salen agrupadas en puntos y lineas con su recuento`() {
        show(one)

        compose.onNodeWithText("calle.dxf").assertIsDisplayed()
        compose.onNodeWithTag(layerGroupTag("d1", DesignLayerKind.POINTS)).assertIsDisplayed()
        compose.onNodeWithTag(layerGroupTag("d1", DesignLayerKind.LINES)).assertIsDisplayed()
        compose.onNodeWithText("12 elementos · Visible").assertIsDisplayed()
        compose.onNodeWithText("1 elemento · Oculta").assertIsDisplayed()
    }

    @Test
    fun `S040 RF-26 el estado de una capa se dice con texto y no solo con el interruptor`() {
        show(one)

        compose.onAllNodesWithText("Oculta", substring = true).assertCountEquals(1)
        compose.onAllNodesWithText("Visible", substring = true).assertCountEquals(2)
    }

    @Test
    fun `S040 RF-26 TalkBack lee el nombre de la capa, su dibujo, su recuento y su estado`() {
        show(one)

        compose.onNodeWithTag(layerSwitchTag("l1")).assertContentDescriptionEquals("Ejes, dibujo calle.dxf, 12 elementos, Visible")
        compose.onNodeWithTag(layerSwitchTag("l2")).assertContentDescriptionEquals("Pozos, dibujo calle.dxf, 1 elemento, Oculta")
    }

    @Test
    fun `S040 RNF-T8 cada interruptor mide al menos 56 dp`() {
        show(one)

        listOf("l1", "l2", "l3").forEach { compose.onNodeWithTag(layerSwitchTag(it)).assertHeightIsAtLeast(56.dp) }
        compose.onNodeWithTag(drawingDeleteTag("d1")).assertHeightIsAtLeast(56.dp)
    }

    @Test
    fun `S040 RF-27 tocar una capa visible la oculta y tocar una oculta la muestra`() {
        show(one)

        compose.onNodeWithTag(layerSwitchTag("l1")).performClick()
        compose.onNodeWithTag(layerSwitchTag("l2")).performClick()

        calls shouldBe listOf("visible:l1:false", "visible:l2:true")
    }

    @Test
    fun `S040 RF-28 dos dibujos con una capa del mismo nombre se listan aparte, cada una con su dibujo`() {
        show(one, two)

        compose.onAllNodesWithText("Ejes").assertCountEquals(2)
        compose.onNodeWithTag(layerSwitchTag("l1")).assertContentDescriptionEquals("Ejes, dibujo calle.dxf, 12 elementos, Visible")
        compose.onNodeWithTag(layerSwitchTag("l4")).assertContentDescriptionEquals("Ejes, dibujo redes.dxf, 7 elementos, Visible")
        compose.onNodeWithText("redes.dxf").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `S040 RF-13 un dibujo incompleto lo dice`() {
        show(two)

        compose.onNodeWithText("Incompleto").assertIsDisplayed()
    }

    @Test
    fun `S040 RF-30 borrar un dibujo pide confirmacion y no borra nada antes`() {
        show(one, two)

        compose.onNodeWithTag(drawingDeleteTag("d2")).performScrollTo().performClick()

        compose.onNodeWithText("¿Borrar este dibujo?").assertIsDisplayed()
        compose.onNodeWithText(
            "Se borra el dibujo «redes.dxf» con todas sus capas. Los puntos del proyecto no se tocan.",
        ).assertIsDisplayed()
        calls shouldBe emptyList()
        compose.onNodeWithTag(DRAWING_DELETE_CONFIRM_TAG).performClick()
        calls shouldBe listOf("delete:d2")
    }

    @Test
    fun `S040 RF-30 cancelar la confirmacion no borra nada`() {
        show(one)
        compose.onNodeWithTag(drawingDeleteTag("d1")).performScrollTo().performClick()

        compose.onNodeWithText("Cancelar").performClick()

        calls shouldBe emptyList()
        compose.onNodeWithText("¿Borrar este dibujo?").assertDoesNotExist()
    }

    @Test
    fun `S040 RF-26 sin dibujos el panel lo explica`() {
        show()

        compose.onNodeWithTag(DRAWINGS_EMPTY_TAG).assertIsDisplayed()
    }

    @Test
    fun `S040 RF-26 la vista de proyecto ofrece el panel solo cuando hay dibujos y lo abre`() {
        val map = FakeMapController()
        var shown = listOf(one)
        compose.setContent {
            TopoPhoneTheme(EffectiveTheme.LIGHT) {
                ProjectViewScreen(
                    ThemeState.DEFAULT,
                    {},
                    ProjectViewUiState(),
                    onBack = {},
                    map = fakeMap(map),
                    drawings = shown,
                    drawingsActions = actions,
                )
            }
        }

        compose.onNodeWithTag(DRAWINGS_BUTTON_TAG).assertIsDisplayed().performClick()

        compose.onNodeWithTag(DRAWINGS_SHEET_TAG).assertIsDisplayed()
        compose.onNodeWithText("Ejes").assertIsDisplayed()
        shown = emptyList()
    }

    @Test
    fun `S040 RF-26 sin dibujos la vista de proyecto no ocupa sitio con el boton`() {
        compose.setContent {
            TopoPhoneTheme(EffectiveTheme.LIGHT) {
                ProjectViewScreen(ThemeState.DEFAULT, {}, ProjectViewUiState(), onBack = {}, map = fakeMap(FakeMapController()))
            }
        }

        compose.onNodeWithTag(DRAWINGS_BUTTON_TAG).assertDoesNotExist()
    }
}
