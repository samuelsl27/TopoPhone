package es.topophone.app.ui.options

import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.app.ui.points.OBJECTS_MENU_TAG
import es.topophone.app.ui.points.OBJECTS_TAB_LINES_TAG
import es.topophone.app.ui.points.OBJECTS_TAB_POINTS_TAG
import es.topophone.app.ui.points.ObjectListActions
import es.topophone.app.ui.points.ObjectListScreen
import es.topophone.app.ui.points.ObjectListUiState
import es.topophone.app.ui.points.PointFixtures
import es.topophone.app.ui.projects.ACTIVE_SECTION_TAG
import es.topophone.app.ui.projects.ActiveInputs
import es.topophone.app.ui.projects.ActiveItems
import es.topophone.app.ui.projects.ActiveKind
import es.topophone.app.ui.projects.ActiveSection
import es.topophone.app.ui.projects.activeRowTag
import es.topophone.core.model.preferences.ThemeState
import es.topophone.core.model.project.LinearUnit
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * La matriz de tamaño y accesibilidad de los menús, las pestañas y la sección de lo activo: con 360 y 411 dp de ancho y la letra al 100 % y
 * al 130 %, cada control existe, mide 56 dp, cabe en el ancho, ningún texto se corta y cada control que se toca tiene un nombre para
 * TalkBack (S059/RNF-T8 y los RNF específicos). Los gráficos nativos miden el texto de verdad.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class OptionsAccessibilityMatrixTest {

    @get:Rule
    val compose = createComposeRule()

    private val state = ObjectListUiState(loaded = true, points = listOf(PointFixtures.manual("E1").summary()), total = 1)

    private fun assertControls(widthDp: Int, tags: List<String>) {
        tags.forEach { tag ->
            val node = compose.onNodeWithTag(tag)
            node.assertHeightIsAtLeast(56.dp)
            val bounds = node.getUnclippedBoundsInRoot()
            (bounds.left >= 0.dp && bounds.right <= widthDp.dp) shouldBe true
        }
    }

    private fun showObjects(fontScale: Float) =
        compose.setScaledContent(fontScale) { ObjectListScreen(ThemeState.DEFAULT, {}, state, ObjectListActions(), onBack = {}) }

    private fun list(widthDp: Int, fontScale: Float) {
        showObjects(fontScale)

        assertControls(
            widthDp,
            BAR_GROUPS.map { groupButtonTag(it) } + listOf(OBJECTS_TAB_POINTS_TAG, OBJECTS_TAB_LINES_TAG, OBJECTS_MENU_TAG),
        )
        compose.assertNoClippedText()
        compose.assertTalkBackLabels()
    }

    private fun menu(group: OptionGroup, widthDp: Int, fontScale: Float) {
        showObjects(fontScale)
        compose.onNodeWithTag(groupButtonTag(group)).performClick()

        assertControls(widthDp, ProjectOptions.of(group).map { optionTag(it) })
        compose.assertNoClippedText()
        compose.assertTalkBackLabels()
    }

    private fun active(widthDp: Int, fontScale: Float) {
        val items = ActiveItems.of(
            ActiveInputs(
                terrainFactor = 0.999_912,
                localizationActive = true,
                localizationRmsM = 0.014,
                baseShiftName = "Vértice 4",
                surfaceName = "Terreno 2024",
                alignmentName = "Eje A-1",
                layersVisible = 2,
                layersTotal = 3,
            ),
        )
        compose.setScaledContent(fontScale) {
            ActiveSection(items, LinearUnit.METRE, ActiveKind.entries.associateWith { { } })
        }

        assertControls(widthDp, ActiveKind.entries.map { activeRowTag(it) })
        compose.onNodeWithTag(ACTIVE_SECTION_TAG).assertHeightIsAtLeast(56.dp)
        compose.assertNoClippedText()
        compose.assertTalkBackLabels()
    }

    // --- 360 dp, letra al 100 % -------------------------------------------------------------------------------------------------------

    @Test
    @Config(qualifiers = "es-rES-w360dp-h800dp")
    fun `S059 RNF-T8 la lista de Objetos a 360 dp con la letra al 100 por cien`() = list(360, 1.0f)

    @Test
    @Config(qualifiers = "es-rES-w360dp-h800dp")
    fun `S059 RNF-T8 el menu de Importar a 360 dp con la letra al 100 por cien`() = menu(OptionGroup.IMPORT, 360, 1.0f)

    @Test
    @Config(qualifiers = "es-rES-w360dp-h800dp")
    fun `S059 RNF-T8 el menu de Herramientas a 360 dp con la letra al 100 por cien`() = menu(OptionGroup.TOOLS, 360, 1.0f)

    @Test
    @Config(qualifiers = "es-rES-w360dp-h800dp")
    fun `S059 RNF-T8 lo activo a 360 dp con la letra al 100 por cien`() = active(360, 1.0f)

    // --- 360 dp, letra al 130 % -------------------------------------------------------------------------------------------------------

    @Test
    @Config(qualifiers = "es-rES-w360dp-h800dp")
    fun `S059 RNF-T8 la lista de Objetos a 360 dp con la letra al 130 por cien`() = list(360, 1.3f)

    @Test
    @Config(qualifiers = "es-rES-w360dp-h800dp")
    fun `S059 RNF-T8 el menu de Importar a 360 dp con la letra al 130 por cien`() = menu(OptionGroup.IMPORT, 360, 1.3f)

    @Test
    @Config(qualifiers = "es-rES-w360dp-h800dp")
    fun `S059 RNF-T8 el menu de Herramientas a 360 dp con la letra al 130 por cien`() = menu(OptionGroup.TOOLS, 360, 1.3f)

    @Test
    @Config(qualifiers = "es-rES-w360dp-h800dp")
    fun `S059 RNF-T8 lo activo a 360 dp con la letra al 130 por cien`() = active(360, 1.3f)

    // --- 411 dp, letra al 100 % -------------------------------------------------------------------------------------------------------

    @Test
    @Config(qualifiers = "es-rES-w411dp-h891dp")
    fun `S059 RNF-T8 la lista de Objetos a 411 dp con la letra al 100 por cien`() = list(411, 1.0f)

    @Test
    @Config(qualifiers = "es-rES-w411dp-h891dp")
    fun `S059 RNF-T8 el menu de Importar a 411 dp con la letra al 100 por cien`() = menu(OptionGroup.IMPORT, 411, 1.0f)

    @Test
    @Config(qualifiers = "es-rES-w411dp-h891dp")
    fun `S059 RNF-T8 el menu de Herramientas a 411 dp con la letra al 100 por cien`() = menu(OptionGroup.TOOLS, 411, 1.0f)

    @Test
    @Config(qualifiers = "es-rES-w411dp-h891dp")
    fun `S059 RNF-T8 lo activo a 411 dp con la letra al 100 por cien`() = active(411, 1.0f)

    // --- 411 dp, letra al 130 % -------------------------------------------------------------------------------------------------------

    @Test
    @Config(qualifiers = "es-rES-w411dp-h891dp")
    fun `S059 RNF-T8 la lista de Objetos a 411 dp con la letra al 130 por cien`() = list(411, 1.3f)

    @Test
    @Config(qualifiers = "es-rES-w411dp-h891dp")
    fun `S059 RNF-T8 el menu de Importar a 411 dp con la letra al 130 por cien`() = menu(OptionGroup.IMPORT, 411, 1.3f)

    @Test
    @Config(qualifiers = "es-rES-w411dp-h891dp")
    fun `S059 RNF-T8 el menu de Herramientas a 411 dp con la letra al 130 por cien`() = menu(OptionGroup.TOOLS, 411, 1.3f)

    @Test
    @Config(qualifiers = "es-rES-w411dp-h891dp")
    fun `S059 RNF-T8 lo activo a 411 dp con la letra al 130 por cien`() = active(411, 1.3f)
}
