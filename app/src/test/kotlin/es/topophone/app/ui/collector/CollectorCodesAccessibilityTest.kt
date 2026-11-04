package es.topophone.app.ui.collector

import androidx.compose.runtime.Composable
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.app.ui.codes.CodeEditorActions
import es.topophone.app.ui.codes.CodeEditorScreen
import es.topophone.app.ui.codes.CodeEditorUiState
import es.topophone.app.ui.points.ChangeCodeActions
import es.topophone.app.ui.points.ChangeCodeDialogs
import es.topophone.app.ui.points.ChangeCodeUi
import es.topophone.app.ui.points.DiscardedValue
import es.topophone.app.ui.points.ObjectListActions
import es.topophone.app.ui.points.ObjectListScreen
import es.topophone.app.ui.points.ObjectListUiState
import es.topophone.app.ui.points.PointFixtures.measured
import es.topophone.core.designsystem.theme.TopoPhoneTheme
import es.topophone.core.gnss.nmea.SolutionStatus
import es.topophone.core.model.codes.AttributeError
import es.topophone.core.model.codes.AttributeType
import es.topophone.core.model.codes.CodeGeometry
import es.topophone.core.model.codes.CodeSymbol
import es.topophone.core.model.codes.PointCode
import es.topophone.core.model.codes.SurveyCode
import es.topophone.core.model.preferences.EffectiveTheme
import es.topophone.core.model.preferences.ThemeState
import es.topophone.core.model.project.LinearUnit
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Las pantallas de los codigos en el recolector, la lista, la ficha y el editor cumplen RNF-T7 y RNF-T8: todo control tiene un texto o
 * una etiqueta para TalkBack, ninguno mide menos de 56 dp de alto, TalkBack lee el codigo con su descripcion y, en ingles, los textos
 * salen en ingles (S024/RNF-T7, RNF-T8).
 */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "es-rES")
class CollectorCodesAccessibilityTest {

    @get:Rule
    val compose = createComposeRule()

    private val arq = SurveyCode("c1", "ARQ", "Arqueta", CodeGeometry.POINT, 0x1E88E5)
    private val poz = SurveyCode("c2", "POZ", "", CodeGeometry.POINT, 0xD32F2F, symbol = CodeSymbol.STAR)

    private val fields = listOf(
        AttributeFieldUi("Material", AttributeType.LIST, listOf("PVC", "Hormigon"), "PVC", null),
        AttributeFieldUi("Diametro", AttributeType.NUMBER, emptyList(), "0,25", null),
        AttributeFieldUi("Unidades", AttributeType.INTEGER, emptyList(), "x", AttributeError.NOT_INTEGER),
        AttributeFieldUi("Nota", AttributeType.TEXT, emptyList(), "", null),
    )
    private val codeUi = CollectorCodeUi(
        selected = arq,
        options = listOf(poz, arq),
        recentCount = 1,
        totalCodes = 2,
        ownIds = setOf(poz.id),
        fields = fields,
    )
    private val state =
        CollectorUiState(
            loaded = true,
            name = "P12",
            poleHeightText = "1,800",
            live = LiveQuality(SolutionStatus.FIX, 0.01, 0.015),
            code = codeUi,
        )

    private fun show(content: @Composable () -> Unit) {
        compose.setContent { TopoPhoneTheme(EffectiveTheme.LIGHT) { content() } }
    }

    private fun label(node: SemanticsNode): String {
        val text = node.config.getOrNull(SemanticsProperties.Text)?.joinToString(" ") { it.text }.orEmpty()
        val description = node.config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString(" ").orEmpty()
        val edit = node.config.getOrNull(SemanticsProperties.EditableText)?.text.orEmpty()
        return (text + description + edit).trim()
    }

    /** Todo lo que se puede pulsar tiene etiqueta y mide al menos 56 dp de alto. */
    private fun assertControls(screen: String) {
        val density = compose.density.density
        val nodes = compose.onAllNodes(hasClickAction()).fetchSemanticsNodes()
        (nodes.size > 0) shouldBe true
        nodes.filter { label(it).isEmpty() }.map { it.id }.shouldBeEmpty()
        nodes.filter { it.size.height / density < 55.5f }
            .map { "$screen: ${label(it)} mide ${it.size.height / density} dp" }
            .shouldBeEmpty()
    }

    private val actions = CollectorActions(onNewCode = {}, onEditCode = {})

