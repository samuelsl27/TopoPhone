package es.topophone.app.ui.points

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.core.designsystem.theme.TopoPhoneTheme
import es.topophone.core.model.codes.CodeSymbol
import es.topophone.core.model.codes.PointCode
import es.topophone.core.model.line.LineSummary
import es.topophone.core.model.preferences.EffectiveTheme
import es.topophone.core.model.preferences.ThemeState
import io.kotest.matchers.comparables.shouldBeLessThan
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Las pestañas de la lista de Objetos: «Puntos» y «Líneas y polígonos» con su contador, lo que va en cada una, que se conserva la elegida
 * y que cambiar de pestaña con un proyecto grande es ligero (S059/RF-11 a RF-17, RNF-T13).
 */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "es-rES-h800dp")
class ObjectTabsTest {

    @get:Rule
    val compose = createComposeRule()

    private val calls = mutableListOf<String>()
    private val actions = ObjectListActions(
        onOpenLine = { calls += "open:$it" },
        onSetLineHidden = { id, hidden -> calls += "hidden:$id:$hidden" },
        onStakeoutLine = { calls += "stakeout:$it" },
        onToggleSelection = { calls += "select:$it" },
    )

    private val bord = PointCode("BORD", "Bordillo", 0x1E88E5, CodeSymbol.CIRCLE)
    private val points = listOf(PointFixtures.measured("1").summary(), PointFixtures.manual("E1").summary())
    private val lines = listOf(
        LineSummary("l1", "BORD_1", bord, closed = false, hidden = false, active = true, vertexCount = 3),
        LineSummary("l2", "PARC_1", null, closed = true, hidden = true, active = false, vertexCount = 4),
    )
    private val full = ObjectListUiState(loaded = true, points = points, total = 2, lines = lines)

    private fun show(state: ObjectListUiState) = compose.setContent {
        TopoPhoneTheme(EffectiveTheme.LIGHT) { ObjectListScreen(ThemeState.DEFAULT, {}, state, actions, onBack = {}) }
    }

    private fun openLines() = compose.onNodeWithTag(OBJECTS_TAB_LINES_TAG).performClick()

    // --- T8: las pestañas ------------------------------------------------------------------------------------------------------------

    @Test
    fun `S059 RF-11 las dos pestañas llevan el numero de elementos que contienen`() {
        show(full)

        compose.onNodeWithTag(OBJECTS_TAB_POINTS_TAG).assertTextContains("Puntos (2)", substring = true)
        compose.onNodeWithTag(OBJECTS_TAB_LINES_TAG).assertTextContains("Líneas y polígonos (2)", substring = true)
    }

    @Test
    fun `S059 RF-12 la lista abre en Puntos aunque este vacia y el proyecto tenga lineas`() {
        show(ObjectListUiState(loaded = true, total = 0, lines = lines))

        compose.onNodeWithText("Todavía no hay puntos.").assertIsDisplayed()
        compose.onAllNodesWithTag(OBJECT_LINES_LIST_TAG).assertCountEquals(0)
    }

    @Test
    fun `S059 RF-11 cambiar de pestaña cambia la lista`() {
        show(full)

        compose.onNodeWithTag(objectRowTag("id-1")).assertIsDisplayed()
        openLines()
        compose.onNodeWithTag(objectLineRowTag("l1")).assertIsDisplayed()
        compose.onAllNodesWithTag(objectRowTag("id-1")).assertCountEquals(0)
    }

    // --- T9: la pestaña Puntos -------------------------------------------------------------------------------------------------------

    @Test
    fun `S059 RF-14 el buscador solo esta en la pestaña Puntos`() {
        show(full)

        compose.onNodeWithTag(OBJECT_SEARCH_TAG).assertIsDisplayed()
        openLines()
        compose.onAllNodesWithTag(OBJECT_SEARCH_TAG).assertCountEquals(0)
    }

    @Test
    fun `S059 RF-14 los filtros por codigo van en una sola fila que se desplaza de lado`() {
        show(full.copy(codes = listOf("BORD", "PARC", "FAR", "ARQ", "POZO", "VALLA", "MURO")))

        compose.onNodeWithTag(OBJECT_CODE_FILTER_ALL_TAG).assertIsDisplayed()
        val tops =
            listOf("BORD", "PARC").map { compose.onNodeWithTag(objectCodeFilterTag(it)).fetchSemanticsNode().boundsInRoot.top } +
                compose.onNodeWithTag(OBJECT_CODE_FILTER_ALL_TAG).fetchSemanticsNode().boundsInRoot.top
        tops.distinct().size shouldBe 1 // los filtros están en la misma fila
        compose.onNodeWithTag(objectCodeFilterTag("MURO")).assertIsNotDisplayed() // y el resto se alcanza desplazando de lado
    }

