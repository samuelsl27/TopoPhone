package es.topophone.app.ui.points

import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.core.designsystem.theme.TopoPhoneTheme
import es.topophone.core.model.point.ImportCounts
import es.topophone.core.model.point.ImportErrorReason
import es.topophone.core.model.point.ImportRowError
import es.topophone.core.model.preferences.EffectiveTheme
import es.topophone.core.model.preferences.ThemeState
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** El resultado de la importación, con el informe y deshacer (S028/RF-28, RF-29). Interfaz en es-ES. */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "es-rES")
class PointImportResultScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val calls = mutableListOf<String>()
    private val actions = PointImportActions(
        onUndo = { calls += "undo" },
        onSaveReport = { calls += "save" },
        onShareReport = { calls += "share" },
    )

    private val counts = ImportCounts(rows = 12, valid = 7, skipped = 2, renamed = 1, errors = 3)
    private val done = PointImportUiState(
        stage = ImportStage.RESULT,
        fileName = "puntos.csv",
        projectName = "Calle Mayor 12",
        counts = counts,
        errors = listOf(ImportRowError(3, ImportErrorReason.MISSING_NAME), ImportRowError(9, ImportErrorReason.NOT_A_NUMBER)),
        result = PointImportResult(counts, importId = "imp-1"),
    )

    private fun show(state: PointImportUiState = done) {
        compose.setContent {
            TopoPhoneTheme(EffectiveTheme.LIGHT) { PointImportScreen(ThemeState.DEFAULT, {}, state, actions, onBack = { calls += "back" }) }
        }
    }

    @Test
    fun `S028 RF-28 el resultado dice cuantos puntos se han importado, saltado, renombrado y con error`() {
        show()

        compose.onNodeWithText("Importación terminada").assertIsDisplayed()
        compose.onNodeWithText("Importados: 7").assertIsDisplayed()
        compose.onNodeWithText("Saltados por repetidos: 2").assertIsDisplayed()
        compose.onNodeWithText("Renombrados: 1").assertIsDisplayed()
        compose.onNodeWithText("Con error: 3").assertIsDisplayed()
    }

    @Test
    fun `S028 RF-28 TalkBack lee los cuatro recuentos juntos`() {
        show()

        compose.onNodeWithTag(IMPORT_RESULT_COUNTS_TAG)
            .assertContentDescriptionEquals("Importados: 7. Saltados por repetidos: 2. Renombrados: 1. Con error: 3.")
    }

    @Test
    fun `S028 RF-28 los recuentos a cero tambien se muestran`() {
        show(done.copy(result = PointImportResult(ImportCounts(rows = 1, valid = 1), "imp-1"), counts = ImportCounts(rows = 1, valid = 1)))

        compose.onNodeWithText("Saltados por repetidos: 0").assertIsDisplayed()
        compose.onNodeWithText("Renombrados: 0").assertIsDisplayed()
        compose.onNodeWithText("Con error: 0").assertIsDisplayed()
    }

    @Test
    fun `S028 RF-28 el informe de errores se abre desde el resultado`() {
        show()

        compose.onNodeWithText("Línea 3: falta el nombre").assertDoesNotExist()
        compose.onNodeWithTag(IMPORT_VIEW_REPORT_TAG).performScrollTo().performClick()

        compose.onNodeWithText("Línea 3: falta el nombre").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Línea 9: una coordenada no es un número").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag(IMPORT_REPORT_SAVE_TAG).performScrollTo().performClick()
        compose.onNodeWithTag(IMPORT_REPORT_SHARE_TAG).performScrollTo().performClick()
        calls shouldBe listOf("save", "share")
    }

    @Test
    fun `S028 RF-28 sin filas con error no hay informe que ver`() {
        val clean = ImportCounts(rows = 3, valid = 3)
        show(done.copy(counts = clean, errors = emptyList(), result = PointImportResult(clean, "imp-1")))

        compose.onNodeWithTag(IMPORT_VIEW_REPORT_TAG).assertDoesNotExist()
    }

    @Test
    fun `S028 RF-29 deshacer pide confirmacion antes de mover nada`() {
        show()

        compose.onNodeWithTag(IMPORT_UNDO_TAG).performScrollTo().performClick()

        compose.onNodeWithText("¿Deshacer la importación?").assertIsDisplayed()
        calls shouldBe emptyList()
    }

    @Test
    fun `S028 RF-29 al confirmar se deshace la importacion`() {
        show()

        compose.onNodeWithTag(IMPORT_UNDO_TAG).performScrollTo().performClick()
        compose.onNodeWithTag(IMPORT_UNDO_CONFIRM_TAG).performClick()

        calls shouldBe listOf("undo")
    }

    @Test
    fun `S028 RF-29 al cancelar la confirmacion no se deshace nada`() {
        show()

        compose.onNodeWithTag(IMPORT_UNDO_TAG).performScrollTo().performClick()
        compose.onNodeWithText("Cancelar").performClick()

        calls shouldBe emptyList()
        compose.onNodeWithText("¿Deshacer la importación?").assertDoesNotExist()
    }

    @Test
    fun `S028 RF-29 deshecha la importacion se dice cuantos puntos fueron a la papelera y ya no se ofrece deshacer`() {
        show(done.copy(result = PointImportResult(counts, "imp-1", undone = true, undoneCount = 6)))

        compose.onNodeWithText("Importación deshecha: 6 puntos pasan a la papelera.").assertIsDisplayed()
        compose.onNodeWithTag(IMPORT_UNDO_TAG).assertDoesNotExist()
    }

    @Test
    fun `S028 RF-29 con un solo punto se dice en singular`() {
        show(done.copy(result = PointImportResult(counts, "imp-1", undone = true, undoneCount = 1)))

        compose.onNodeWithText("Importación deshecha: 1 punto pasa a la papelera.").assertIsDisplayed()
    }

    @Test
    fun `S028 RF-29 si ya no queda ninguno de los puntos se dice que no hay nada que mover`() {
        show(done.copy(result = PointImportResult(counts, "imp-1", undone = true, undoneCount = 0)))

        compose.onNodeWithText("Importación deshecha: ningún punto seguía en el proyecto.").assertIsDisplayed()
    }

    @Test
    fun `S028 RF-29 si no se importo ningun punto no hay nada que deshacer`() {
        val none = ImportCounts(rows = 2, errors = 2)
        show(done.copy(counts = none, result = PointImportResult(none, "imp-1")))

        compose.onNodeWithTag(IMPORT_UNDO_TAG).assertDoesNotExist()
    }

    @Test
    fun `S028 RF-28 se cierra el resultado`() {
        show()

        compose.onNodeWithText("Cerrar").performScrollTo().performClick()

        calls shouldBe listOf("back")
    }
}
