package es.topophone.app.ui.surface

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
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

/** El panel «Superficies»: la lista, activar, borrar con confirmación y el desplazamiento vertical (S042/RF-16 a RF-20). Interfaz en es-ES. */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "es-rES")
class SurfacesSheetTest {

    @get:Rule
    val compose = createComposeRule()

    private val calls = mutableListOf<String>()
    private var offsetResult = true
    private val actions = SurfacesActions(
        onActivate = { calls += "activate:$it" },
        onDelete = { calls += "delete:$it" },
        onOffset = {
            calls += "offset:$it"
            offsetResult
        },
    )

    private fun surface(id: String, name: String, file: String = "obra.xml") = Surface(
        id = id,
        projectId = "p1",
        name = name,
        fileName = file,
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

    private val two = SurfacesUiState(listOf(surface("s1", "terreno"), surface("s2", "rasante", "diseno.xml")), activeId = "s1")

    private fun show(state: SurfacesUiState) {
        compose.setContent {
            TopoPhoneTheme(EffectiveTheme.LIGHT) { SurfacesSheet(state, actions, onDismiss = { calls += "dismiss" }) }
        }
    }

    private fun shown(text: String) = compose.onNodeWithText(text, substring = true).performScrollTo().assertIsDisplayed()

    private fun tap(tag: String) = compose.onNodeWithTag(tag).performScrollTo().performClick()

    @Test
    fun `S042 RF-16 sin superficies lo dice y aun deja ver el desplazamiento`() {
        show(SurfacesUiState())

        compose.onNodeWithTag(SURFACES_EMPTY_TAG).assertIsDisplayed()
        shown("Desplazamiento vertical")
    }

    @Test
    fun `S042 RF-16 cada superficie sale con su nombre su fichero sus recuentos su extension y sus cotas`() {
        show(two)

        shown("terreno")
        shown("rasante")
        shown("diseno.xml")
        compose.onAllNodesWithText("42 puntos · 58 triángulos · 2 huecos").assertCountEquals(2)
        compose.onAllNodesWithText("120,5 × 100,0 m · cota de 600,0 a 608,7 m").assertCountEquals(2)
        compose.onAllNodesWithText("Importada el", substring = true).assertCountEquals(2)
    }

    @Test
    fun `S042 RF-16 la activa se dice en texto y la otra se puede activar en un toque`() {
        show(two)

        compose.onNodeWithTag(surfaceStateTag("s1")).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag(surfaceStateTag("s1")).assertTextEquals("Activa")
        compose.onNodeWithTag(surfaceStateTag("s2")).assertTextEquals("Inactiva")
        compose.onNodeWithTag(surfaceActivateTag("s1")).assertDoesNotExist()
        tap(surfaceActivateTag("s2"))

        calls shouldBe listOf("activate:s2")
    }

    @Test
    fun `S042 RF-16 la activa se puede desactivar para quedarse sin ninguna`() {
        show(two)

        tap(surfaceDeactivateTag("s1"))

        calls shouldBe listOf("activate:null")
        compose.onNodeWithTag(surfaceDeactivateTag("s2")).assertDoesNotExist()
    }

    @Test
    fun `S042 RF-18 borrar pide confirmacion antes de borrar nada`() {
        show(two)

        tap(surfaceDeleteTag("s2"))

        calls shouldBe emptyList()
        compose.onNodeWithText("Se borrará la superficie «rasante» del fichero «diseno.xml»", substring = true).assertIsDisplayed()
        compose.onNodeWithText("conservan sus datos", substring = true).assertIsDisplayed()
        compose.onNodeWithTag(SURFACE_DELETE_CONFIRM_TAG).performClick()
        calls shouldBe listOf("delete:s2")
    }

    @Test
    fun `S042 RF-18 cancelar el borrado no borra`() {
        show(two)

        tap(surfaceDeleteTag("s1"))
        compose.onNodeWithText("Cancelar").performClick()

        calls shouldBe emptyList()
        compose.onNodeWithTag(SURFACE_DELETE_CONFIRM_TAG).assertDoesNotExist()
    }

    @Test
    fun `S042 RF-19 el campo del desplazamiento enseña el valor de ahora en la unidad del proyecto`() {
        show(two.copy(offsetM = 0.3048, unit = LinearUnit.INTERNATIONAL_FOOT))

        compose.onNodeWithTag(SURFACE_OFFSET_FIELD_TAG).performScrollTo().assertTextContains("1", substring = true)
        shown("Desplazamiento (ft)")
    }

    @Test
    fun `S042 RF-19 aplicar manda lo escrito con su signo`() {
        show(two)

        compose.onNodeWithTag(SURFACE_OFFSET_FIELD_TAG).performScrollTo().performTextClearance()
        compose.onNodeWithTag(SURFACE_OFFSET_FIELD_TAG).performTextInput("-0,45")
        tap(SURFACE_OFFSET_APPLY_TAG)

        calls shouldBe listOf("offset:-0,45")
        compose.onNodeWithTag(SURFACE_OFFSET_ERROR_TAG, useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun `S042 RF-20 un desplazamiento que no es un numero avisa`() {
        offsetResult = false
        show(two)

        compose.onNodeWithTag(SURFACE_OFFSET_FIELD_TAG).performScrollTo().performTextClearance()
        compose.onNodeWithTag(SURFACE_OFFSET_FIELD_TAG).performTextInput("abc")
        tap(SURFACE_OFFSET_APPLY_TAG)

        compose.onNodeWithTag(SURFACE_OFFSET_ERROR_TAG, useUnmergedTree = true).assertExists()
        shown("Escribe un número.")
    }

    @Test
    fun `S042 RNF-T8 los botones tienen zonas tactiles de 56 dp`() {
        show(two)

        compose.onNodeWithTag(surfaceDeactivateTag("s1")).performScrollTo().assertHeightIsAtLeast(56.dp)
        compose.onNodeWithTag(surfaceActivateTag("s2")).performScrollTo().assertHeightIsAtLeast(56.dp)
        compose.onNodeWithTag(surfaceDeleteTag("s1")).performScrollTo().assertHeightIsAtLeast(56.dp)
        compose.onNodeWithTag(SURFACE_OFFSET_APPLY_TAG).performScrollTo().assertHeightIsAtLeast(56.dp)
    }
}
