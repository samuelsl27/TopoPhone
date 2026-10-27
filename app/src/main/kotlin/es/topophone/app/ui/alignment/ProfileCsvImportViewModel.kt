package es.topophone.app.ui.alignment

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import es.topophone.app.di.IoDispatcher
import es.topophone.app.ui.points.ImportSource
import es.topophone.core.formats.alignment.ProfileColumns
import es.topophone.core.formats.alignment.ProfileCsvError
import es.topophone.core.formats.alignment.ProfileCsvReader
import es.topophone.core.formats.alignment.ProfileCsvRow
import es.topophone.core.formats.alignment.ProfileRole
import es.topophone.core.formats.csv.DelimitedReader
import es.topophone.core.formats.csv.TextDecoding
import es.topophone.core.formats.csv.TextProbe
import es.topophone.core.model.alignment.AlignmentSummary
import es.topophone.core.model.alignment.ProfileIssue
import es.topophone.core.model.log.AppLog
import es.topophone.core.model.log.LogCategory
import es.topophone.core.model.point.DelimitedRow
import es.topophone.core.model.point.ImportDecimal
import es.topophone.core.model.point.ImportEncoding
import es.topophone.core.model.point.ImportSeparator
import es.topophone.core.model.project.LinearUnit
import es.topophone.core.survey.KvVertex
import es.topophone.core.survey.ProfileKvBuilder
import es.topophone.data.alignment.AlignmentImporter
import es.topophone.data.alignment.AlignmentRepository
import es.topophone.data.alignment.ProfileAttachResult
import es.topophone.data.project.ProjectRepository
import java.io.IOException
import java.io.StringReader
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Por qué no se abre el asistente de la rasante de un CSV (S049/RF-9). */
enum class ProfileCsvProblem { EMPTY, NOT_TEXT, NO_COLUMNS, TOO_BIG, TOO_MANY_ROWS, NO_ALIGNMENT, UNREADABLE }

enum class ProfileCsvStage { LOADING, PROBLEM, CONFIG, DONE }

/** Lo que muestra la pantalla de asociar una rasante de un CSV a un eje (S049/RF-9, RF-10). */
data class ProfileCsvUiState(
    val stage: ProfileCsvStage = ProfileCsvStage.LOADING,
    val fileName: String = "",
    val problem: ProfileCsvProblem? = null,
    val projectUnit: LinearUnit = LinearUnit.METRE,
    /** Los ejes del proyecto entre los que se elige a cuál se asocia la rasante (RF-9). */
    val alignments: List<AlignmentSummary> = emptyList(),
    val selectedAlignment: String? = null,
    val encoding: ImportEncoding = ImportEncoding.UTF8,
    val separator: ImportSeparator = ImportSeparator.SEMICOLON,
    val decimal: ImportDecimal = ImportDecimal.POINT,
    val hasHeader: Boolean = false,
    /** Las celdas de la primera fila, para dar nombre a cada columna. */
    val firstRow: List<String> = emptyList(),
    val columns: ProfileColumns = ProfileColumns(emptyList()),
    /** Las primeras filas leídas con la asignación de ahora (RF-9). */
    val preview: List<ProfileCsvRow> = emptyList(),
    val rowCount: Int = 0,
    val errors: List<ProfileCsvError> = emptyList(),
    val working: Boolean = false,
    /** Se está leyendo el fichero con las opciones de ahora: la vista previa y los errores aún no son los de ellas. */
    val reading: Boolean = false,
    /** La rasante no valía para el eje (RF-10): el vértice y el motivo. */
    val rejected: ProfileIssue? = null,
    val writeError: Boolean = false,
    val attachedTo: String? = null,
) {
    /** El separador y el signo decimal no pueden ser los dos la coma (S028/RF-8). */
    val decimalConflict: Boolean get() = separator == ImportSeparator.COMMA && decimal == ImportDecimal.COMMA

    /** Hay una rasante que asociar: eje elegido, columnas válidas, filas sin errores y al menos dos vértices. */
    val canAttach: Boolean
        get() = stage == ProfileCsvStage.CONFIG &&
            !working &&
            !reading &&
            selectedAlignment != null &&
            columns.problem == null &&
            errors.isEmpty() &&
            rowCount >= 2 &&
            !decimalConflict
}

