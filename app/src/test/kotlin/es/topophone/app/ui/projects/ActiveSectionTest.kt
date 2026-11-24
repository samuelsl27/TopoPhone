package es.topophone.app.ui.projects

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.core.designsystem.theme.TopoPhoneTheme
import es.topophone.core.model.preferences.EffectiveTheme
import es.topophone.core.model.project.LinearUnit
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * La sección «Activo en este proyecto»: una fila por elemento con su nombre y su estado en texto, el texto largo cortado con su
 * descripción entera, las filas sin destino solo informativas, y la sección ausente sin nada activo; y a dónde lleva cada fila
 * (S059/RF-18 a RF-21, RNF-T8).
 */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "es-rES-h800dp")
class ActiveSectionTest {

    @get:Rule
    val compose = createComposeRule()

    private val everything = ActiveItems.of(
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

    private val opened = mutableListOf<ActiveKind>()
    private val destinations: Map<ActiveKind, () -> Unit> = ActiveKind.entries.associateWith { kind -> { opened += kind } }

    private fun show(items: List<ActiveItem>, destinations: Map<ActiveKind, () -> Unit> = this.destinations) = compose.setContent {
        TopoPhoneTheme(EffectiveTheme.LIGHT) { ActiveSection(items, LinearUnit.METRE, destinations) }
    }

    @Test
    fun `S059 RF-19 cada fila lleva su nombre y su estado en texto`() {
        show(everything)

        compose.onNodeWithText("Activo en este proyecto").assertIsDisplayed()
        compose.onNodeWithTag(activeRowTag(ActiveKind.TERRAIN)).assertTextContains("Factor 0,999912", substring = true)
        compose.onNodeWithTag(activeRowTag(ActiveKind.LOCALIZATION)).assertTextContains("RMS horizontal 0,014 m", substring = true)
        compose.onNodeWithTag(activeRowTag(ActiveKind.BASE_SHIFT)).assertTextContains("Vértice 4", substring = true)
        compose.onNodeWithTag(activeRowTag(ActiveKind.SURFACE)).assertTextContains("Terreno 2024", substring = true)
        compose.onNodeWithTag(activeRowTag(ActiveKind.ALIGNMENT)).assertTextContains("Eje A-1", substring = true)
        compose.onNodeWithTag(activeRowTag(ActiveKind.LAYERS)).assertTextContains("2 de 3 visibles", substring = true)
    }

    @Test
    fun `S059 RF-19 una localizacion sin RMS dice Activa`() {
        show(ActiveItems.of(ActiveInputs(localizationActive = true)))

        compose.onNodeWithTag(activeRowTag(ActiveKind.LOCALIZATION)).assertTextContains("Activa", substring = true)
    }

    @Test
    fun `S059 RF-20 pulsar una fila llama a su destino`() {
        show(everything)

        ActiveKind.entries.forEach { compose.onNodeWithTag(activeRowTag(it)).performClick() }

        opened shouldBe ActiveKind.entries
    }

    @Test
    fun `S059 RF-19b un nombre largo se corta en la fila y TalkBack lo lee entero`() {
        val long = "Levantamiento topografico completo de la finca de Las Eras, parcela 14, poligono 7"
        show(ActiveItems.of(ActiveInputs(surfaceName = long)))

        val node = compose.onNodeWithTag(activeRowTag(ActiveKind.SURFACE)).fetchSemanticsNode()
        node.config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString("").orEmpty().contains(long) shouldBe true
    }

    @Test
    fun `S059 RF-20b una fila sin destino solo informa y no es un boton`() {
        show(everything, destinations = emptyMap())

        val node = compose.onNodeWithTag(activeRowTag(ActiveKind.BASE_SHIFT)).fetchSemanticsNode()
        node.config.getOrNull(SemanticsActions.OnClick) shouldBe null
        node.config.getOrNull(SemanticsProperties.Role) shouldBe null
        compose.onNodeWithTag(activeRowTag(ActiveKind.BASE_SHIFT)).assertIsDisplayed()
    }

    @Test
    fun `S059 RF-21 sin nada activo la seccion no aparece`() {
        show(emptyList())

        compose.onAllNodesWithTag(ACTIVE_SECTION_TAG).assertCountEquals(0)
    }
}
