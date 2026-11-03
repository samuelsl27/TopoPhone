package es.topophone.app.ui.codes

import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertWidthIsAtLeast
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
import es.topophone.core.model.preferences.EffectiveTheme
import es.topophone.core.model.preferences.ThemeState
import es.topophone.core.model.project.NameError
import es.topophone.data.codes.LibrarySummary
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** La lista de bibliotecas de códigos: textos, menús por tipo de biblioteca y diálogos (S023/RF-1 a RF-3). Interfaz en español. */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "es-rES")
class CodeLibraryListScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val general = LibrarySummary("default:general", "Topografía general", true, 15)
    private val mine = LibrarySummary("u1", "Zona norte", false, 1)

    private fun show(state: CodeLibraryListUiState, actions: CodeLibraryListActions = CodeLibraryListActions()) {
        compose.setContent {
            TopoPhoneTheme(EffectiveTheme.LIGHT) { CodeLibraryListScreen(ThemeState.DEFAULT, {}, state, actions, {}) }
        }
    }

    @Test
    fun `S023 RF-1 cada fila muestra el nombre, el numero de codigos y marca con texto las por defecto`() {
        show(CodeLibraryListUiState(listOf(general, mine)))

        compose.onNodeWithText("Topografía general").assertIsDisplayed()
        compose.onNodeWithText("15 códigos · Por defecto").assertIsDisplayed()
        compose.onNodeWithText("Zona norte").assertIsDisplayed()
        compose.onNodeWithText("1 código").assertIsDisplayed()
    }

    @Test
    fun `S023 RF-3 el menu de una por defecto solo ofrece duplicar y el de una del usuario, las tres acciones`() {
        show(CodeLibraryListUiState(listOf(general, mine)))

        compose.onNodeWithTag(codeLibraryMenuTag(general.id)).performClick()
        compose.onNodeWithTag(codeLibraryActionTag(general.id, "duplicate")).assertIsDisplayed()
        compose.onNodeWithTag(codeLibraryActionTag(general.id, "rename")).assertDoesNotExist()
        compose.onNodeWithTag(codeLibraryActionTag(general.id, "delete")).assertDoesNotExist()
    }

    @Test
    fun `S023 RF-2 las acciones del menu de una biblioteca del usuario llegan a quien las atiende`() {
        val calls = mutableListOf<String>()
        show(
            CodeLibraryListUiState(listOf(mine)),
            CodeLibraryListActions(
                onRename = { calls += "rename:${it.id}" },
                onDuplicate = { calls += "duplicate:$it" },
                onAskDelete = { calls += "delete:${it.id}" },
                onOpen = { calls += "open:$it" },
            ),
        )

        compose.onNodeWithTag(codeLibraryTag(mine.id)).performClick()
        listOf("rename", "duplicate", "delete").forEach {
            compose.onNodeWithTag(codeLibraryMenuTag(mine.id)).performClick()
            compose.onNodeWithTag(codeLibraryActionTag(mine.id, it)).performClick()
        }

        calls shouldBe listOf("open:u1", "rename:u1", "duplicate:u1", "delete:u1")
    }

    @Test
    fun `S023 RF-2 el dialogo de nueva biblioteca pide un nombre y lo entrega`() {
        var submitted: String? = null
        show(
            CodeLibraryListUiState(listOf(general), dialog = LibraryDialogState(LibraryDialog.Create)),
            CodeLibraryListActions(onSubmitName = {
                submitted =
                    it
            }),
        )

        compose.onNodeWithTag(CODE_LIBRARY_NAME_TAG).performTextReplacement("Aceras")
        compose.onNodeWithTag(CODE_LIBRARY_SAVE_TAG).performClick()

        submitted shouldBe "Aceras"
    }

    @Test
    fun `S023 RF-2 los motivos del nombre se muestran en el dialogo`() {
        show(CodeLibraryListUiState(listOf(general), dialog = LibraryDialogState(LibraryDialog.Create, NameError.DUPLICATE)))

        compose.onNodeWithText(text(R.string.codes_library_name_duplicate)).assertIsDisplayed()
    }

    @Test
    fun `S023 RF-2 borrar muestra la confirmacion con el nombre de la biblioteca`() {
        var confirmed = false
        show(CodeLibraryListUiState(listOf(mine), confirmDelete = mine), CodeLibraryListActions(onConfirmDelete = { confirmed = true }))

        compose.onNodeWithText(text(R.string.codes_library_delete_message, "Zona norte")).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.codes_library_delete)).performClick()

        confirmed shouldBe true
    }

    @Test
    fun `S023 RNF-T8 las zonas tactiles miden al menos 56 dp`() {
        show(CodeLibraryListUiState(listOf(general, mine)))

        compose.onNodeWithTag(CODE_LIBRARY_NEW_TAG).assertHeightIsAtLeast(MinTouchTarget)
        compose.onNodeWithTag(codeLibraryTag(mine.id)).assertHeightIsAtLeast(MinTouchTarget)
        compose.onNodeWithTag(codeLibraryMenuTag(mine.id)).assertHeightIsAtLeast(MinTouchTarget).assertWidthIsAtLeast(MinTouchTarget)
    }
}
