package es.topophone.app.ui.section

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.core.designsystem.theme.TopoPhoneTheme
import es.topophone.core.formats.landxml.LandXmlCrossSectInfo
import es.topophone.core.formats.section.SectionColumns
import es.topophone.core.formats.section.SectionCsvError
import es.topophone.core.formats.section.SectionCsvRow
import es.topophone.core.formats.section.SectionRole
import es.topophone.core.model.alignment.AlignmentExtent
import es.topophone.core.model.alignment.AlignmentSource
import es.topophone.core.model.alignment.AlignmentSummary
import es.topophone.core.model.alignment.section.HeightKind
import es.topophone.core.model.alignment.section.SectionIssue
import es.topophone.core.model.alignment.section.SectionKind
import es.topophone.core.model.point.ImportDecimal
import es.topophone.core.model.point.ImportSeparator
import es.topophone.core.model.preferences.EffectiveTheme
import es.topophone.core.model.preferences.ThemeState
import es.topophone.core.model.project.LinearUnit
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** La pantalla de importar secciones de un CSV o un LandXML: problemas, opciones, columnas, lista y resumen (S050/RF-4, RF-5, RF-7). */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "es-rES")
class SectionImportScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val axis = AlignmentSummary(
        "a", "p", "Eje C", null, 1L, AlignmentSource.LANDXML, 0.0, 200.0, true, false, false, AlignmentExtent(0.0, 1.0, 0.0, 1.0),
    )

    private fun show(state: SectionImportUiState, actions: SectionImportActions = SectionImportActions()) {
        compose.setContent {
            TopoPhoneTheme(EffectiveTheme.LIGHT) { SectionImportScreen(ThemeState.DEFAULT, {}, state, actions, {}) }
        }
    }

    private val csv = SectionImportUiState(
        stage = SectionImportStage.CONFIG,
        kind = SectionImportKind.CSV,
        fileName = "secciones.csv",
        alignment = axis,
        separator = ImportSeparator.COMMA,
        decimal = ImportDecimal.POINT,
        hasHeader = true,
        firstRow = listOf("pk", "codigo", "desplazamiento", "cota", "tipo"),
        columns = SectionColumns(listOf(SectionRole.CHAINAGE, SectionRole.CODE, SectionRole.OFFSET, SectionRole.HEIGHT, SectionRole.KIND)),
        preview = listOf(SectionCsvRow(2, 0.0, "EJE", 0.0, 100.3, SectionKind.DESIGN)),
        rowCount = 20,
    )

    private val landxml = SectionImportUiState(
        stage = SectionImportStage.CONFIG,
        kind = SectionImportKind.LANDXML,
        fileName = "secciones.xml",
        alignment = axis,
        declaredUnit = LinearUnit.METRE,
        sections = listOf(
            LandXmlCrossSectInfo(0, 0.0, "PK 0", SectionKind.DESIGN, 5, 0),
            LandXmlCrossSectInfo(1, 50.0, "PK 50", SectionKind.GROUND, 4, 0),
        ),
        selected = setOf(0, 1),
    )

    @Test
    fun `S050 RF-5 un fichero que no vale dice el motivo y no abre las opciones`() {
        show(SectionImportUiState(stage = SectionImportStage.PROBLEM, problem = SectionImportProblem.NOT_LANDXML, fileName = "a.xml"))

        compose.onNodeWithTag(SECTION_IMPORT_PROBLEM_TAG).assertIsDisplayed()
        compose.onNodeWithText("El fichero no es un LandXML.").assertExists()
    }

    @Test
    fun `S050 RF-5 una version de LandXML no admitida la dice`() {
        show(SectionImportUiState(stage = SectionImportStage.PROBLEM, problem = SectionImportProblem.UNSUPPORTED_VERSION, version = "2.0"))

        compose.onNodeWithText("Versión de LandXML no admitida: 2.0.").assertExists()
    }

    @Test
    fun `S050 RF-4 el CSV ofrece la lectura la unidad la cota y las columnas con su vista previa`() {
        show(csv)

        compose.onNodeWithTag(SECTION_IMPORT_PREVIEW_TAG).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag(sectionImportRoleTag(3, SectionRole.HEIGHT)).assertExists()
        compose.onNodeWithTag(sectionImportHeightTag(HeightKind.ABSOLUTE)).assertExists()
        compose.onNodeWithTag(sectionImportUnitTag(LinearUnit.INTERNATIONAL_FOOT)).assertExists()
        compose.onNodeWithTag(SECTION_IMPORT_BUTTON_TAG).performScrollTo().assertIsEnabled()
    }

    @Test
    fun `S050 RF-4 los cambios de unidad cota columna y lectura avisan`() {
        val calls = mutableListOf<String>()
        show(
            csv,
            SectionImportActions(
                onUnit = { calls += "unit:${it.name}" },
                onHeightKind = { calls += "height:${it.name}" },
                onRole = { i, r -> calls += "role:$i:${r.name}" },
                onSeparator = { calls += "sep:${it.name}" },
                onHasHeader = { calls += "header:$it" },
                onImport = { calls += "import" },
            ),
        )

        compose.onNodeWithTag(sectionImportUnitTag(LinearUnit.INTERNATIONAL_FOOT)).performScrollTo().performClick()
        compose.onNodeWithTag(sectionImportHeightTag(HeightKind.RELATIVE)).performScrollTo().performClick()
        compose.onNodeWithTag(sectionImportRoleTag(1, SectionRole.IGNORE)).performScrollTo().performClick()
        compose.onNodeWithTag(sectionImportSeparatorTag(ImportSeparator.SEMICOLON)).performScrollTo().performClick()
        compose.onNodeWithTag(SECTION_IMPORT_HEADER_TAG).performScrollTo().performClick()
        compose.onNodeWithTag(SECTION_IMPORT_BUTTON_TAG).performScrollTo().performClick()

        calls shouldBe listOf(
            "unit:INTERNATIONAL_FOOT",
            "height:RELATIVE",
            "role:1:IGNORE",
            "sep:SEMICOLON",
            "header:false",
            "import",
        )
    }

    @Test
    fun `S050 RF-4 con una columna sin asignar o con errores no se puede importar y se dice`() {
        show(
            csv.copy(
                columns = SectionColumns(listOf(SectionRole.CHAINAGE, SectionRole.CODE, SectionRole.OFFSET)),
                errors = listOf(SectionCsvError(7, SectionRole.OFFSET, SectionCsvError.Reason.NOT_A_NUMBER)),
            ),
        )

        compose.onNodeWithTag(SECTION_IMPORT_MAPPING_TAG).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Falta asignar la columna de la cota.").assertExists()
        compose.onNodeWithTag(SECTION_IMPORT_ERRORS_TAG).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Línea 7, Desplazamiento: no es un número").assertExists()
        compose.onNodeWithTag(SECTION_IMPORT_BUTTON_TAG).performScrollTo().assertIsNotEnabled()
    }

    @Test
    fun `S050 RF-4 coma como separador y como decimal a la vez se avisa`() {
        show(csv.copy(separator = ImportSeparator.COMMA, decimal = ImportDecimal.COMMA))

        compose.onNodeWithTag(SECTION_IMPORT_CONFLICT_TAG).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `S050 RF-5 el LandXML lista sus secciones por PK con diseno y terreno y deja marcarlas`() {
        val calls = mutableListOf<String>()
        show(
            landxml,
            SectionImportActions(onToggle = { calls += "toggle:$it" }, onSelectKind = { k, on -> calls += "kind:${k.name}:$on" }),
        )

        compose.onNodeWithText("PK 0+000,00 · Diseño · 5 puntos").assertExists()
        compose.onNodeWithText("PK 0+050,00 · Terreno · 4 puntos").assertExists()
        compose.onNodeWithTag(SECTION_IMPORT_LANDXML_NOTE_TAG).assertExists()
        compose.onNodeWithTag(sectionImportItemTag(1)).performScrollTo().performClick()
        compose.onNodeWithTag(sectionImportSelectKindTag(SectionKind.GROUND)).performScrollTo().performClick()

        calls shouldBe listOf("toggle:1", "kind:GROUND:false")
    }

    @Test
    fun `S050 RF-5 sin ninguna seccion marcada el boton Importar esta desactivado`() {
        show(landxml.copy(selected = emptySet()))

        compose.onNodeWithTag(SECTION_IMPORT_BUTTON_TAG).performScrollTo().assertIsNotEnabled()
    }

    @Test
    fun `S050 RF-7 el resumen dice cuantas se guardaron y de las que no, el PK el tipo y el motivo`() {
        show(
            SectionImportUiState(
                stage = SectionImportStage.DONE,
                alignment = axis,
                savedCount = 3,
                rejected = listOf(
                    RejectedSection(20.0, SectionKind.DESIGN, SectionIssue.RepeatedCode("A")),
                    RejectedSection(500.0, SectionKind.GROUND, SectionIssue.ChainageOutsideAxis(500.0)),
                ),
            ),
        )

        compose.onNodeWithText("Importadas 3 secciones.").assertIsDisplayed()
        compose.onNodeWithTag(SECTION_IMPORT_REJECTED_TAG).assertIsDisplayed()
        compose.onNodeWithText("PK 0+020,00 (Diseño): El código «A» está repetido.").assertExists()
        compose.onNodeWithText("PK 0+500,00 (Terreno): El PK 0+500,00 queda fuera del eje.").assertExists()
    }
}
