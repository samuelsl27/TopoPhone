package es.topophone.app.ui.section

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import es.topophone.app.di.IoDispatcher
import es.topophone.app.ui.points.ImportSource
import es.topophone.core.formats.csv.DecimalDetector
import es.topophone.core.formats.csv.DelimitedReader
import es.topophone.core.formats.csv.TextDecoding
import es.topophone.core.formats.csv.TextProbe
import es.topophone.core.formats.section.SectionCsvError
import es.topophone.core.formats.section.SuperelevationColumns
import es.topophone.core.formats.section.SuperelevationCsvReader
import es.topophone.core.formats.section.SuperelevationRole
import es.topophone.core.model.alignment.AlignmentSummary
import es.topophone.core.model.alignment.section.SectionCode
import es.topophone.core.model.alignment.section.SectionIssue
import es.topophone.core.model.alignment.section.SectionSlope
import es.topophone.core.model.alignment.section.SectionTemplate
import es.topophone.core.model.alignment.section.SuperelevationRow
import es.topophone.core.model.alignment.section.TemplateSegment
import es.topophone.core.model.format.NumberInput
import es.topophone.core.model.point.DelimitedRow
import es.topophone.core.model.point.ImportDecimal
import es.topophone.core.model.project.LinearUnit
import es.topophone.data.alignment.AlignmentRepository
import es.topophone.data.project.ProjectRepository
import es.topophone.data.section.SectionRepository
import es.topophone.data.section.SectionSaveResult
import es.topophone.data.section.StoredSection
import es.topophone.data.section.StoredTemplate
import java.io.IOException
import java.io.StringReader
import java.math.BigDecimal
import java.math.RoundingMode
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Un tramo de una sección tipo tal como se escribe (S050/RF-2): el ancho en la unidad del proyecto y la pendiente en porcentaje. */
data class SegmentRow(val code: String = "", val width: String = "", val slope: String = "", val follows: Boolean = false)

/** La sección tipo que se está escribiendo: su rango de PK, su punto de rasante y los tramos de cada lado desde el eje (S050/RF-2). */
data class TemplateDraft(
    val from: String = "",
    val to: String = "",
    val grade: String = SectionCode.AXIS,
    val left: List<SegmentRow> = listOf(SegmentRow()),
    val right: List<SegmentRow> = listOf(SegmentRow()),
)

/** Una fila del peralte tal como se escribe (S050/RF-6): PK en la unidad del proyecto y las pendientes en porcentaje. */
data class SuperelevationDraftRow(val chainage: String = "", val left: String = "", val right: String = "")

/** El campo que no es un número, para decirlo (S050/RF-7). */
enum class SectionField { FROM, TO, WIDTH, SLOPE, CHAINAGE, LEFT_SLOPE, RIGHT_SLOPE }

/** Qué impide guardar lo que se está escribiendo (S050/RF-7). [row] es el número de la fila, desde 1; [left] el lado de un tramo. */
sealed interface SectionsProblem {
    data class InvalidNumber(val field: SectionField, val row: Int?, val left: Boolean?) : SectionsProblem

    data object NoSegments : SectionsProblem

    /** La validación de `core:survey` no lo acepta: el punto y el motivo. */
    data class Rejected(val issue: SectionIssue) : SectionsProblem

    data object WriteError : SectionsProblem
}

/** El CSV de peralte que se está leyendo, con las columnas por asignar (S050/RF-6). */
data class SuperelevationCsvState(
    val fileName: String,
    val firstRow: List<String>,
    val columns: SuperelevationColumns,
    val decimal: ImportDecimal,
    val hasHeader: Boolean,
    val rowCount: Int,
    val errors: List<SectionCsvError<SuperelevationRole>>,
    /** El CSV no se pudo leer: vacío, binario, sin columnas o ilegible. */
    val unreadable: Boolean = false,
)

/** Lo que muestra la pantalla «Secciones» de un eje (S050/RF-1 a RF-3, RF-6, RF-7). */
data class SectionsUiState(
    val alignment: AlignmentSummary? = null,
    val unit: LinearUnit = LinearUnit.METRE,
    val loading: Boolean = true,
    val templates: List<StoredTemplate> = emptyList(),
    val sections: List<StoredSection> = emptyList(),
    val superelevation: List<SuperelevationRow> = emptyList(),
    val templateDraft: TemplateDraft? = null,
    val superelevationDraft: List<SuperelevationDraftRow>? = null,
    val csv: SuperelevationCsvState? = null,
    val problem: SectionsProblem? = null,
    val saving: Boolean = false,
)

