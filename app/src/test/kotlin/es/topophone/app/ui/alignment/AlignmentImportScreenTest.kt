package es.topophone.app.ui.alignment

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.core.designsystem.theme.TopoPhoneTheme
import es.topophone.core.formats.landxml.LandXmlAlignmentInfo
import es.topophone.core.model.alignment.AlignmentIssue
import es.topophone.core.model.alignment.ProfileIssue
import es.topophone.core.model.alignment.ProfileOmission
import es.topophone.core.model.cad.CadSourceCrs
import es.topophone.core.model.preferences.EffectiveTheme
import es.topophone.core.model.preferences.ThemeState
import es.topophone.core.model.project.GeodeticDatum
import es.topophone.core.model.project.LinearUnit
import es.topophone.data.alignment.AlignmentImportFailure
import es.topophone.data.alignment.AlignmentImportSummary
import es.topophone.data.alignment.IgnoredAlignment
import es.topophone.data.alignment.ImportedAlignment
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** La pantalla de importar los ejes de un LandXML: problemas, lista marcable, sistema, unidad, progreso y resumen (S049/RF-2, RF-3, RF-11, RF-13). */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "es-rES")
class AlignmentImportScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val alignments = listOf(
        LandXmlAlignmentInfo(0, "Eje A", 420.0, 0.0, 5, 1, false),
        LandXmlAlignmentInfo(1, "Eje B", 380.0, 1000.5, 8, 1, false),
        LandXmlAlignmentInfo(2, "Eje C", 200.0, 0.0, 1, 0, false),
        LandXmlAlignmentInfo(3, "Eje D", 90.0, 0.0, 1, 0, true),
    )

    private fun config(selected: Set<Int> = setOf(0, 1, 2), scanning: Boolean = false) = AlignmentImportUiState(
        stage = AlignmentImportStage.CONFIG,
        fileName = "ejes.xml",
        projectName = "Camino de la Dehesa",
        alignments = if (scanning) emptyList() else alignments,
        selected = selected,
        scanning = scanning,
        totalBytes = 1000,
        bytesRead = 400,
    )

    private fun show(state: AlignmentImportUiState, actions: AlignmentImportActions = AlignmentImportActions(), onBack: () -> Unit = {}) {
        compose.setContent {
            TopoPhoneTheme(EffectiveTheme.LIGHT) {
                AlignmentImportScreen(ThemeState.DEFAULT, {}, state, actions, onBack)
            }
        }
    }

    @Test
    fun `S049 RF-3 cada problema del fichero se dice con su texto`() {
        val cases = listOf(
            AlignmentFileProblem.EMPTY to "El fichero está vacío.",
            AlignmentFileProblem.TOO_BIG to "pasa de 100 MB",
            AlignmentFileProblem.NOT_XML to "no es un XML válido",
            AlignmentFileProblem.NOT_LANDXML to "no es un LandXML",
            AlignmentFileProblem.NO_ALIGNMENT to "no contiene ninguna alineación",
            AlignmentFileProblem.UNREADABLE to "No se puede leer",
        )
        val state = mutableStateOf(AlignmentImportUiState(stage = AlignmentImportStage.PROBLEM, fileName = "x.xml"))
        compose.setContent {
            TopoPhoneTheme(EffectiveTheme.LIGHT) {
                AlignmentImportScreen(ThemeState.DEFAULT, {}, state.value, AlignmentImportActions(), {})
            }
        }
        for ((problem, text) in cases) {
            state.value = state.value.copy(fileProblem = problem)
            compose.waitForIdle()
            compose.onNodeWithTag(ALIGNMENT_PROBLEM_TAG).assertIsDisplayed()
            compose.onNodeWithText(text, substring = true).assertIsDisplayed()
        }
    }

    @Test
    fun `S049 RF-3 una version no admitida se dice con su numero`() {
        show(
            AlignmentImportUiState(
                stage = AlignmentImportStage.PROBLEM,
                fileProblem = AlignmentFileProblem.UNSUPPORTED_VERSION,
                version = "2.0",
            ),
        )

        compose.onNodeWithText("versión 2.0", substring = true).assertIsDisplayed()
    }

    @Test
    fun `S049 RF-2 la lista muestra el nombre la longitud el PK inicial y si lleva rasante`() {
        show(config())

        compose.onNodeWithText("Eje A · 420,0 m · PK inicial 0+000,00").assertIsDisplayed()
        compose.onNodeWithText("Eje B · 380,0 m · PK inicial 1+000,50").assertIsDisplayed()
        compose.onAllNodesWithText("con rasante").fetchSemanticsNodes().size shouldBe 2
        compose.onAllNodesWithText("sin rasante").fetchSemanticsNodes().size shouldBe 1
        compose.onNodeWithText("con ecuaciones de PK", substring = true).assertExists()
    }

    @Test
    fun `S049 RF-2 tocar una alineacion la marca o desmarca`() {
        var toggled = -1
        show(config(), AlignmentImportActions(onToggle = { toggled = it }))

        compose.onNodeWithTag(alignmentChoiceTag(1)).performScrollTo().performClick()

        toggled shouldBe 1
    }

    @Test
    fun `S049 RF-2 el boton Importar solo se activa con alguna alineacion marcada`() {
        show(config(selected = emptySet()))
        compose.onNodeWithTag(ALIGNMENT_IMPORT_BUTTON_TAG).performScrollTo().assertIsNotEnabled()
    }

    @Test
    fun `S049 RF-2 con alineaciones marcadas el boton importa`() {
        var imported = 0
        show(config(), AlignmentImportActions(onImport = { imported++ }))

        compose.onNodeWithTag(ALIGNMENT_IMPORT_BUTTON_TAG).performScrollTo().assertIsEnabled().performClick()

        imported shouldBe 1
    }

    @Test
    fun `S049 RF-2 mientras se explora se ve el progreso y la lista aun no`() {
        show(config(scanning = true))

        compose.onNodeWithTag(ALIGNMENT_SCANNING_TAG).assertIsDisplayed()
        compose.onNodeWithTag(ALIGNMENT_IMPORT_BUTTON_TAG).performScrollTo().assertIsNotEnabled()
    }

    @Test
    fun `S049 RF-11 se puede elegir el sistema del proyecto u otro UTM y la unidad`() {
        var source: CadSourceCrs? = null
        var unit: LinearUnit? = null
        show(config(), AlignmentImportActions(onSource = { source = it }, onUnit = { unit = it }))

        compose.onNodeWithTag(ALIGNMENT_CRS_OTHER_TAG).performScrollTo().performClick()
        compose.onNodeWithTag(alignmentUnitTag(LinearUnit.US_SURVEY_FOOT)).performScrollTo().performClick()

        source shouldBe CadSourceCrs.Utm(GeodeticDatum.ETRS89, 29)
        unit shouldBe LinearUnit.US_SURVEY_FOOT
    }

    @Test
    fun `S049 RF-11 con otro sistema se avisa de que el eje se guarda como rectas`() {
        show(config().copy(source = CadSourceCrs.Utm(GeodeticDatum.ETRS89, 31)))

        compose.onNodeWithText("rectas cortas", substring = true).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `S049 RF-13 un fallo de escritura o una cancelacion se dicen en la configuracion`() {
        show(config().copy(failure = AlignmentImportFailure.WRITE_ERROR))
        compose.onNodeWithTag(ALIGNMENT_FAILURE_TAG).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("no se ha importado nada", substring = true).assertExists()
    }

    @Test
    fun `S049 RF-13 durante la importacion se ve el progreso y se puede cancelar`() {
        var cancelled = 0
        show(
            config().copy(stage = AlignmentImportStage.IMPORTING, importBytes = 500),
            AlignmentImportActions(onCancel = { cancelled++ }),
        )

        compose.onNodeWithTag(ALIGNMENT_PROGRESS_TAG).assertIsDisplayed()
        compose.onNodeWithTag(ALIGNMENT_CANCEL_TAG).assertIsDisplayed().performClick()

        cancelled shouldBe 1
    }

    @Test
    fun `S049 RF-13 el resumen dice lo importado lo ignorado y las rasantes omitidas`() {
        val summary = AlignmentImportSummary(
            imported = listOf(
                ImportedAlignment("a", "Eje A", 420.0, 0.0, hasProfile = true, profileOmission = null, approximated = false),
                ImportedAlignment(
                    "b",
                    "Eje B",
                    380.0,
                    1000.5,
                    hasProfile = false,
                    profileOmission = ProfileOmission.Invalid(ProfileIssue.RepeatedChainage(3, 100.0)),
                    approximated = true,
                ),
            ),
            ignored = listOf(
                IgnoredAlignment("Eje C", AlignmentIssue.Gap(2, 0.05)),
                IgnoredAlignment("Eje D", AlignmentIssue.StationEquation),
            ),
            activatedId = "a",
        )
        show(config().copy(stage = AlignmentImportStage.RESULT, summary = summary))

        compose.onNodeWithTag(ALIGNMENT_RESULT_TAG).assertIsDisplayed()
        compose.onNodeWithText("Importados: 2").assertIsDisplayed()
        compose.onNodeWithText("Eje A · con rasante · del PK 0+000,00 al 0+420,00").assertIsDisplayed()
        compose.onNodeWithText("el vértice 3 repite el PK 0+100,00", substring = true).assertIsDisplayed()
        compose.onNodeWithText("rectas cortas", substring = true).assertExists()
        compose.onNodeWithText("Ignorados: 2").assertIsDisplayed()
        compose.onNodeWithText("Eje C: hay 5,0 cm de hueco antes del tramo 2").assertIsDisplayed()
        compose.onNodeWithText("Eje D: tiene ecuaciones de PK, que no se admiten").assertIsDisplayed()
    }
}
