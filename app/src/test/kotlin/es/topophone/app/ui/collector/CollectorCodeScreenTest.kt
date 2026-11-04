package es.topophone.app.ui.collector

import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.core.designsystem.theme.TopoPhoneTheme
import es.topophone.core.gnss.nmea.SolutionStatus
import es.topophone.core.model.codes.AttributeError
import es.topophone.core.model.codes.AttributeType
import es.topophone.core.model.codes.CodeGeometry
import es.topophone.core.model.codes.SurveyCode
import es.topophone.core.model.preferences.EffectiveTheme
import es.topophone.core.model.project.LinearUnit
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** El selector de codigos del recolector (S024/RF-1, RF-2, RF-3, RF-11). Interfaz en es-ES. */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "es-rES")
class CollectorCodeScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private fun code(code: String, description: String) = SurveyCode("id-$code", code, description, CodeGeometry.POINT, 0x1E88E5)

    private val arb = code("ARB", "Árbol")
    private val pos = code("POS", "Poste")
    private val arq = code("ARQ", "Arqueta")

    private val base = CollectorUiState(
        loaded = true,
        name = "P12",
        poleHeightText = "1,800",
        live = LiveQuality(SolutionStatus.FIX, 0.010, 0.015),
    )

    private val calls = mutableListOf<String>()
    private val actions = CollectorActions(
        onSelectCode = { calls += "select:${it?.code}" },
        onCodeQuery = { calls += "query:$it" },
    )

    private fun show(state: CollectorUiState, actions: CollectorActions = this.actions) {
        compose.setContent {
            TopoPhoneTheme(EffectiveTheme.LIGHT) { CollectorPanel(state, LinearUnit.METRE, actions, onClose = {}) }
        }
    }

    private fun withCodes(
        selected: SurveyCode? = null,
        recentCount: Int = 0,
        options: List<SurveyCode> = listOf(pos, arb, arq),
        query: String = "",
    ) = base.copy(code = CollectorCodeUi(selected = selected, query = query, options = options, recentCount = recentCount, totalCodes = 3))

    private fun openSelector() {
        compose.onNodeWithTag(COLLECTOR_CODE_TAG).performScrollTo().performClick()
    }

    @Test
    fun `S024 RF-1 junto al nombre y la descripcion se ve el codigo elegido o Sin codigo`() {
        show(withCodes())
        compose.onNodeWithTag(COLLECTOR_CODE_TAG).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag(COLLECTOR_CODE_TAG).assert(hasText("Código: Sin código", substring = true))
    }

    @Test
    fun `S024 RF-1 el codigo elegido se ve con su descripcion`() {
        show(withCodes(selected = arq))

        compose.onNodeWithTag(COLLECTOR_CODE_TAG).assert(hasText("Código: ARQ · Arqueta", substring = true))
    }

    @Test
    fun `S024 RF-1 el selector ofrece Sin codigo, los recientes primero y luego todos`() {
        show(withCodes(recentCount = 1))
        openSelector()

        compose.onNodeWithTag(COLLECTOR_CODE_NONE_TAG).assertIsDisplayed()
        compose.onNodeWithText("Recientes").assertIsDisplayed()
        compose.onNodeWithText("Todos los códigos").assertIsDisplayed()
        compose.onNodeWithTag(codeOptionTag("POS")).assertIsDisplayed()
    }

    @Test
    fun `S024 RF-3 elegir un codigo lo comunica y cierra el selector`() {
        show(withCodes(recentCount = 1))
        openSelector()

        compose.onNodeWithTag(codeOptionTag("POS")).performClick()

        calls shouldBe listOf("select:POS")
        compose.onNodeWithTag(COLLECTOR_CODE_SEARCH_TAG).assertDoesNotExist()
    }

    @Test
    fun `S024 RF-3 Sin codigo quita el codigo elegido`() {
        show(withCodes(selected = arq))
        openSelector()

        compose.onNodeWithTag(COLLECTOR_CODE_NONE_TAG).performClick()

        calls shouldBe listOf("select:null")
    }

    @Test
    fun `S024 RF-2 escribir en el buscador lo comunica`() {
        show(withCodes())
        openSelector()

        compose.onNodeWithTag(COLLECTOR_CODE_SEARCH_TAG).performTextInput("arb")

        calls shouldBe listOf("query:arb")
    }

    @Test
    fun `S024 RF-2 sin coincidencias lo dice y sin codigos en el proyecto tambien`() {
        show(withCodes(options = emptyList(), query = "zzz"))
        openSelector()
        compose.onNodeWithTag(COLLECTOR_CODE_EMPTY_TAG).assert(hasText("Ningún código coincide"))
    }

    @Test
    fun `S024 RF-1 un proyecto sin codigos lo dice`() {
        show(base.copy(code = CollectorCodeUi(totalCodes = 0)))
        openSelector()

        compose.onNodeWithTag(COLLECTOR_CODE_EMPTY_TAG).assert(hasText("Este proyecto no tiene códigos de punto"))
    }

    @Test
    fun `S024 RF-11 con una medida en marcha el selector no se puede abrir`() {
        show(withCodes().copy(measuring = true))

        compose.onNodeWithTag(COLLECTOR_CODE_TAG).assertIsNotEnabled()
    }

    @Test
    fun `S024 RF-5 Nuevo codigo solo aparece si se puede crear`() {
        show(withCodes(), CollectorActions(onNewCode = { calls += "new" }))
        openSelector()

        compose.onNodeWithTag(COLLECTOR_CODE_NEW_TAG).performClick()

        calls shouldBe listOf("new")
    }

    private val fields = listOf(
        AttributeFieldUi("Material", AttributeType.LIST, listOf("PVC", "Hormigon"), "PVC", null),
        AttributeFieldUi("Diametro", AttributeType.NUMBER, emptyList(), "0,25", null),
        AttributeFieldUi("Unidades", AttributeType.INTEGER, emptyList(), "3,5", AttributeError.NOT_INTEGER),
        AttributeFieldUi("Nota", AttributeType.TEXT, emptyList(), "", null),
    )

    private fun withFields(selected: SurveyCode = arq) =
        base.copy(code = CollectorCodeUi(selected = selected, options = listOf(arq), totalCodes = 1, fields = fields))

    @Test
    fun `S024 RF-8 hay un campo por atributo con su valor precargado, de los cuatro tipos`() {
        show(withFields())

        compose.onNodeWithTag(attributeFieldTag(0)).performScrollTo().assert(hasText("Material: PVC", substring = true))
        compose.onNodeWithTag(attributeFieldTag(1)).performScrollTo().assert(hasText("0,25", substring = true))
        compose.onNodeWithTag(attributeFieldTag(2)).performScrollTo().assert(hasText("3,5", substring = true))
        compose.onNodeWithTag(attributeFieldTag(3)).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `S024 RF-8 escribir en un campo comunica su posicion y su texto`() {
        show(withFields(), CollectorActions(onCodeAttribute = { i, t -> calls += "attr:$i:$t" }))

        compose.onNodeWithTag(attributeFieldTag(3)).performScrollTo().performTextInput("junto al muro")

        calls shouldBe listOf("attr:3:junto al muro")
    }

    @Test
    fun `S024 RF-8 una lista se elige entre sus opciones o se deja sin valor`() {
        show(withFields(), CollectorActions(onCodeAttribute = { i, t -> calls += "attr:$i:$t" }))

        compose.onNodeWithTag(attributeFieldTag(0)).performScrollTo().performClick()
        compose.onNodeWithTag(attributeOptionTag(0, "Hormigon")).performClick()
        compose.onNodeWithTag(attributeFieldTag(0)).performScrollTo().performClick()
        compose.onNodeWithTag(COLLECTOR_ATTR_EMPTY_OPTION).performClick()

        calls shouldBe listOf("attr:0:Hormigon", "attr:0:")
    }

    @Test
    fun `S024 RF-9 un valor no valido se marca con su motivo`() {
        show(withFields())

        compose.onNodeWithText("Escribe un número entero").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `S024 RF-11 con una medida en marcha los atributos no se pueden cambiar`() {
        show(withFields().copy(measuring = true))

        compose.onNodeWithTag(attributeFieldTag(3)).performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag(attributeFieldTag(0)).performScrollTo().assertIsNotEnabled()
    }

    @Test
    fun `S024 RF-8 sin codigo no hay campos de atributos`() {
        show(withCodes())

        compose.onNodeWithTag(attributeFieldTag(0)).assertDoesNotExist()
    }

    @Test
    fun `S024 RF-7 solo los codigos creados en el proyecto tienen Editar`() {
        val ui = withCodes(options = listOf(pos, arb)).let { it.copy(code = it.code.copy(ownIds = setOf(pos.id))) }
        show(ui, CollectorActions(onEditCode = { calls += "edit:${it.code}" }))
        openSelector()

        compose.onNodeWithTag(codeEditTag("ARB")).assertDoesNotExist()
        compose.onNodeWithTag(codeEditTag("POS")).performClick()

        calls shouldBe listOf("edit:POS")
    }

    @Test
    fun `S024 RF-7 sin accion de editar no se ofrece`() {
        val ui = withCodes(options = listOf(pos)).let { it.copy(code = it.code.copy(ownIds = setOf(pos.id))) }
        show(ui)
        openSelector()

        compose.onNodeWithTag(codeEditTag("POS")).assertDoesNotExist()
    }
}