/**
 * Las secciones y el peralte de un eje (S050/RF-1 a RF-3, RF-6, RF-7): la lista de lo guardado, la sección tipo escrita a mano (tramos de
 * cada lado, punto de rasante y rango de PK), la tabla de peralte a mano o de un CSV con columnas asignables, y los rechazos con el
 * punto y el motivo. Los PK y los anchos se escriben en la unidad del proyecto y las pendientes en porcentaje, con el signo positivo si
 * el terreno baja al alejarse del eje (plan D2).
 */
@HiltViewModel
class SectionsViewModel @Inject constructor(
    private val sections: SectionRepository,
    private val alignments: AlignmentRepository,
    private val projects: ProjectRepository,
    @param:IoDispatcher private val io: CoroutineDispatcher,
) : ViewModel() {
    private val mutable = MutableStateFlow(SectionsUiState())
    val state: StateFlow<SectionsUiState> = mutable.asStateFlow()

    private var started = false
    private var csvRows: List<DelimitedRow> = emptyList()

    /** Prepara la pantalla del eje [alignmentId]; solo la primera vez. */
    fun start(projectId: String, alignmentId: String) {
        if (started) return
        started = true
        viewModelScope.launch(io) {
            val unit = projects.project(projectId)?.linearUnit ?: LinearUnit.METRE
            combine(
                alignments.summaries(projectId),
                sections.templates(alignmentId),
                sections.sections(alignmentId),
                sections.superelevation(alignmentId),
            ) { axes, templates, stored, rows ->
                mutable.update {
                    it.copy(
                        alignment = axes.firstOrNull { a -> a.id == alignmentId },
                        unit = unit,
                        loading = false,
                        templates = templates,
                        sections = stored,
                        superelevation = rows,
                    )
                }
            }.collect {}
        }
    }

    // -------------------------------------------------------------------------------------------------------- sección tipo
    fun startTemplate() = mutable.update { it.copy(templateDraft = TemplateDraft(), problem = null) }

    fun cancelTemplate() = mutable.update { it.copy(templateDraft = null, problem = null) }

    fun updateTemplate(change: (TemplateDraft) -> TemplateDraft) = mutable.update {
        it.templateDraft?.let { draft -> it.copy(templateDraft = change(draft), problem = null) } ?: it
    }

    fun addSegment(left: Boolean) = updateTemplate { d ->
        if (left) d.copy(left = d.left + SegmentRow()) else d.copy(right = d.right + SegmentRow())
    }

    fun removeSegment(left: Boolean, index: Int) = updateTemplate { d ->
        if (left) {
            d.copy(
                left = d.left.filterIndexed { i, _ ->
                    i != index
                },
            )
        } else {
            d.copy(right = d.right.filterIndexed { i, _ -> i != index })
        }
    }

    fun updateSegment(left: Boolean, index: Int, change: (SegmentRow) -> SegmentRow) = updateTemplate { d ->
        fun List<SegmentRow>.with() = mapIndexed { i, r -> if (i == index) change(r) else r }
        if (left) d.copy(left = d.left.with()) else d.copy(right = d.right.with())
    }

    /** Guarda la sección tipo escrita; si no vale, dice el punto y el motivo y no guarda nada (RF-7). */
    fun saveTemplate() {
        val current = mutable.value
        val draft = current.templateDraft ?: return
        val axis = current.alignment ?: return
        val template = build(draft, current.unit) ?: return
        mutable.update { it.copy(saving = true, problem = null) }
        viewModelScope.launch(io) {
            when (val result = sections.addTemplate(axis.id, template)) {
                is SectionSaveResult.Saved -> mutable.update { it.copy(saving = false, templateDraft = null) }
                is SectionSaveResult.Rejected -> mutable.update {
                    it.copy(saving = false, problem = SectionsProblem.Rejected(result.issue))
                }
                SectionSaveResult.AxisNotFound -> mutable.update { it.copy(saving = false, problem = SectionsProblem.WriteError) }
            }
        }
    }

    private fun build(draft: TemplateDraft, unit: LinearUnit): SectionTemplate? {
        fun number(text: String, field: SectionField, row: Int?, left: Boolean?): Double? = NumberInput.parse(text).also {
            if (it ==
                null
            ) {
                mutable.update { s -> s.copy(problem = SectionsProblem.InvalidNumber(field, row, left)) }
            }
        }
        val from = number(draft.from, SectionField.FROM, null, null)?.let(unit::toMetres) ?: return null
        val to = number(draft.to, SectionField.TO, null, null)?.let(unit::toMetres) ?: return null
        fun side(rows: List<SegmentRow>, left: Boolean): List<TemplateSegment>? = rows.mapIndexed { i, r ->
            val width = number(r.width, SectionField.WIDTH, i + 1, left)?.let(unit::toMetres) ?: return null
            val slope = number(r.slope, SectionField.SLOPE, i + 1, left)?.let(SectionSlope::fromPercent) ?: return null
            TemplateSegment(r.code.trim(), width, slope, r.follows)
        }
        val left = side(draft.left, true) ?: return null
        val right = side(draft.right, false) ?: return null
        if (left.isEmpty() && right.isEmpty()) {
            mutable.update { it.copy(problem = SectionsProblem.NoSegments) }
            return null
        }
        return SectionTemplate(from, to, draft.grade.trim().ifEmpty { SectionCode.AXIS }, left, right)
    }

    fun deleteTemplate(id: String) {
        viewModelScope.launch(io) { sections.deleteTemplate(id) }
    }

    fun deleteSection(id: String) {
        viewModelScope.launch(io) { sections.deleteSection(id) }
    }

    // ------------------------------------------------------------------------------------------------------------- peralte
    /** Empieza a editar la tabla de peralte con las filas guardadas (RF-6). */
    fun startSuperelevation() = mutable.update { current ->
        val unit = current.unit
        current.copy(
            superelevationDraft = current.superelevation.map {
                SuperelevationDraftRow(
                    plain(unit.fromMetres(it.chainage)),
                    plain(SectionSlope.toPercent(it.leftSlope)),
                    plain(SectionSlope.toPercent(it.rightSlope)),
                )
            }.ifEmpty { listOf(SuperelevationDraftRow()) },
            problem = null,
            csv = null,
        )
    }

    fun cancelSuperelevation() = mutable.update { it.copy(superelevationDraft = null, csv = null, problem = null) }

    fun addSuperelevationRow() = updateRows { it + SuperelevationDraftRow() }

    fun removeSuperelevationRow(index: Int) = updateRows { rows -> rows.filterIndexed { i, _ -> i != index } }

    fun updateSuperelevationRow(index: Int, change: (SuperelevationDraftRow) -> SuperelevationDraftRow) =
        updateRows { rows -> rows.mapIndexed { i, r -> if (i == index) change(r) else r } }

    private fun updateRows(change: (List<SuperelevationDraftRow>) -> List<SuperelevationDraftRow>) = mutable.update {
        it.superelevationDraft?.let { rows -> it.copy(superelevationDraft = change(rows), problem = null) } ?: it
    }

    /** Guarda la tabla de peralte escrita (sustituye a la anterior); si no vale, dice el punto y el motivo (RF-7). */
    fun saveSuperelevation() {
        val current = mutable.value
        val draft = current.superelevationDraft ?: return
        val axis = current.alignment ?: return
        val unit = current.unit
        val rows = ArrayList<SuperelevationRow>()
        draft.forEachIndexed { i, r ->
            fun number(text: String, field: SectionField): Double? = NumberInput.parse(text).also {
                if (it == null) mutable.update { s -> s.copy(problem = SectionsProblem.InvalidNumber(field, i + 1, null)) }
            }
            val chainage = number(r.chainage, SectionField.CHAINAGE)?.let(unit::toMetres) ?: return
            val left = number(r.left, SectionField.LEFT_SLOPE)?.let(SectionSlope::fromPercent) ?: return
            val right = number(r.right, SectionField.RIGHT_SLOPE)?.let(SectionSlope::fromPercent) ?: return
            rows += SuperelevationRow(chainage, left, right)
        }
        mutable.update { it.copy(saving = true, problem = null) }
        viewModelScope.launch(io) {
            when (val result = sections.setSuperelevation(axis.id, rows)) {
                is SectionSaveResult.Saved -> mutable.update { it.copy(saving = false, superelevationDraft = null, csv = null) }
                is SectionSaveResult.Rejected -> mutable.update {
                    it.copy(saving = false, problem = SectionsProblem.Rejected(result.issue))
                }
                SectionSaveResult.AxisNotFound -> mutable.update { it.copy(saving = false, problem = SectionsProblem.WriteError) }
            }
        }
    }

    // ------------------------------------------------------------------------------------------------------------- CSV de peralte
    /** Lee un CSV de peralte: propone las columnas, que se pueden cambiar, y las opciones se detectan como en S028 (RF-6). */
    fun readCsv(source: ImportSource) {
        viewModelScope.launch(io) {
            val sample = try {
                source.open()?.use { TextDecoding.readSample(it) }
            } catch (_: IOException) {
                null
            } catch (_: SecurityException) {
                null
            }
            if (sample == null) return@launch csvUnreadable(source.name)
            val encoding = when (val probe = TextDecoding.probe(sample.bytes, sample.truncated)) {
                TextProbe.Empty, TextProbe.Binary -> return@launch csvUnreadable(source.name)
                is TextProbe.Text -> probe.encoding
            }
            val text = TextDecoding.decode(sample.bytes, encoding)
            val separator = DelimitedReader.detectSeparator(text)
            val rows = DelimitedReader.rows(StringReader(text), separator).take(HEAD_ROWS).toList()
            val first = rows.firstOrNull()?.cells.orEmpty()
            if (first.size < 3) return@launch csvUnreadable(source.name)
            val hasHeader = SuperelevationCsvReader.hasHeader(first)
            val body = if (hasHeader) rows.drop(1) else rows
            val decimal = DecimalDetector.detect(body, separator)
            csvRows = body
            val columns = SuperelevationCsvReader.suggest(first, hasHeader)
            mutable.update {
                it.copy(
                    csv = csvState(source.name, first, columns, decimal, hasHeader),
                    superelevationDraft =
                    it.superelevationDraft ?: emptyList(),
                )
            }
        }
    }

    private fun csvUnreadable(name: String) {
        mutable.update {
            it.copy(
                csv = SuperelevationCsvState(
                    name,
                    emptyList(),
                    SuperelevationColumns(emptyList()),
                    ImportDecimal.POINT,
                    false,
                    0,
                    emptyList(),
                    true,
                ),
            )
        }
    }

    private fun csvState(
        name: String,
        first: List<String>,
        columns: SuperelevationColumns,
        decimal: ImportDecimal,
        hasHeader: Boolean,
    ): SuperelevationCsvState {
        val parsed = if (columns.isValid) SuperelevationCsvReader.parse(csvRows.asSequence(), columns, decimal) else null
        return SuperelevationCsvState(name, first, columns, decimal, hasHeader, parsed?.rows?.size ?: 0, parsed?.errors.orEmpty())
    }

    /** Cambia el papel de la columna [index] del CSV; un papel no se puede repetir (RF-6). */
    fun setCsvRole(index: Int, role: SuperelevationRole) = mutable.update { current ->
        val csv = current.csv ?: return@update current
        val roles = csv.columns.roles.mapIndexed { i, r ->
            when {
                i == index -> role
                role != SuperelevationRole.IGNORE && r == role -> SuperelevationRole.IGNORE
                else -> r
            }
        }
        current.copy(csv = csvState(csv.fileName, csv.firstRow, SuperelevationColumns(roles), csv.decimal, csv.hasHeader))
    }

    /** Pasa las filas del CSV a la tabla que se está escribiendo, para revisarlas y guardarlas (RF-6). */
    fun applyCsv() {
        val current = mutable.value
        val csv = current.csv ?: return
        if (csv.unreadable || !csv.columns.isValid || csv.errors.isNotEmpty() || csv.rowCount == 0) return
        val parsed = SuperelevationCsvReader.parse(csvRows.asSequence(), csv.columns, csv.decimal)
        val unit = current.unit
        mutable.update {
            it.copy(
                superelevationDraft = parsed.rows.map { r ->
                    SuperelevationDraftRow(
                        plain(unit.fromMetres(r.chainage)),
                        plain(SectionSlope.toPercent(r.leftSlope)),
                        plain(SectionSlope.toPercent(r.rightSlope)),
                    )
                },
                csv = null,
                problem = null,
            )
        }
    }

    /** El número tal como se escribe: sin ceros ni restos de coma flotante (2, no 2.0000000000000004). */
    private fun plain(value: Double): String =
        BigDecimal(value).setScale(DISPLAY_DECIMALS, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()

    private companion object {
        const val HEAD_ROWS = 200
        const val DISPLAY_DECIMALS = 9
    }
}