/**
 * Asocia a un eje del proyecto la rasante de un CSV de PK, cota y, opcionalmente, parámetro del acuerdo vertical (S049/RF-9, RF-10). La
 * codificación, el separador y el signo decimal se proponen y se pueden cambiar como en S028; los valores van en la unidad del proyecto.
 */
@HiltViewModel
class ProfileCsvImportViewModel @Inject constructor(
    private val importer: AlignmentImporter,
    private val alignments: AlignmentRepository,
    private val projects: ProjectRepository,
    @param:IoDispatcher private val io: CoroutineDispatcher,
    private val log: AppLog = AppLog.NONE,
) : ViewModel() {
    private val mutable = MutableStateFlow(ProfileCsvUiState())
    val state: StateFlow<ProfileCsvUiState> = mutable.asStateFlow()

    private var started = false
    private var source: ImportSource? = null
    private var parsedRows: List<ProfileCsvRow> = emptyList()

    /** Prepara el asistente con [source] para el proyecto [projectId]; solo la primera vez. */
    fun start(projectId: String, source: ImportSource) {
        if (started) return
        started = true
        this.source = source
        mutable.value = ProfileCsvUiState(fileName = source.name)
        viewModelScope.launch(io) { load(projectId, source) }
    }

    private fun problem(problem: ProfileCsvProblem) {
        mutable.update { it.copy(stage = ProfileCsvStage.PROBLEM, problem = problem) }
    }

    private suspend fun load(projectId: String, source: ImportSource) {
        val project = projects.project(projectId) ?: return problem(ProfileCsvProblem.UNREADABLE)
        val axes = alignments.summaries(projectId).first()
        if (axes.isEmpty()) return problem(ProfileCsvProblem.NO_ALIGNMENT)
        if (DelimitedReader.limitExceeded(source.sizeBytes, rows = 0) != null) return problem(ProfileCsvProblem.TOO_BIG)
        val sample = try {
            source.open()?.use { TextDecoding.readSample(it) }
        } catch (_: IOException) {
            null
        } catch (_: SecurityException) {
            null
        } ?: return problem(ProfileCsvProblem.UNREADABLE)
        val encoding = when (val probe = TextDecoding.probe(sample.bytes, sample.truncated)) {
            TextProbe.Empty -> return problem(ProfileCsvProblem.EMPTY)
            TextProbe.Binary -> return problem(ProfileCsvProblem.NOT_TEXT)
            is TextProbe.Text -> probe.encoding
        }
        val text = TextDecoding.decode(sample.bytes, encoding)
        val separator = DelimitedReader.detectSeparator(text)
        val head = DelimitedReader.rows(StringReader(text), separator).take(HEAD_ROWS).toList()
        val first = head.firstOrNull()?.cells.orEmpty()
        if (first.size < 2) return problem(ProfileCsvProblem.NO_COLUMNS)
        val hasHeader = ProfileCsvReader.hasHeader(first)
        val body = if (hasHeader) head.drop(1) else head
        val activeId = alignments.activeAlignmentId(projectId).first()
        mutable.value = ProfileCsvUiState(
            stage = ProfileCsvStage.CONFIG,
            fileName = source.name,
            projectUnit = project.linearUnit,
            alignments = axes,
            selectedAlignment = axes.firstOrNull { it.id == activeId }?.id ?: axes.first().id,
            encoding = encoding,
            separator = separator,
            decimal = ProfileCsvReader.detectDecimal(body, separator),
            hasHeader = hasHeader,
            firstRow = first,
            columns = ProfileCsvReader.suggest(first, hasHeader),
            reading = true,
        )
        reparse()
    }

    fun selectAlignment(id: String) = mutable.update {
        if (it.alignments.any { a -> a.id == id }) it.copy(selectedAlignment = id, rejected = null) else it
    }

    fun setEncoding(encoding: ImportEncoding) = change { it.copy(encoding = encoding) }

    fun setSeparator(separator: ImportSeparator) = change { it.copy(separator = separator) }

    fun setDecimal(decimal: ImportDecimal) = change { it.copy(decimal = decimal) }

    fun setHasHeader(hasHeader: Boolean) = change { it.copy(hasHeader = hasHeader) }

    /** Cambia el papel de la columna [index]; un papel no se puede repetir, así que el que lo tenía lo pierde (RF-9). */
    fun setRole(index: Int, role: ProfileRole) = change { current ->
        val roles = current.columns.roles.mapIndexed { i, r ->
            if (i != index &&
                role != ProfileRole.IGNORE &&
                r == role
            ) {
                ProfileRole.IGNORE
            } else {
                r
            }
        }
        current.copy(columns = ProfileColumns(roles).withRole(index, role))
    }

    private fun change(update: (ProfileCsvUiState) -> ProfileCsvUiState) {
        mutable.update { update(it).copy(rejected = null, writeError = false, reading = true) }
        viewModelScope.launch(io) { reparse() }
    }

    /** Lee el fichero entero con las opciones de ahora y deja la vista previa y los errores (RF-9). */
    private fun reparse() {
        val source = source ?: return
        val current = mutable.value
        val rows = try {
            source.open()?.use { stream ->
                DelimitedReader.rows(TextDecoding.reader(stream, current.encoding), current.separator)
                    .take(DelimitedReader.MAX_ROWS + 1).toList()
            }
        } catch (_: IOException) {
            null
        } catch (_: SecurityException) {
            null
        } ?: return problem(ProfileCsvProblem.UNREADABLE)
        if (rows.size > DelimitedReader.MAX_ROWS) return problem(ProfileCsvProblem.TOO_MANY_ROWS)
        val first = rows.firstOrNull()?.cells.orEmpty()
        val body: List<DelimitedRow> = if (current.hasHeader) rows.drop(1) else rows
        val columns = if (current.columns.roles.size == first.size) current.columns else ProfileCsvReader.suggest(first, current.hasHeader)
        if (columns.problem != null) {
            parsedRows = emptyList()
            mutable.update {
                it.copy(firstRow = first, columns = columns, preview = emptyList(), rowCount = 0, errors = emptyList(), reading = false)
            }
            return
        }
        val result = ProfileCsvReader.parse(body.asSequence(), columns, current.decimal)
        parsedRows = result.rows
        mutable.update {
            it.copy(
                firstRow = first,
                columns = columns,
                preview = result.rows.take(PREVIEW_ROWS),
                rowCount = result.rows.size,
                errors = result.errors,
                reading = false,
            )
        }
    }

    /** Asocia la rasante al eje elegido (RF-9); si no vale para él, dice el vértice y el motivo (RF-10). */
    fun attach() {
        val current = mutable.value
        if (!current.canAttach) return
        val axisId = current.selectedAlignment ?: return
        val unit = current.projectUnit
        mutable.update { it.copy(working = true, rejected = null, writeError = false) }
        viewModelScope.launch(io) {
            val vertices = ProfileKvBuilder.build(
                parsedRows.map { KvVertex(unit.toMetres(it.chainage), unit.toMetres(it.elevation), it.kv?.let(unit::toMetres)) },
            )
            when (val result = importer.attachProfile(axisId, vertices)) {
                ProfileAttachResult.Attached -> {
                    log.info(LogCategory.APP, "Rasante de CSV asociada a un eje")
                    val name = current.alignments.first { it.id == axisId }.name
                    mutable.update { it.copy(working = false, stage = ProfileCsvStage.DONE, attachedTo = name) }
                }
                is ProfileAttachResult.Rejected -> mutable.update { it.copy(working = false, rejected = result.issue) }
                ProfileAttachResult.AxisNotFound, ProfileAttachResult.WriteError -> mutable.update {
                    it.copy(working = false, writeError = true)
                }
            }
        }
    }

    private companion object {
        const val HEAD_ROWS = 200
        const val PREVIEW_ROWS = 8
    }
}
