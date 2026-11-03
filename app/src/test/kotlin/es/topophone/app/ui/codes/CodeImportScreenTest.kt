package es.topophone.app.ui.codes

import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.app.R
import es.topophone.app.ui.points.ImportFileProblem
import es.topophone.app.ui.text
import es.topophone.core.designsystem.component.MinTouchTarget
import es.topophone.core.designsystem.theme.TopoPhoneTheme
import es.topophone.core.formats.codes.InvalidRow
import es.topophone.core.formats.codes.RowProblem
import es.topophone.core.model.codes.CodeError
import es.topophone.core.model.codes.CodeGeometry
import es.topophone.core.model.codes.DuplicatePolicy
import es.topophone.core.model.codes.MergeConflicts
import es.topophone.core.model.point.ImportDecimal
import es.topophone.core.model.point.ImportEncoding
import es.topophone.core.model.point.ImportSeparator
import es.topophone.core.model.preferences.EffectiveTheme
import es.topophone.core.model.preferences.ThemeState
import es.topophone.data.codes.LibrarySummary
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** El asistente de importación de bibliotecas: opciones, vista previa, filas descartadas, repetidos y resultado (S023/RF-12 a RF-16). */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "es-rES")
class CodeImportScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val wizard = CodeImportUiState(
        stage = CodeImportStage.WIZARD,
        fileName = "mis-codigos.csv",
        validCount = 2,
        preview = listOf(
            CodePreviewRow(2, "ARQ", "Arqueta", CodeGeometry.POINT, 0x1E88E5, 2),
            CodePreviewRow(3, "BOR", "", CodeGeometry.LINE, 0xE64A19, 0),
        ),
        libraries = listOf(LibrarySummary("u1", "Mía", false, 3)),
    )

    private fun show(state: CodeImportUiState, actions: CodeImportActions = CodeImportActions()) {
        compose.setContent {
            TopoPhoneTheme(EffectiveTheme.LIGHT) { CodeImportScreen(ThemeState.DEFAULT, {}, state, actions, {}) }
        }
    }

    @Test
    fun `S023 RF-12 la vista previa enseña cada codigo con su color, geometria y atributos en texto`() {
        show(wizard.copy(analyzing = false))

        compose.onNodeWithText("2 códigos válidos").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Arqueta · Punto · 2 atributos").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Línea · 0 atributos").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("ARQ").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `S023 RF-12 la codificacion, el separador y el decimal se eligen entre opciones con texto`() {
        val calls = mutableListOf<String>()
        show(
            wizard.copy(encoding = ImportEncoding.WINDOWS_1252, separator = ImportSeparator.SEMICOLON, decimal = ImportDecimal.COMMA),
            CodeImportActions(
                onEncoding = { calls += "enc:${it.name}" },
                onSeparator = { calls += "sep:${it.name}" },
                onDecimal = { calls += "dec:${it.name}" },
            ),
        )

        compose.onNodeWithTag(codeImportEncodingTag(ImportEncoding.WINDOWS_1252)).assertIsSelected()
        compose.onNodeWithTag(codeImportSeparatorTag(ImportSeparator.SEMICOLON)).assertIsSelected()
        compose.onNodeWithTag(codeImportDecimalTag(ImportDecimal.COMMA)).assertIsSelected()
        compose.onNodeWithTag(codeImportEncodingTag(ImportEncoding.UTF8)).performClick()
        compose.onNodeWithTag(codeImportSeparatorTag(ImportSeparator.TAB)).performClick()
        compose.onNodeWithTag(codeImportDecimalTag(ImportDecimal.POINT)).performClick()

        calls shouldBe listOf("enc:UTF8", "sep:TAB", "dec:POINT")
    }

    @Test
    fun `S023 RF-12 con coma como separador y como decimal se avisa y no se puede importar`() {
        show(wizard.copy(separator = ImportSeparator.COMMA, decimal = ImportDecimal.COMMA))

        compose.onNodeWithTag(CODE_IMPORT_DECIMAL_CONFLICT_TAG).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag(CODE_IMPORT_CONFIRM_TAG).performScrollTo().assertIsNotEnabled()
    }

    @Test
    fun `S023 RF-13 sin la columna Code se dice por que y no se puede importar`() {
        show(wizard.copy(missingCode = true, validCount = 0, preview = emptyList()))

        compose.onNodeWithTag(CODE_IMPORT_MISSING_TAG).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag(CODE_IMPORT_PREVIEW_TAG).assertDoesNotExist()
        compose.onNodeWithTag(CODE_IMPORT_CONFIRM_TAG).performScrollTo().assertIsNotEnabled()
    }

    @Test
    fun `S023 RF-15 las filas descartadas salen con su linea y su motivo`() {
        show(
            wizard.copy(
                invalidCount = 3,
                invalid = listOf(
                    InvalidRow(3, RowProblem.Code(CodeError.Empty)),
                    InvalidRow(5, RowProblem.UnknownType("Arco")),
                    InvalidRow(7, RowProblem.Attribute(2, CodeError.DefaultValue(0))),
                ),
            ),
        )

        compose.onNodeWithText("3 filas descartadas").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Línea 3: el código está vacío").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Línea 5: tipo desconocido «Arco»").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Línea 7: atributo 2: el valor por defecto no vale para su tipo").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `S023 RF-15 si hay mas filas descartadas de las que se listan se dice cuantas faltan`() {
        show(wizard.copy(invalidCount = 1_200, invalid = List(1_000) { InvalidRow(it + 2, RowProblem.UnknownColor("x")) }))

        compose.onNodeWithText("… y 200 más").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `S023 RF-16 los repetidos se listan y se elige entre omitirlos y reemplazarlos`() {
        val policies = mutableListOf<DuplicatePolicy>()
        show(
            wizard.copy(
                conflicts = MergeConflicts(inFile = listOf("far"), withLibrary = listOf("ARQ", "Árbol")),
                policy = DuplicatePolicy.SKIP,
            ),
            CodeImportActions(onPolicy = { policies += it }),
        )

        compose.onNodeWithText("Ya están en la biblioteca de destino: ARQ, Árbol").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Repetidos en el propio fichero: far").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag(codeImportPolicyTag(DuplicatePolicy.SKIP)).assertIsSelected()
        compose.onNodeWithTag(codeImportPolicyTag(DuplicatePolicy.REPLACE)).performScrollTo().performClick()

        policies shouldBe listOf(DuplicatePolicy.REPLACE)
    }

    @Test
    fun `S023 RF-16 sin repetidos no aparece la eleccion`() {
        show(wizard)

        compose.onNodeWithTag(CODE_IMPORT_CONFLICTS_TAG).assertDoesNotExist()
    }

    @Test
    fun `S023 RF-14 el destino es una biblioteca nueva o una del usuario`() {
        val targets = mutableListOf<CodeImportTarget>()
        show(wizard, CodeImportActions(onTarget = { targets += it }))

        compose.onNodeWithTag(CODE_IMPORT_TARGET_NEW_TAG).assertIsSelected()
        compose.onNodeWithTag(codeImportTargetTag("u1")).performScrollTo().performClick()

        targets shouldBe listOf(CodeImportTarget.Existing("u1"))
    }

    @Test
    fun `S023 RF-14 importar solo se puede con codigos validos y confirma`() {
        var confirmed = 0
        show(wizard, CodeImportActions(onConfirm = { confirmed++ }))

        compose.onNodeWithTag(CODE_IMPORT_CONFIRM_TAG).performScrollTo().assertIsEnabled().performClick()

        confirmed shouldBe 1
    }

    @Test
    fun `S023 RF-14 si no se pudo escribir se dice que no se ha importado nada`() {
        show(wizard.copy(writeFailed = true))

        compose.onNodeWithTag(CODE_IMPORT_FAILED_TAG).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `S023 RF-14 el resultado cuenta los codigos que entraron, los repetidos y las filas descartadas`() {
        var opened: String? = null
        show(
            wizard.copy(stage = CodeImportStage.RESULT, outcome = CodeImportOutcome("l1", entered = 3, skipped = 1, invalid = 2)),
            CodeImportActions(onOpenLibrary = { opened = it }),
        )

        compose.onNodeWithText("Se han importado 3 códigos.").assertIsDisplayed()
        compose.onNodeWithText("1 código del fichero no ha entrado por estar repetido.").assertIsDisplayed()
        compose.onNodeWithText("2 filas se han descartado.").assertIsDisplayed()
        compose.onNodeWithTag(CODE_IMPORT_OPEN_TAG).performClick()

        opened shouldBe "l1"
    }

    @Test
    fun `S023 RF-12 un fichero con problema lo dice con texto`() {
        show(CodeImportUiState(stage = CodeImportStage.PROBLEM, fileName = "x.bin", fileProblem = ImportFileProblem.NOT_TEXT))

        compose.onNodeWithText(text(R.string.import_problem_not_text)).assertIsDisplayed()
    }

    @Test
    fun `S023 RNF-T8 los controles miden al menos 56 dp`() {
        show(wizard)

        listOf(
            codeImportEncodingTag(ImportEncoding.UTF8),
            codeImportSeparatorTag(ImportSeparator.COMMA),
            codeImportDecimalTag(ImportDecimal.POINT),
            CODE_IMPORT_TARGET_NEW_TAG,
            CODE_IMPORT_CONFIRM_TAG,
        ).forEach { compose.onNodeWithTag(it).performScrollTo().assertHeightIsAtLeast(MinTouchTarget) }
    }
}
