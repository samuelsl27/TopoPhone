package es.topophone.app.ui.cad

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertContentDescriptionEquals
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
import es.topophone.app.ui.points.ReportOutcome
import es.topophone.core.designsystem.theme.TopoPhoneTheme
import es.topophone.core.model.cad.CadEntityKind
import es.topophone.core.model.cad.CadExtent
import es.topophone.core.model.cad.CadIgnored
import es.topophone.core.model.cad.CadIgnoredReason
import es.topophone.core.model.cad.CadImportOptions
import es.topophone.core.model.cad.CadImportSummary
import es.topophone.core.model.cad.CadSourceCrs
import es.topophone.core.model.preferences.EffectiveTheme
import es.topophone.core.model.preferences.ThemeState
import es.topophone.core.model.project.GeodeticDatum
import es.topophone.core.model.project.LinearUnit
import es.topophone.core.model.project.ProjectCrs
import es.topophone.data.cad.CadImportFailure
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** La pantalla de importar un DXF: configuración, avisos, progreso y resumen (S040/RF-6 a RF-10, RF-13, RF-18). Interfaz en es-ES. */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "es-rES")
class CadImportScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val calls = mutableListOf<String>()
    private val actions = CadImportActions(
        onSource = { calls += "source:$it" },
        onUnit = { calls += "unit:${it.name}" },
        onUseElevations = { calls += "elevations:$it" },
        onConfirmFar = { calls += "far" },
        onConfirmDuplicate = { calls += "duplicate" },
        onImport = { calls += "import" },
        onCancel = { calls += "cancel" },
        onSaveSummary = { calls += "save:$it" },
        onShareSummary = { calls += "share:$it" },
    )

    private val ready = CadImportUiState(
        stage = CadImportStage.CONFIG,
        fileName = "plano.dxf",
        projectName = "Calle Mayor 12",
        projectCrs = ProjectCrs.ETRS89_UTM30,
        elementCount = 1234,
        extent = CadExtent(440_000.0, 440_150.5, 4_474_000.0, 4_474_260.25),
        distanceKm = 0.4,
    )

    private fun show(state: CadImportUiState, outcome: ReportOutcome? = null) {
        compose.setContent {
            TopoPhoneTheme(EffectiveTheme.LIGHT) {
                CadImportScreen(ThemeState.DEFAULT, {}, state, actions, onBack = { calls += "back" }, summaryOutcome = outcome)
            }
        }
    }

    private fun tap(tag: String) = compose.onNodeWithTag(tag).performScrollTo().performClick()

    @Test
    fun `S040 RF-3 un DWG se explica con su version y que hay que convertirlo`() {
        show(
            CadImportUiState(
                stage = CadImportStage.PROBLEM,
                fileName = "obra.dwg",
                fileProblem = CadFileProblem.DWG,
                dwgVersion = "AC1032",
            ),
        )

        compose.onNodeWithTag(CAD_PROBLEM_TAG).assertIsDisplayed()
        compose.onNodeWithText("Es un DWG (versión AC1032)", substring = true).assertIsDisplayed()
        compose.onNodeWithText("conviértelo a DXF", substring = true).assertIsDisplayed()
        compose.onNodeWithText("obra.dwg").assertIsDisplayed()
    }

    @Test
    fun `S040 RF-4 y RF-5 cada motivo de rechazo tiene su texto`() {
        val cases = mapOf(
            CadFileProblem.EMPTY to "El fichero está vacío.",
            CadFileProblem.NOT_DXF to "El fichero no es un DXF.",
            CadFileProblem.BINARY_DXF to "Es un DXF binario",
            CadFileProblem.TOO_BIG to "pasa de 100 MB",
            CadFileProblem.UNREADABLE to "No se ha podido leer el fichero.",
            CadFileProblem.NO_ELEMENTS to "ningún punto ni línea",
        )
        val holder = mutableStateOf(CadImportUiState(stage = CadImportStage.PROBLEM))
        compose.setContent {
            TopoPhoneTheme(EffectiveTheme.LIGHT) { CadImportScreen(ThemeState.DEFAULT, {}, holder.value, actions, {}) }
        }
        cases.forEach { (problem, text) ->
            compose.runOnIdle { holder.value = CadImportUiState(stage = CadImportStage.PROBLEM, fileProblem = problem) }
            compose.onNodeWithText(text, substring = true).assertIsDisplayed()
        }
    }

    @Test
    fun `S040 RF-3 un DWG sin version conocida sigue explicando que no se lee`() {
        show(CadImportUiState(stage = CadImportStage.PROBLEM, fileProblem = CadFileProblem.DWG))

        compose.onNodeWithText("Los DWG no se leen", substring = true).assertIsDisplayed()
    }

    @Test
    fun `S040 RF-11 mientras carga se ve el progreso de la lectura`() {
        show(CadImportUiState(stage = CadImportStage.LOADING))

        compose.onNodeWithTag(CAD_LOADING_TAG).assertIsDisplayed()
    }

    @Test
    fun `S040 RF-9 mientras se explora no se puede importar`() {
        show(ready.copy(scanning = true, elementCount = 0, extent = null))

        compose.onNodeWithTag(CAD_SCANNING_TAG).assertIsDisplayed()
        compose.onNodeWithTag(CAD_IMPORT_BUTTON_TAG).performScrollTo().assertIsNotEnabled()
    }

    @Test
    fun `S040 RF-6 el sistema del proyecto viene elegido y Otro sistema ofrece los UTM de la app`() {
        show(ready)

        compose.onNodeWithTag(CAD_CRS_PROJECT_TAG).performScrollTo().assertIsSelected()
        tap(CAD_CRS_OTHER_TAG)
        calls.single() shouldBe "source:${CadSourceCrs.Utm(GeodeticDatum.ETRS89, 29)}"
    }

    @Test
    fun `S040 RF-6 con otro sistema se elige cual de los disponibles`() {
        show(ready.copy(options = CadImportOptions(source = CadSourceCrs.Utm(GeodeticDatum.ED50, 30))))

        compose.onNodeWithTag(CAD_CRS_OTHER_TAG).performScrollTo().assertIsSelected()
        compose.onNodeWithTag(cadSourceTag(ProjectCrs.ED50_UTM30)).performScrollTo().assertIsSelected()
        ProjectCrs.entries.filter { it.isUtm }.forEach { compose.onNodeWithTag(cadSourceTag(it)).performScrollTo().assertIsDisplayed() }
        tap(cadSourceTag(ProjectCrs.REGCAN95_UTM28))
        calls.single() shouldBe "source:${CadSourceCrs.Utm(GeodeticDatum.REGCAN95, 28)}"
    }

    @Test
    fun `S040 RF-7 las tres unidades se ofrecen y la elegida va marcada`() {
        show(ready.copy(options = CadImportOptions(unit = LinearUnit.INTERNATIONAL_FOOT)))

        compose.onNodeWithTag(cadUnitTag(LinearUnit.INTERNATIONAL_FOOT)).performScrollTo().assertIsSelected()
        tap(cadUnitTag(LinearUnit.US_SURVEY_FOOT))
        tap(cadUnitTag(LinearUnit.METRE))
        calls shouldBe listOf("unit:US_SURVEY_FOOT", "unit:METRE")
    }

    @Test
    fun `S040 RF-8 las cotas del dibujo se usan salvo que se diga que no`() {
        show(ready)

        compose.onNodeWithText("Usar las cotas del dibujo").performScrollTo().assertIsDisplayed()
        tap(CAD_ELEVATIONS_TAG)
        calls.single() shouldBe "elevations:false"
    }

    @Test
    fun `S040 RF-9 la extension se da en Este y Norte con la distancia al proyecto`() {
        show(ready)

        compose.onNodeWithTag(CAD_EXTENT_TAG).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Este: de", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Norte: de", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Distancia al proyecto: 0,4 km").assertIsDisplayed()
        compose.onNodeWithText("440.000,0", substring = true).assertIsDisplayed()
    }

    @Test
    fun `S040 RF-17 si el dibujo no cabe se dice y no se puede importar`() {
        show(ready.copy(outsideArea = true, extent = null))

        compose.onNodeWithTag(CAD_OUTSIDE_TAG).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag(CAD_IMPORT_BUTTON_TAG).performScrollTo().assertIsNotEnabled()
    }

    @Test
    fun `S040 RF-10 un dibujo lejano avisa y no se importa hasta confirmar`() {
        show(ready.copy(farWarning = true, distanceKm = 250.0))

        compose.onNodeWithTag(CAD_FAR_TAG).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag(CAD_IMPORT_BUTTON_TAG).performScrollTo().assertIsNotEnabled()
        tap(CAD_FAR_CONFIRM_TAG)
        calls.single() shouldBe "far"
    }

    @Test
    fun `S040 RF-10 confirmado el aviso se puede importar`() {
        show(ready.copy(farWarning = true, farConfirmed = true))

        compose.onNodeWithTag(CAD_IMPORT_BUTTON_TAG).performScrollTo().assertIsEnabled().performClick()
        calls.single() shouldBe "import"
    }

    @Test
    fun `S040 RF-20 un nombre ya importado avisa y pide confirmar`() {
        show(ready.copy(duplicateName = true))

        compose.onNodeWithTag(CAD_DUPLICATE_TAG).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag(CAD_IMPORT_BUTTON_TAG).performScrollTo().assertIsNotEnabled()
        tap(CAD_DUPLICATE_CONFIRM_TAG)
        calls.single() shouldBe "duplicate"
    }

    @Test
    fun `S040 RF-13 un fichero cortado dice que se importara lo que se pueda leer`() {
        show(ready.copy(scanComplete = false))

        compose.onNodeWithTag(CAD_INCOMPLETE_TAG).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag(CAD_IMPORT_BUTTON_TAG).performScrollTo().assertIsEnabled()
    }

    @Test
    fun `S040 RF-11 al importar se ve el progreso y se puede cancelar`() {
        show(ready.copy(stage = CadImportStage.IMPORTING, importBytes = 500, totalBytes = 1000))

        compose.onNodeWithTag(CAD_PROGRESS_TAG).assertIsDisplayed()
        compose.onNodeWithText("Importando el dibujo…").assertIsDisplayed()
        compose.onNodeWithTag(CAD_CANCEL_TAG).performClick()
        calls.single() shouldBe "cancel"
    }

    @Test
    fun `S040 RF-11 sin saber el tamano el progreso es indeterminado y tambien se puede cancelar`() {
        show(ready.copy(stage = CadImportStage.IMPORTING, totalBytes = null))

        compose.onNodeWithTag(CAD_PROGRESS_TAG).assertIsDisplayed()
        compose.onNodeWithTag(CAD_CANCEL_TAG).assertIsEnabled()
    }

    @Test
    fun `S040 RF-12 tras cancelar o fallar se dice que no se ha guardado nada`() {
        val holder = mutableStateOf(ready.copy(cancelled = true))
        compose.setContent {
            TopoPhoneTheme(EffectiveTheme.LIGHT) { CadImportScreen(ThemeState.DEFAULT, {}, holder.value, actions, {}) }
        }
        compose.onNodeWithText("Importación cancelada: no se ha guardado nada.").performScrollTo().assertIsDisplayed()

        compose.runOnIdle { holder.value = ready.copy(failure = CadImportFailure.WRITE_ERROR) }

        compose.onNodeWithText("No se ha podido escribir en el móvil", substring = true).performScrollTo().assertIsDisplayed()
    }

    private val summary = CadImportSummary(
        imported = mapOf(CadEntityKind.LINE to 10, CadEntityKind.POINT to 5),
        ignored = mapOf(CadIgnored(CadIgnoredReason.UNSUPPORTED_TYPE, "TEXT") to 2, CadIgnored(CadIgnoredReason.OUTSIDE_CRS_AREA) to 1),
        layers = 4,
        incomplete = false,
    )
    private val done = ready.copy(stage = CadImportStage.RESULT, result = CadImportOutcome("d1", summary))

    @Test
    fun `S040 RF-18 el resumen cuenta lo importado por tipo, lo ignorado por motivo y las capas`() {
        show(done)

        compose.onNodeWithText("Dibujo importado").assertIsDisplayed()
        compose.onNodeWithText("Elementos importados: 15").assertIsDisplayed()
        compose.onNodeWithText("Puntos: 5").assertIsDisplayed()
        compose.onNodeWithText("Líneas: 10").assertIsDisplayed()
        compose.onNodeWithText("Elementos ignorados: 3").assertIsDisplayed()
        compose.onNodeWithText("Tipo que no se importa: TEXT: 2").assertIsDisplayed()
        compose.onNodeWithText("Fuera del ámbito del sistema: 1").assertIsDisplayed()
        compose.onNodeWithText("Capas: 4").assertIsDisplayed()
    }

    @Test
    fun `S040 RF-18 TalkBack lee los tres recuentos juntos`() {
        show(done)

        compose.onNodeWithTag(CAD_RESULT_TAG).assertContentDescriptionEquals("Importados: 15. Ignorados: 3. Capas: 4.")
    }

    @Test
    fun `S040 RF-13 un dibujo incompleto lo dice en el resumen`() {
        show(done.copy(result = CadImportOutcome("d1", summary.copy(incomplete = true))))

        compose.onNodeWithTag(CAD_RESULT_INCOMPLETE_TAG).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `S040 RF-18 el resumen se puede guardar o compartir y se avisa del resultado`() {
        show(done, outcome = ReportOutcome.SAVED)

        tap(CAD_SUMMARY_SAVE_TAG)
        tap(CAD_SUMMARY_SHARE_TAG)
        calls shouldBe listOf("save:resumen-dxf.txt", "share:resumen-dxf.txt")
        compose.onNodeWithText("Resumen guardado.").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `S040 RF-18 si no se pudo guardar el resumen se dice`() {
        show(done, outcome = ReportOutcome.FAILED)

        compose.onNodeWithText("No se ha podido guardar el resumen.").performScrollTo().assertIsDisplayed()
    }
}
