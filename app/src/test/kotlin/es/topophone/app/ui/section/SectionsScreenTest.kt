package es.topophone.app.ui.section

import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.core.designsystem.theme.TopoPhoneTheme
import es.topophone.core.formats.section.SectionCsvError
import es.topophone.core.formats.section.SuperelevationColumns
import es.topophone.core.formats.section.SuperelevationRole
import es.topophone.core.model.alignment.AlignmentExtent
import es.topophone.core.model.alignment.AlignmentSource
import es.topophone.core.model.alignment.AlignmentSummary
import es.topophone.core.model.alignment.section.CrossSection
import es.topophone.core.model.alignment.section.HeightKind
import es.topophone.core.model.alignment.section.SectionIssue
import es.topophone.core.model.alignment.section.SectionKind
import es.topophone.core.model.alignment.section.SectionPoint
import es.topophone.core.model.alignment.section.SectionTemplate
import es.topophone.core.model.alignment.section.SuperelevationRow
import es.topophone.core.model.alignment.section.TemplateSegment
import es.topophone.core.model.point.ImportDecimal
import es.topophone.core.model.preferences.EffectiveTheme
import es.topophone.core.model.preferences.ThemeState
import es.topophone.data.section.StoredSection
import es.topophone.data.section.StoredTemplate
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** La pantalla «Secciones» de un eje: las listas, los editores y los rechazos (S050/RF-1 a RF-3, RF-6, RF-7). */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "es-rES")
class SectionsScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val axis = AlignmentSummary(
        "a", "p", "Eje A", null, 1L, AlignmentSource.MANUAL, 0.0, 420.0, true, true, false, AlignmentExtent(0.0, 1.0, 0.0, 1.0),
    )

    private val template = SectionTemplate(
        0.0,
        100.0,
        "EJE",
        listOf(TemplateSegment("BI", 3.5, 0.02, true)),
        listOf(TemplateSegment("BD", 3.5, 0.02, true)),
    )

    private val section = CrossSection(
        SectionKind.GROUND,
        50.0,
        HeightKind.ABSOLUTE,
        listOf(SectionPoint("T1", -3.0, 10.0), SectionPoint("T2", 3.0, 10.5)),
    )

    private fun show(state: SectionsUiState, actions: SectionsActions = SectionsActions()) {
        compose.setContent {
            TopoPhoneTheme(EffectiveTheme.LIGHT) { SectionsScreen(ThemeState.DEFAULT, {}, state, actions, {}) }
        }
    }

    private val full = SectionsUiState(
        alignment = axis,
        loading = false,
        templates = listOf(StoredTemplate("t1", template, 1L)),
        sections = listOf(StoredSection("s1", section, 2L)),
        superelevation = listOf(SuperelevationRow(0.0, 0.02, 0.02), SuperelevationRow(180.0, -0.06, 0.06)),
    )

    @Test
    fun `S050 RF-1 sin nada guardado las tres listas lo dicen`() {
        show(SectionsUiState(alignment = axis, loading = false))

        compose.onNodeWithTag(SECTIONS_TEMPLATES_EMPTY_TAG).assertIsDisplayed()
        compose.onNodeWithTag(SECTIONS_PROFILES_EMPTY_TAG).assertIsDisplayed()
        compose.onNodeWithTag(SECTIONS_SUPER_EMPTY_TAG).assertExists()
    }

    @Test
    fun `S050 RF-3 las secciones tipo y las importadas se listan con sus PK tipo y cota`() {
        show(full)

        compose.onNodeWithText("De PK 0+000,00 a PK 0+100,00 · punto de rasante EJE").assertExists()
        compose.onNodeWithText("Terreno · PK 0+050,00 · 2 puntos · cota absoluta").assertExists()
    }

    @Test
    fun `S050 RF-6 el peralte se lista con baja o sube y no solo con el signo`() {
        show(full)

        compose.onNodeWithText("PK 0+000,00 · izquierda baja 2 % · derecha baja 2 %").assertExists()
        compose.onNodeWithText("PK 0+180,00 · izquierda sube 6 % · derecha baja 6 %").assertExists()
    }

    @Test
    fun `S050 RF-3 borrar una seccion tipo o una importada lleva su identificador`() {
        val calls = mutableListOf<String>()
        show(full, SectionsActions(onDeleteTemplate = { calls += "t:$it" }, onDeleteSection = { calls += "s:$it" }))

        compose.onNodeWithTag(sectionsTemplateDeleteTag("t1")).performScrollTo().performClick()
        compose.onNodeWithTag(sectionsProfileDeleteTag("s1")).performScrollTo().performClick()

        calls shouldBe listOf("t:t1", "s:s1")
    }

    @Test
    fun `S050 RF-1 los botones de importar editar y calcular avisan`() {
        val calls = mutableListOf<String>()
        show(
            full,
            SectionsActions(
                onImportCsv = { calls += "csv" },
                onImportLandXml = { calls += "xml" },
                onStartTemplate = { calls += "template" },
                onStartSuperelevation = { calls += "super" },
                onCalculate = { calls += "calc" },
            ),
        )

        listOf(
            SECTIONS_IMPORT_CSV_TAG,
            SECTIONS_IMPORT_LANDXML_TAG,
            SECTIONS_ADD_TEMPLATE_TAG,
            SECTIONS_EDIT_SUPER_TAG,
            SECTIONS_CALCULATE_TAG,
        )
            .forEach { compose.onNodeWithTag(it).performScrollTo().assertHeightIsAtLeast(56.dp).performClick() }

        calls shouldBe listOf("csv", "xml", "template", "super", "calc")
    }

    @Test
    fun `S050 RF-2 el editor de la seccion tipo pide el rango el punto de rasante y los tramos de cada lado`() {
        val edits = mutableListOf<TemplateDraft>()
        show(
            SectionsUiState(alignment = axis, loading = false, templateDraft = TemplateDraft()),
            SectionsActions(onTemplate = { change -> edits += change(TemplateDraft()) }),
        )

        compose.onNodeWithTag(SECTIONS_TEMPLATE_EDITOR_TAG).assertIsDisplayed()
        compose.onNodeWithTag(SECTIONS_TEMPLATE_FROM_TAG).performTextInput("0")
        compose.onNodeWithTag(SECTIONS_TEMPLATE_GRADE_TAG).assertExists()
        compose.onNodeWithTag(sectionsSegmentTag(true, 0, "width")).assertExists()
        compose.onNodeWithTag(sectionsSegmentTag(false, 0, "follows")).assertExists()

        edits.single().from shouldBe "0"
    }

    @Test
    fun `S050 RF-2 los tramos se anaden y se quitan y el cuadro Sigue el peralte se marca`() {
        val calls = mutableListOf<String>()
        show(
            SectionsUiState(alignment = axis, loading = false, templateDraft = TemplateDraft()),
            SectionsActions(
                onAddSegment = { calls += "add:$it" },
                onRemoveSegment = { left, i -> calls += "rm:$left:$i" },
                onSegment = { left, i, change -> calls += "seg:$left:$i:${change(SegmentRow()).follows}" },
                onSaveTemplate = { calls += "save" },
                onCancelTemplate = { calls += "cancel" },
            ),
        )

        compose.onNodeWithTag(sectionsSegmentAddTag(true)).performScrollTo().performClick()
        compose.onNodeWithTag(sectionsSegmentRemoveTag(false, 0)).performScrollTo().performClick()
        compose.onNodeWithTag(sectionsSegmentTag(true, 0, "follows")).performScrollTo().performClick()
        compose.onNodeWithTag(SECTIONS_TEMPLATE_SAVE_TAG).performScrollTo().performClick()
        compose.onNodeWithTag(SECTIONS_TEMPLATE_CANCEL_TAG).performScrollTo().performClick()

        calls shouldBe listOf("add:true", "rm:false:0", "seg:true:0:true", "save", "cancel")
    }

    @Test
    fun `S050 RF-7 un rechazo dice el punto y el motivo con el PK en la unidad del proyecto`() {
        show(
            SectionsUiState(
                alignment = axis,
                loading = false,
                templateDraft = TemplateDraft(),
                problem = SectionsProblem.Rejected(SectionIssue.OverlappingTemplates(50.0, 150.0)),
            ),
        )

        compose.onNodeWithTag(SECTIONS_PROBLEM_TAG).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("El rango de PK 0+050,00 a 0+150,00 se solapa con otra sección tipo.").assertExists()
    }

    @Test
    fun `S050 RF-7 un numero mal escrito dice el campo la fila y el lado`() {
        show(
            SectionsUiState(
                alignment = axis,
                loading = false,
                templateDraft = TemplateDraft(),
                problem = SectionsProblem.InvalidNumber(SectionField.WIDTH, 2, true),
            ),
        )

        compose.onNodeWithText("Ancho (tramo 2, lado izquierdo) no es un número.").assertExists()
    }

    @Test
    fun `S050 RF-6 el editor del peralte pide el PK y las dos pendientes de cada fila`() {
        val calls = mutableListOf<String>()
        show(
            SectionsUiState(
                alignment = axis,
                loading = false,
                superelevationDraft = listOf(SuperelevationDraftRow("0", "2", "2"), SuperelevationDraftRow()),
            ),
            SectionsActions(
                onAddSuperRow = { calls += "add" },
                onRemoveSuperRow = { calls += "rm:$it" },
                onSuperRow = { i, change -> calls += "row:$i:${change(SuperelevationDraftRow()).right}" },
                onSaveSuperelevation = { calls += "save" },
                onCancelSuperelevation = { calls += "cancel" },
                onSuperelevationCsv = { calls += "csv" },
            ),
        )

        compose.onNodeWithTag(sectionsSuperTag(0, "chainage")).assertExists()
        compose.onNodeWithTag(sectionsSuperTag(1, "right")).performTextInput("4")
        compose.onNodeWithTag(SECTIONS_SUPER_ADD_TAG).performScrollTo().performClick()
        compose.onNodeWithTag(sectionsSuperRemoveTag(1)).performScrollTo().performClick()
        compose.onNodeWithTag(SECTIONS_SUPER_CSV_TAG).performScrollTo().performClick()
        compose.onNodeWithTag(SECTIONS_SUPER_SAVE_TAG).performScrollTo().performClick()
        compose.onNodeWithTag(SECTIONS_SUPER_CANCEL_TAG).performScrollTo().performClick()

        calls shouldBe listOf("row:1:4", "add", "rm:1", "csv", "save", "cancel")
    }

    private fun csvState(valid: Boolean, errors: List<SectionCsvError<SuperelevationRole>> = emptyList(), unreadable: Boolean = false) =
        SuperelevationCsvState(
            fileName = "peralte.csv",
            firstRow = listOf("PK", "Izq", "Der"),
            columns = SuperelevationColumns(
                if (valid) {
                    listOf(SuperelevationRole.CHAINAGE, SuperelevationRole.LEFT, SuperelevationRole.RIGHT)
                } else {
                    listOf(SuperelevationRole.CHAINAGE, SuperelevationRole.IGNORE, SuperelevationRole.RIGHT)
                },
            ),
            decimal = ImportDecimal.POINT,
            hasHeader = true,
            rowCount = if (valid) 2 else 0,
            errors = errors,
            unreadable = unreadable,
        )

    @Test
    fun `S050 RF-6 el CSV de peralte deja asignar las columnas y solo se usa si es valido`() {
        val roles = mutableListOf<Pair<Int, SuperelevationRole>>()
        var applied = 0
        val withDraft = SectionsUiState(alignment = axis, loading = false, superelevationDraft = listOf(SuperelevationDraftRow()))
        show(
            withDraft.copy(csv = csvState(valid = true)),
            SectionsActions(onCsvRole = { i, r -> roles += i to r }, onApplyCsv = { applied++ }),
        )

        compose.onNodeWithTag(SECTIONS_CSV_PANEL_TAG).assertExists()
        compose.onNodeWithTag(sectionsCsvRoleTag(1, SuperelevationRole.RIGHT)).performScrollTo().performClick()
        compose.onNodeWithTag(SECTIONS_CSV_APPLY_TAG).performScrollTo().assertIsEnabled().performClick()

        roles shouldBe listOf(1 to SuperelevationRole.RIGHT)
        applied shouldBe 1
    }

    @Test
    fun `S050 RF-6 un CSV de peralte con columna sin asignar o con errores no se puede usar`() {
        val withDraft = SectionsUiState(alignment = axis, loading = false, superelevationDraft = listOf(SuperelevationDraftRow()))
        show(withDraft.copy(csv = csvState(valid = false)))

        compose.onNodeWithTag(SECTIONS_CSV_APPLY_TAG).performScrollTo().assertIsNotEnabled()
    }

    @Test
    fun `S050 RF-6 un CSV de peralte ilegible se dice`() {
        val withDraft = SectionsUiState(alignment = axis, loading = false, superelevationDraft = listOf(SuperelevationDraftRow()))
        show(withDraft.copy(csv = csvState(valid = false, unreadable = true)))

        compose.onNodeWithTag(SECTIONS_CSV_UNREADABLE_TAG).performScrollTo().assertIsDisplayed()
    }
}
