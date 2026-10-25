package es.topophone.app.ui.points

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.core.designsystem.theme.TopoPhoneTheme
import es.topophone.core.model.point.ImportDecimal
import es.topophone.core.model.point.ImportEncoding
import es.topophone.core.model.point.ImportOptions
import es.topophone.core.model.point.ImportSeparator
import es.topophone.core.model.preferences.EffectiveTheme
import es.topophone.core.model.preferences.ThemeState
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** El asistente de importación: el fichero y la lectura (S028/RF-2 a RF-9). Interfaz en es-ES. */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "es-rES")
class PointImportScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val calls = mutableListOf<String>()
    private val actions = PointImportActions(
        onEncoding = { calls += "encoding:$it" },
        onSeparator = { calls += "separator:$it" },
        onDecimal = { calls += "decimal:$it" },
        onHasHeader = { calls += "header:$it" },
    )

    private val wizard = PointImportUiState(stage = ImportStage.WIZARD, fileName = "puntos.csv", options = ImportOptions(hasHeader = true))

    private fun show(state: PointImportUiState) {
        compose.setContent {
            TopoPhoneTheme(EffectiveTheme.LIGHT) { PointImportScreen(ThemeState.DEFAULT, {}, state, actions, onBack = { calls += "back" }) }
        }
    }

    @Test
    fun `S028 RF-4 se ve el fichero y se elige la codificacion`() {
        show(wizard)

        compose.onNodeWithText("Fichero: puntos.csv").assertIsDisplayed()
        compose.onNodeWithTag(importEncodingTag(ImportEncoding.UTF8)).assertIsSelected()
        compose.onNodeWithTag(importEncodingTag(ImportEncoding.WINDOWS_1252)).assertIsNotSelected().performClick()

        calls shouldBe listOf("encoding:WINDOWS_1252")
    }

    @Test
    fun `S028 RF-5 los cuatro separadores se ofrecen y se elige otro`() {
        show(wizard)

        ImportSeparator.entries.forEach { compose.onNodeWithTag(importSeparatorTag(it)).performScrollTo().assertIsDisplayed() }
        compose.onNodeWithTag(importSeparatorTag(ImportSeparator.COMMA)).assertIsSelected()
        compose.onNodeWithTag(importSeparatorTag(ImportSeparator.SPACES)).performClick()

        calls shouldBe listOf("separator:SPACES")
    }

    @Test
    fun `S028 RF-5 el separador detectado sale marcado`() {
        show(wizard.copy(options = wizard.options.copy(separator = ImportSeparator.SEMICOLON)))

        compose.onNodeWithTag(importSeparatorTag(ImportSeparator.SEMICOLON)).assertIsSelected()
        compose.onNodeWithTag(importSeparatorTag(ImportSeparator.COMMA)).assertIsNotSelected()
    }

    @Test
    fun `S028 RF-7 se elige el signo decimal`() {
        show(wizard)

        compose.onNodeWithTag(importDecimalTag(ImportDecimal.POINT)).performScrollTo().assertIsSelected()
        compose.onNodeWithTag(importDecimalTag(ImportDecimal.COMMA)).performScrollTo().performClick()

        calls shouldBe listOf("decimal:COMMA")
    }

    @Test
    fun `S028 RF-8 con la coma de separador y de decimal se avisa`() {
        show(wizard.copy(options = wizard.options.copy(separator = ImportSeparator.COMMA, decimal = ImportDecimal.COMMA)))

        compose.onNodeWithTag(IMPORT_COMMA_CONFLICT_TAG).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("El separador y el signo decimal no pueden ser los dos la coma.").assertIsDisplayed()
    }

    @Test
    fun `S028 RF-8 sin conflicto no hay aviso`() {
        show(wizard.copy(options = wizard.options.copy(separator = ImportSeparator.SEMICOLON, decimal = ImportDecimal.COMMA)))

        compose.onNodeWithTag(IMPORT_COMMA_CONFLICT_TAG).assertDoesNotExist()
    }

    @Test
    fun `S028 RF-9 la casilla de la cabecera se marca y se cambia`() {
        show(wizard)

        compose.onNodeWithTag(IMPORT_HEADER_TAG).performScrollTo().performClick()

        calls shouldBe listOf("header:false")
    }

    @Test
    fun `S028 RF-9 sin cabecera la casilla viene desmarcada y se puede marcar`() {
        show(wizard.copy(options = wizard.options.copy(hasHeader = false)))

        compose.onNodeWithTag(IMPORT_HEADER_TAG).performScrollTo().performClick()

        calls shouldBe listOf("header:true")
    }

    private fun problem(problem: ImportFileProblem) =
        PointImportUiState(stage = ImportStage.PROBLEM, fileName = "x.csv", fileProblem = problem)

    @Test
    fun `S028 RF-2 un fichero vacio no abre el asistente y lo dice`() {
        show(problem(ImportFileProblem.EMPTY))

        compose.onNodeWithText("El fichero está vacío.").assertIsDisplayed()
    }

    @Test
    fun `S028 RF-2 un fichero que no es texto no abre el asistente y lo dice`() {
        show(problem(ImportFileProblem.NOT_TEXT))

        compose.onNodeWithText("El fichero no es de texto, así que no se puede importar.").assertIsDisplayed()
    }

    @Test
    fun `S028 RF-2 un fichero sin dos columnas no abre el asistente y lo dice`() {
        show(problem(ImportFileProblem.NO_COLUMNS))

        compose.onNodeWithText("El fichero no tiene ninguna fila con al menos dos columnas.").assertIsDisplayed()
    }

    @Test
    fun `S028 RF-3 un fichero de mas de 20 MB o de 50000 filas dice el limite`() {
        show(problem(ImportFileProblem.TOO_BIG))
        compose.onNodeWithText("El fichero pasa del límite de 20 MB.").assertIsDisplayed()
    }

    @Test
    fun `S028 RF-3 un fichero de mas de 50000 filas dice el limite`() {
        show(problem(ImportFileProblem.TOO_MANY_ROWS))

        compose.onNodeWithText("El fichero pasa del límite de 50.000 filas.").assertIsDisplayed()
    }

    @Test
    fun `S028 RF-2 un fichero que no se puede leer lo dice`() {
        show(problem(ImportFileProblem.UNREADABLE))

        compose.onNodeWithText("No se ha podido leer el fichero.").assertIsDisplayed()
    }

    @Test
    fun `S028 RF-2 con un problema solo se ofrece cerrar y no hay opciones`() {
        show(problem(ImportFileProblem.EMPTY))

        compose.onNodeWithTag(importSeparatorTag(ImportSeparator.COMMA)).assertDoesNotExist()
        compose.onNodeWithText("Cerrar").performClick()

        calls shouldBe listOf("back")
    }

    @Test
    fun `S028 RF-21 mientras se lee el fichero se ve un aviso de carga`() {
        show(PointImportUiState(stage = ImportStage.LOADING, fileName = "x.csv"))

        compose.onNodeWithTag(IMPORT_LOADING_TAG).assertIsDisplayed()
        compose.onNodeWithText("Leyendo el fichero…").assertIsDisplayed()
    }
}
