package es.topophone.app.ui.codes

import androidx.compose.runtime.Composable
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.core.designsystem.theme.TopoPhoneTheme
import es.topophone.core.formats.codes.InvalidRow
import es.topophone.core.formats.codes.RowProblem
import es.topophone.core.model.codes.AttributeType
import es.topophone.core.model.codes.CodeError
import es.topophone.core.model.codes.CodeGeometry
import es.topophone.core.model.codes.CodeLibrary
import es.topophone.core.model.codes.MergeConflicts
import es.topophone.core.model.codes.SurveyCode
import es.topophone.core.model.preferences.EffectiveTheme
import es.topophone.core.model.preferences.ThemeState
import es.topophone.data.codes.LibrarySummary
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Las pantallas de las bibliotecas de códigos cumplen RNF-T7 y RNF-T8: todo control tiene un texto o una etiqueta para TalkBack,
 * ninguno mide menos de 56 dp de alto y, en inglés, los textos salen en inglés. (S023/RNF-T7, RNF-T8)
 */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "es-rES")
class CodesAccessibilityTest {

    @get:Rule
    val compose = createComposeRule()

    private val codes = listOf(
        SurveyCode("c1", "ARQ", "Arqueta", CodeGeometry.POINT, 0x1E88E5),
        SurveyCode("c2", "BOR", "", CodeGeometry.LINE, 0xE64A19),
    )
    private val summaries = listOf(LibrarySummary("default:general", "General", true, 15), LibrarySummary("u1", "Mía", false, 2))

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

    private val list = CodeLibraryListUiState(summaries)
    private val library = CodeLibraryUiState(
        loaded = true,
        library = CodeLibrary("u1", "Mía", false, codes),
        codes = codes,
    )
    private val editor = CodeEditorUiState(
        loaded = true,
        isNew = false,
        code = "ARQ",
        attributes = listOf(
            AttributeDraft("Material", AttributeType.LIST, "PVC", listOf("PVC", "Hormigón")),
            AttributeDraft("Diámetro", AttributeType.NUMBER, "0.25"),
        ),
    )
    private val wizard = CodeImportUiState(
        stage = CodeImportStage.WIZARD,
        fileName = "codigos.csv",
        validCount = 2,
        preview = listOf(CodePreviewRow(2, "ARQ", "Arqueta", CodeGeometry.POINT, 0x1E88E5, 2)),
        invalid = listOf(InvalidRow(3, RowProblem.Code(CodeError.Empty))),
        invalidCount = 1,
        conflicts = MergeConflicts(listOf("far"), listOf("ARQ")),
        libraries = listOf(LibrarySummary("u1", "Mía", false, 2)),
    )

    @Test
    fun `S023 RNF-T8 la lista de bibliotecas tiene etiquetas y zonas de 56 dp`() {
        show { CodeLibraryListScreen(ThemeState.DEFAULT, {}, list, CodeLibraryListActions(), {}) }

        assertControls("lista")
    }

    @Test
    fun `S023 RNF-T8 una biblioteca editable tiene etiquetas y zonas de 56 dp`() {
        show { CodeLibraryScreen(ThemeState.DEFAULT, {}, library, CodeLibraryActions(), {}) }

        assertControls("biblioteca")
    }

    @Test
    fun `S023 RNF-T8 el editor de un codigo tiene etiquetas y zonas de 56 dp`() {
        show { CodeEditorScreen(ThemeState.DEFAULT, {}, editor, CodeEditorActions(), {}) }

        assertControls("editor")
    }

    @Test
    fun `S023 RNF-T8 el asistente de importacion tiene etiquetas y zonas de 56 dp`() {
        show { CodeImportScreen(ThemeState.DEFAULT, {}, wizard, CodeImportActions(), {}) }

        assertControls("importar")
    }

    @Test
    fun `S023 RNF-T8 el resultado de la importacion tiene etiquetas y zonas de 56 dp`() {
        show {
            CodeImportScreen(
                ThemeState.DEFAULT,
                {},
                wizard.copy(stage = CodeImportStage.RESULT, outcome = CodeImportOutcome("u1", 2, 1, 1)),
                CodeImportActions(),
                {},
            )
        }

        assertControls("resultado")
    }

    @Test
    @Config(qualifiers = "en")
    fun `S023 RNF-T7 en ingles la lista y el editor salen en ingles`() {
        show { CodeLibraryListScreen(ThemeState.DEFAULT, {}, list, CodeLibraryListActions(), {}) }

        compose.onNodeWithText("Code libraries").assertIsDisplayed()
        compose.onNodeWithText("New library").assertIsDisplayed()
        compose.onNodeWithText("15 codes · Default").assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "en")
    fun `S023 RNF-T7 en ingles el editor sale en ingles`() {
        show { CodeEditorScreen(ThemeState.DEFAULT, {}, editor, CodeEditorActions(), {}) }

        compose.onNodeWithText("Edit code").assertIsDisplayed()
        compose.onNodeWithText("Point").assertIsDisplayed()
        compose.onNodeWithText("Add attribute").assertExists()
    }

    @Test
    @Config(qualifiers = "en")
    fun `S023 RNF-T7 en ingles el asistente de importacion sale en ingles`() {
        show { CodeImportScreen(ThemeState.DEFAULT, {}, wizard, CodeImportActions(), {}) }

        compose.onNodeWithText("Preview").assertExists()
        compose.onNodeWithText("Line 3: the code is empty").assertExists()
        compose.onNodeWithText("Skip repeated codes").assertExists()
        compose.onNodeWithText("1 row discarded").assertExists()
    }
}