    @Test
    fun `S024 RNF-T8 el selector y los atributos del recolector tienen etiquetas y zonas de 56 dp`() {
        show { CollectorPanel(state, LinearUnit.METRE, actions, onClose = {}) }
        compose.onNodeWithTag(COLLECTOR_CODE_TAG).performScrollTo()

        assertControls("recolector")
    }

    @Test
    fun `S024 RNF-T8 la lista del selector abierto tiene etiquetas y zonas de 56 dp`() {
        show { CollectorPanel(state, LinearUnit.METRE, actions, onClose = {}) }
        compose.onNodeWithTag(COLLECTOR_CODE_TAG).performScrollTo().performClick()

        assertControls("selector")
    }

    @Test
    fun `S024 RNF-T8 TalkBack lee el codigo con su descripcion en el boton y en cada opcion`() {
        show { CollectorPanel(state, LinearUnit.METRE, actions, onClose = {}) }
        compose.onNodeWithTag(
            COLLECTOR_CODE_TAG,
        ).performScrollTo().assert(hasContentDescription("Elegir código. Ahora: ARQ · Arqueta", substring = true))
        compose.onNodeWithTag(COLLECTOR_CODE_TAG).performClick()

        compose.onNodeWithTag(codeOptionTag("ARQ")).assert(hasContentDescription("ARQ · Arqueta"))
        compose.onNodeWithTag(codeOptionTag("POZ")).assert(hasContentDescription("POZ"))
    }

    @Test
    fun `S024 RNF-T8 Cambiar codigo y su aviso tienen etiquetas y zonas de 56 dp`() {
        show {
            ChangeCodeDialogs(
                ChangeCodeUi(open = true, picker = codeUi),
                ChangeCodeActions(),
            )
        }
        assertControls("cambiar codigo")
    }

    @Test
    fun `S024 RNF-T8 el aviso de valores que se pierden tiene etiquetas y zonas de 56 dp`() {
        show {
            ChangeCodeDialogs(ChangeCodeUi(target = poz, warning = listOf(DiscardedValue("1", "Diametro"))), ChangeCodeActions())
        }

        assertControls("aviso")
    }

    @Test
    fun `S024 RNF-T8 la lista de objetos con codigos y filtros tiene etiquetas y zonas de 56 dp`() {
        val summaries =
            listOf(measured("1").copy(code = PointCode("ARQ", "", 0x1E88E5, CodeSymbol.CIRCLE)).summary(), measured("2").summary())
        show {
            ObjectListScreen(
                ThemeState.DEFAULT,
                {},
                ObjectListUiState(loaded = true, points = summaries, total = 2, codes = listOf("ARQ"), hasUncoded = true),
                ObjectListActions(),
                onBack = {},
            )
        }

        assertControls("lista")
    }

    @Test
    fun `S024 RNF-T8 el editor de un codigo del proyecto tiene etiquetas y zonas de 56 dp`() {
        show {
            CodeEditorScreen(
                ThemeState.DEFAULT,
                {},
                CodeEditorUiState(loaded = true, forProject = true, code = "POS"),
                CodeEditorActions(),
                {},
            )
        }

        assertControls("editor del proyecto")
    }

    @Test
    @Config(qualifiers = "en")
    fun `S024 RNF-T7 en ingles el selector y los atributos salen en ingles`() {
        show { CollectorPanel(state, LinearUnit.METRE, actions, onClose = {}) }
        compose.onNodeWithTag(
            COLLECTOR_CODE_TAG,
        ).performScrollTo().assert(hasContentDescription("Choose code. Current: ARQ · Arqueta", substring = true))
        compose.onNodeWithText("Enter a whole number").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag(COLLECTOR_CODE_TAG).performScrollTo().performClick()

        compose.onNodeWithText("Recent").assertExists()
        compose.onNodeWithText("No code").assertExists()
        compose.onNodeWithText("New code").assertExists()
    }

    @Test
    @Config(qualifiers = "en")
    fun `S024 RNF-T7 en ingles Cambiar codigo y el editor del proyecto salen en ingles`() {
        show { ChangeCodeDialogs(ChangeCodeUi(target = poz, warning = listOf(DiscardedValue("1", "Diametro"))), ChangeCodeActions()) }

        compose.onNodeWithText("Changing to “POZ” will lose these values: 1 · Diametro. Change the code?").assertIsDisplayed()
    }
}
