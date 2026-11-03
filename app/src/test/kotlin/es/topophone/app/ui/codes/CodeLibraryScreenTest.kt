package es.topophone.app.ui.codes

import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.app.R
import es.topophone.app.ui.text
import es.topophone.core.designsystem.component.MinTouchTarget
import es.topophone.core.designsystem.theme.TopoPhoneTheme
import es.topophone.core.model.codes.CodeGeometry
import es.topophone.core.model.codes.CodeLibrary
import es.topophone.core.model.codes.SurveyCode
import es.topophone.core.model.preferences.EffectiveTheme
import es.topophone.core.model.preferences.ThemeState
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** La pantalla de una biblioteca: código con su color y su geometría en texto, buscador y botones según se pueda editar (S023/RF-3, RF-8, RF-11, RF-17). */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "es-rES")
class CodeLibraryScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val arq = SurveyCode("c1", "ARQ", "Arqueta", CodeGeometry.POINT, 0x1E88E5)
    private val bor = SurveyCode("c2", "BOR", "", CodeGeometry.LINE, 0xE64A19)

    private fun state(isDefault: Boolean, query: String = "", codes: List<SurveyCode> = listOf(arq, bor), message: ExportMessage? = null) =
        CodeLibraryUiState(
            loaded = true,
            library = CodeLibrary("l1", "Mi biblioteca", isDefault, listOf(arq, bor)),
            query = query,
            codes = codes,
            message = message,
        )

    private fun show(state: CodeLibraryUiState, actions: CodeLibraryActions = CodeLibraryActions()) {
        compose.setContent {
            TopoPhoneTheme(EffectiveTheme.LIGHT) { CodeLibraryScreen(ThemeState.DEFAULT, {}, state, actions, {}) }
        }
    }

    @Test
    fun `S023 RF-8 cada codigo muestra su texto junto al color y su geometria con texto`() {
        show(state(isDefault = false))

        compose.onNodeWithText("ARQ").assertIsDisplayed()
        compose.onNodeWithText("Arqueta · Punto").assertIsDisplayed()
        compose.onNodeWithText("BOR").assertIsDisplayed()
        compose.onNodeWithText("Línea").assertIsDisplayed()
    }

    @Test
    fun `S023 RF-3 una biblioteca del usuario ofrece anadir, importar y exportar`() {
        val calls = mutableListOf<String>()
        show(
            state(isDefault = false),
            CodeLibraryActions(onAdd = { calls += "add" }, onImport = { calls += "import" }, onExport = { calls += "export" }, onEdit = {
                calls +=
                    "edit:$it"
            }),
        )

        listOf(CODE_ADD_TAG, CODE_IMPORT_TAG, CODE_EXPORT_TAG, codeRowTag("c1")).forEach { compose.onNodeWithTag(it).performClick() }

        calls shouldBe listOf("add", "import", "export", "edit:c1")
    }

    @Test
    fun `S023 RF-3 una por defecto avisa de que es de solo lectura y solo permite exportar`() {
        val calls = mutableListOf<String>()
        show(state(isDefault = true), CodeLibraryActions(onEdit = { calls += "edit:$it" }, onExport = { calls += "export" }))

        compose.onNodeWithTag(CODE_READONLY_TAG).assertIsDisplayed()
        compose.onNodeWithTag(CODE_EXPORT_TAG).performClick()
        compose.onNodeWithTag(codeRowTag("c1")).performClick()
        compose.onNodeWithTag(CODE_ADD_TAG).assertDoesNotExist()
        compose.onNodeWithTag(CODE_IMPORT_TAG).assertDoesNotExist()

        calls shouldBe listOf("export")
    }

    @Test
    fun `S023 RF-11 el texto del buscador llega a quien lo atiende`() {
        var typed = ""
        show(state(isDefault = false), CodeLibraryActions(onQueryChange = { typed = it }))

        compose.onNodeWithTag(CODE_SEARCH_TAG).performTextReplacement("arq")

        typed shouldBe "arq"
    }

    @Test
    fun `S023 RF-11 sin coincidencias se dice que codigo se buscaba`() {
        show(state(isDefault = false, query = " zzz ", codes = emptyList()))

        compose.onNodeWithText(text(R.string.codes_no_match, "zzz")).assertIsDisplayed()
    }

    @Test
    fun `S023 RF-17 el resultado de exportar se muestra con texto`() {
        show(state(isDefault = false, message = ExportMessage.FAILED))

        compose.onNodeWithText(text(R.string.codes_export_failed)).assertIsDisplayed()
    }

    @Test
    fun `S023 RNF-T8 los botones y las filas miden al menos 56 dp`() {
        show(state(isDefault = false))

        listOf(CODE_ADD_TAG, CODE_IMPORT_TAG, CODE_EXPORT_TAG, codeRowTag("c1")).forEach {
            compose.onNodeWithTag(it).assertHeightIsAtLeast(MinTouchTarget)
        }
    }
}
