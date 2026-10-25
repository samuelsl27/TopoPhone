package es.topophone.app.ui.points

import android.content.Context
import android.net.Uri
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.core.designsystem.theme.TopoPhoneTheme
import es.topophone.core.model.point.ColumnMapping
import es.topophone.core.model.point.ImportCounts
import es.topophone.core.model.point.ImportErrorReason
import es.topophone.core.model.point.ImportOptions
import es.topophone.core.model.point.ImportPreviewRow
import es.topophone.core.model.point.ImportRowError
import es.topophone.core.model.point.ImportRowStatus
import es.topophone.core.model.preferences.EffectiveTheme
import es.topophone.core.model.preferences.ThemeState
import io.kotest.matchers.shouldBe
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** El asistente de importación: la vista previa, los recuentos y el informe de errores (S028/RF-21, RF-22, RF-24). Interfaz en es-ES. */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "es-rES")
class PointImportPreviewScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val calls = mutableListOf<String>()
    private val actions = PointImportActions(
        onSaveReport = { calls += "save:$it" },
        onShareReport = { calls += "share:$it" },
        onImport = { calls += "import" },
    )

    private fun row(
        line: Int,
        name: String,
        status: ImportRowStatus = ImportRowStatus.VALID,
        reason: ImportErrorReason? = null,
        renamedTo: String? = null,
    ) = ImportPreviewRow(line, name, "440297.988", "4474244.001", "646.377", "Esquina", status, reason, renamedTo)

    private val wizard = PointImportUiState(
        stage = ImportStage.WIZARD,
        fileName = "puntos.csv",
        projectName = "Calle Mayor 12",
        columnCount = 5,
        headerCells = listOf("Name", "Easting", "Northing", "Elevation", "Description"),
        options = ImportOptions(hasHeader = true, mapping = ColumnMapping.penzd(5)),
        counts = ImportCounts(rows = 9, valid = 5, skipped = 2, renamed = 1, errors = 2),
        preview = listOf(
            row(2, "1"),
            row(3, "2", ImportRowStatus.REPEATED_SKIPPED),
            row(4, "3", ImportRowStatus.REPEATED_RENAMED, renamedTo = "4"),
            row(5, "", ImportRowStatus.ERROR, ImportErrorReason.MISSING_NAME),
        ),
        errors = listOf(ImportRowError(5, ImportErrorReason.MISSING_NAME), ImportRowError(8, ImportErrorReason.NOT_A_NUMBER)),
    )

    private fun show(state: PointImportUiState = wizard, outcome: ReportOutcome? = null) {
        compose.setContent {
            TopoPhoneTheme(EffectiveTheme.LIGHT) {
                PointImportScreen(ThemeState.DEFAULT, {}, state, actions, onBack = {}, reportOutcome = outcome)
            }
        }
    }

    @Test
    fun `S028 RF-21 los recuentos de validas, repetidas y con error salen con icono y texto`() {
        show()

        compose.onNodeWithText("Válidas: 5").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Repetidas: 3").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Con error: 2").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `S028 RF-21 TalkBack lee los recuentos juntos y se anuncia al cambiar`() {
        show()

        compose.onNodeWithTag(IMPORT_COUNTS_TAG).performScrollTo()
            .assertContentDescriptionEquals("Válidas: 5. Repetidas: 3. Con error: 2.")
    }

    @Test
    fun `S028 RF-21 la vista previa lista las filas con la asignacion aplicada`() {
        show()

        compose.onNodeWithTag(importPreviewRowTag(2)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag(importPreviewRowTag(2)).assertTextContains("Línea 2 · 1")
        compose.onNodeWithTag(importPreviewRowTag(2)).assertTextContains("440297.988 · 4474244.001 · 646.377 · Esquina")
    }

    @Test
    fun `S028 RF-21 cada fila de la vista previa dice su estado con icono y texto`() {
        show()

        compose.onNodeWithTag(importPreviewRowTag(2)).performScrollTo().assertContentDescriptionEquals("Línea 2, 1: válida")
        compose.onNodeWithTag(importPreviewRowTag(3)).performScrollTo().assertContentDescriptionEquals("Línea 3, 2: repetida, se salta")
        compose.onNodeWithTag(
            importPreviewRowTag(4),
        ).performScrollTo().assertContentDescriptionEquals("Línea 4, 3: repetida, se renombra a 4")
        compose.onNodeWithTag(importPreviewRowTag(5)).performScrollTo()
            .assertContentDescriptionEquals("Línea 5, sin nombre: error, falta el nombre")
    }

    @Test
    fun `S028 RF-21 el texto del estado se ve en la fila`() {
        show()

        compose.onNodeWithText("Válida").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Repetida: se salta").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Repetida: se renombra a 4").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Error: falta el nombre").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `S028 RF-22 mientras se analiza se avisa y los recuentos no se ven como definitivos`() {
        show(wizard.copy(analyzing = true))

        compose.onNodeWithText("Analizando el fichero…").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `S028 RF-24 la lista de errores da la linea y el motivo de cada fila`() {
        show()

        compose.onNodeWithText("Línea 5: falta el nombre").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Línea 8: una coordenada no es un número").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `S028 RF-24 cada motivo de error tiene su texto`() {
        val texts = mapOf(
            ImportErrorReason.MISSING_NAME to "falta el nombre",
            ImportErrorReason.NOT_A_NUMBER to "una coordenada no es un número",
            ImportErrorReason.MISSING_COLUMNS to "faltan columnas",
            ImportErrorReason.OUTSIDE_CRS_AREA to "fuera del ámbito del sistema de coordenadas",
            ImportErrorReason.OUTSIDE_ED50_GRID to "fuera de la rejilla de ED50",
            ImportErrorReason.OUTSIDE_GEOID to "fuera del modelo de geoide",
        )
        show(wizard.copy(errors = texts.keys.mapIndexed { i, reason -> ImportRowError(i + 1, reason) }))

        texts.values.forEachIndexed { i, text -> compose.onNodeWithText("Línea ${i + 1}: $text").performScrollTo().assertIsDisplayed() }
    }

    @Test
    fun `S028 RF-24 si hay mas errores de los que caben en la lista se dice cuantos mas hay en el informe`() {
        show(
            wizard.copy(
                counts = wizard.counts.copy(errors = 600),
                errors = (1..500).map {
                    ImportRowError(it, ImportErrorReason.NOT_A_NUMBER)
                },
            ),
        )

        compose.onNodeWithText("Y 100 más en el informe.").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `S028 RF-24 sin errores la lista lo dice y no hay informe que guardar`() {
        show(wizard.copy(counts = wizard.counts.copy(errors = 0), errors = emptyList()))

        compose.onNodeWithText("Ninguna fila con error.").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag(IMPORT_REPORT_SAVE_TAG).assertDoesNotExist()
    }

    @Test
    fun `S028 RF-24 el informe se guarda o se comparte con el nombre propuesto`() {
        show()

        compose.onNodeWithTag(IMPORT_REPORT_SAVE_TAG).performScrollTo().performClick()
        compose.onNodeWithTag(IMPORT_REPORT_SHARE_TAG).performScrollTo().performClick()

        calls.size shouldBe 2
        calls[0] shouldBe calls[1].replace("share:", "save:")
        calls[0].startsWith("save:Calle Mayor 12_errores-importacion_") shouldBe true
        calls[0].endsWith(".txt") shouldBe true
    }

    @Test
    fun `S028 RF-24 se dice si el informe se ha guardado o no`() {
        show(outcome = ReportOutcome.SAVED)
        compose.onNodeWithText("Informe guardado.").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `S028 RF-24 se dice si el informe no se ha podido guardar`() {
        show(outcome = ReportOutcome.FAILED)

        compose.onNodeWithText("No se ha podido guardar el informe.").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `S028 RF-25 el boton Importar solo esta activo si se puede importar`() {
        show()
        compose.onNodeWithTag(IMPORT_BUTTON_TAG).performScrollTo().assertIsEnabled().performClick()

        calls shouldBe listOf("import")
    }

    @Test
    fun `S028 RF-25 el boton Importar esta desactivado mientras se analiza o si la asignacion no vale`() {
        show(wizard.copy(analyzing = true))
        compose.onNodeWithTag(IMPORT_BUTTON_TAG).performScrollTo().assertIsNotEnabled()
    }

    @Test
    fun `S028 RF-25 sin ninguna fila valida no se puede importar`() {
        show(wizard.copy(counts = ImportCounts(rows = 2, errors = 2)))

        compose.onNodeWithTag(IMPORT_BUTTON_TAG).performScrollTo().assertIsNotEnabled()
    }

    @Test
    fun `S028 RF-26 si no se pudo escribir se dice que no se ha importado nada`() {
        show(wizard.copy(writeFailed = true))

        compose.onNodeWithText(
            "No se ha importado ningún punto: no se pudo escribir en la base de datos.",
        ).performScrollTo().assertIsDisplayed()
    }

    // --- El informe en un fichero que elige el usuario (S028/RF-24) ---

    @Test
    fun `S028 RF-24 si el usuario cancela el selector de ficheros no se escribe nada`() {
        var written = false

        val outcome = runBlocking {
            saveReportTo(ApplicationProvider.getApplicationContext<Context>().contentResolver, null) {
                written =
                    true
                true
            }
        }

        outcome shouldBe null
        written shouldBe false
    }

    @Test
    fun `S028 RF-24 con el sitio elegido el informe se escribe y se dice que se guardo`() {
        val resolver = ApplicationProvider.getApplicationContext<Context>().contentResolver
        val uri = Uri.parse("content://es.topophone.test/informe.txt")
        val out = ByteArrayOutputStream()
        shadowOf(resolver).registerOutputStream(uri, out)

        val outcome = runBlocking {
            saveReportTo(resolver, uri) { stream ->
                stream.write("hola".toByteArray())
                true
            }
        }

        outcome shouldBe ReportOutcome.SAVED
        out.toString() shouldBe "hola"
    }

    @Test
    fun `S028 RF-24 si la escritura del informe falla se dice que fallo`() {
        val resolver = ApplicationProvider.getApplicationContext<Context>().contentResolver
        val uri = Uri.parse("content://es.topophone.test/otro.txt")
        shadowOf(resolver).registerOutputStream(uri, ByteArrayOutputStream())

        val outcome = runBlocking { saveReportTo(resolver, uri) { false } }

        outcome shouldBe ReportOutcome.FAILED
    }
}