    @Test
    fun `S059 RF-17 la barra de seleccion con sus acciones solo sale en la pestaña Puntos`() {
        show(full.copy(selected = setOf("id-1")))

        compose.onNodeWithTag(SELECTION_BAR_TAG).assertIsDisplayed()
        listOf(SELECTION_HIDE_TAG, SELECTION_SHOW_TAG, SELECTION_DELETE_TAG, SELECTION_POLE_TAG, SELECTION_CODE_TAG, SELECTION_EXPORT_TAG)
            .forEach { compose.onNodeWithTag(it).assertExists() }
        openLines()
        compose.onAllNodesWithTag(SELECTION_BAR_TAG).assertCountEquals(0)
    }

    // --- T10: la pestaña Líneas y polígonos -----------------------------------------------------------------------------------------

    @Test
    fun `S059 RF-15 cada linea lleva su nombre y, en la otra fila, el tipo, los vertices y el estado`() {
        show(full)
        openLines()

        compose.onNodeWithText("BORD_1").assertIsDisplayed()
        compose.onNodeWithText("Abierta · 3 vértices · Activa").assertIsDisplayed()
        compose.onNodeWithText("Polígono · 4 vértices").assertIsDisplayed()
        compose.onNodeWithTag(objectLineCodeTag("l1"), useUnmergedTree = true).assertExists()
    }

    @Test
    fun `S059 RF-16 sin lineas la pestaña lo dice`() {
        show(ObjectListUiState(loaded = true, points = points, total = 2))
        openLines()

        compose.onNodeWithText("Este proyecto no tiene líneas ni polígonos.").assertIsDisplayed()
    }

    @Test
    fun `S059 RF-17 las acciones por fila de una linea siguen como estaban`() {
        show(full)
        openLines()

        compose.onNodeWithTag(objectLineRowTag("l1")).performClick()
        compose.onNodeWithTag(objectLineVisibilityTag("l1")).performClick()
        compose.onNodeWithTag(objectLineMenuTag("l1")).performClick()

        calls shouldBe listOf("open:l1", "hidden:l1:true")
        compose.onNodeWithText("Replantear").assertIsDisplayed()
    }

    // --- T11: restauración y rendimiento ---------------------------------------------------------------------------------------------

    @Test
    fun `S059 RF-13 la pestaña elegida se conserva al recrear la actividad`() {
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            TopoPhoneTheme(EffectiveTheme.LIGHT) { ObjectListScreen(ThemeState.DEFAULT, {}, full, actions, onBack = {}) }
        }
        openLines()

        restoration.emulateSavedInstanceStateRestore()

        compose.onNodeWithTag(objectLineRowTag("l1")).assertIsDisplayed()
        compose.onNodeWithTag(OBJECT_SEARCH_TAG).assertIsNotDisplayed()
    }

    @Test
    fun `S059 RNF-T13 cambiar de pestaña con 50000 puntos y 5000 lineas es ligero`() {
        val manyPoints = (1..50_000).map { PointFixtures.manual("P$it").summary() }
        val manyLines = (1..5_000).map {
            LineSummary("l$it", "L$it", bord, closed = it % 2 == 0, hidden = false, active = false, vertexCount = 3)
        }
        show(ObjectListUiState(loaded = true, points = manyPoints, total = 50_000, lines = manyLines))
        openLines()
        compose.onNodeWithTag(OBJECTS_TAB_POINTS_TAG).performClick() // calentamiento

        val worst = (1..5).maxOf {
            val start = System.nanoTime()
            openLines()
            compose.waitForIdle()
            val lines = (System.nanoTime() - start) / 1_000_000
            compose.onNodeWithTag(OBJECTS_TAB_POINTS_TAG).performClick()
            lines
        }

        // Solo se componen las filas que se ven; en Robolectric, con margen sobre los 16 ms de un móvil.
        worst shouldBeLessThan 300L
    }
}
