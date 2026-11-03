package es.topophone.app.ui.codes

import android.database.sqlite.SQLiteException
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import es.topophone.app.di.IoDispatcher
import es.topophone.app.ui.points.ImportFileProblem
import es.topophone.app.ui.points.ImportSource
import es.topophone.core.formats.codes.CodeCsvParse
import es.topophone.core.formats.codes.CodeCsvResult
import es.topophone.core.formats.codes.CodeLibraryCsvReader
import es.topophone.core.formats.codes.InvalidRow
import es.topophone.core.formats.codes.ParsedCode
import es.topophone.core.formats.csv.DecimalDetector
import es.topophone.core.formats.csv.DelimitedReader
import es.topophone.core.formats.csv.ImportLimit
import es.topophone.core.formats.csv.TextDecoding
import es.topophone.core.formats.csv.TextProbe
import es.topophone.core.model.codes.CodeGeometry
import es.topophone.core.model.codes.CodeMerge
import es.topophone.core.model.codes.DuplicatePolicy
import es.topophone.core.model.codes.MergeConflicts
import es.topophone.core.model.codes.SurveyCode
import es.topophone.core.model.log.AppLog
import es.topophone.core.model.log.LogCategory
import es.topophone.core.model.point.ImportDecimal
import es.topophone.core.model.point.ImportEncoding
import es.topophone.core.model.point.ImportSeparator
import es.topophone.data.codes.CodeImportResult
import es.topophone.data.codes.CodeLibraryRepository
import es.topophone.data.codes.ImportTarget
import es.topophone.data.codes.LibrarySummary
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.io.StringReader
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** En qué punto está el asistente de importación de códigos (S023/RF-12). */
enum class CodeImportStage { LOADING, PROBLEM, WIZARD, IMPORTING, RESULT }

/** Adónde van los códigos: una biblioteca nueva con el nombre del fichero o una del usuario que ya existe (S023/RF-14). */
sealed interface CodeImportTarget {
    data object New : CodeImportTarget

    data class Existing(val libraryId: String) : CodeImportTarget
}

/** Lo que pasó al importar (S023/RF-14): cuántos entraron, cuántos no por repetidos y cuántas filas se descartaron. */
data class CodeImportOutcome(val libraryId: String, val entered: Int, val skipped: Int, val invalid: Int)

/** Una fila de la vista previa antes de importar (S023/RF-12). */
data class CodePreviewRow(
    val line: Int,
    val code: String,
    val description: String,
    val geometry: CodeGeometry,
    val color: Int,
    val attributes: Int,
)

/** Lo que muestra el asistente de importación de bibliotecas (S023/RF-12 a RF-16). */
data class CodeImportUiState(
    val stage: CodeImportStage = CodeImportStage.LOADING,
    val fileName: String = "",
    val fileProblem: ImportFileProblem? = null,
    val encoding: ImportEncoding = ImportEncoding.UTF8,
    val separator: ImportSeparator = ImportSeparator.COMMA,
    val decimal: ImportDecimal = ImportDecimal.POINT,
    val analyzing: Boolean = false,
    /** Falta la columna `Code`: no se importa nada (RF-13). */
    val missingCode: Boolean = false,
    val preview: List<CodePreviewRow> = emptyList(),
    val validCount: Int = 0,
    val invalid: List<InvalidRow> = emptyList(),
    val invalidCount: Int = 0,
    val conflicts: MergeConflicts = MergeConflicts(emptyList(), emptyList()),
    val policy: DuplicatePolicy = DuplicatePolicy.SKIP,
    val libraries: List<LibrarySummary> = emptyList(),
    val target: CodeImportTarget = CodeImportTarget.New,
    val writeFailed: Boolean = false,
    val outcome: CodeImportOutcome? = null,
) {
    /** El signo decimal y el separador no pueden ser los dos la coma (como S028/RF-8). */
    val decimalConflict: Boolean get() = separator == ImportSeparator.COMMA && decimal == ImportDecimal.COMMA

    val canImport: Boolean
        get() = stage == CodeImportStage.WIZARD && !analyzing && !missingCode && !decimalConflict && validCount > 0
}

/**
 * El asistente de importación de una biblioteca desde un CSV (S023/RF-12 a RF-16): lee el fichero por flujo, propone la codificación,
 * el separador y el signo decimal de S028, enseña una vista previa y las filas inválidas, y no escribe nada hasta confirmar. La
 * importación se hace entera o no se hace (RF-14).
 */
@HiltViewModel
class CodeImportViewModel @Inject constructor(
    private val repository: CodeLibraryRepository,
    @param:IoDispatcher private val io: CoroutineDispatcher,
    private val log: AppLog = AppLog.NONE,
) : ViewModel() {
    private val mutable = MutableStateFlow(CodeImportUiState())
    val state: StateFlow<CodeImportUiState> = mutable.asStateFlow()

    private var started = false
    private var source: ImportSource? = null
    private var parse: CodeCsvParse? = null
    private var existingCodes: List<SurveyCode> = emptyList()
    private var analysisJob: Job? = null
    private var baseName = ""

    /** Prepara el asistente con [source]; solo la primera vez. [fallbackName] se usa si el fichero no da un nombre (RF-14). */
    fun start(source: ImportSource, fallbackName: String, preselected: String? = null) {
        if (started) return
        started = true
        this.source = source
        baseName = source.name.substringBeforeLast('.').trim().ifEmpty { fallbackName }
        mutable.value = CodeImportUiState(fileName = source.name)
        viewModelScope.launch(io) { load(preselected) }
    }

    private fun problem(problem: ImportFileProblem) {
        mutable.update { it.copy(stage = CodeImportStage.PROBLEM, fileProblem = problem, analyzing = false) }
    }

    private suspend fun load(preselected: String?) {
        val source = checkNotNull(source)
        if (DelimitedReader.limitExceeded(source.sizeBytes, rows = 0) != null) return problem(ImportFileProblem.TOO_BIG)
        val sample = try {
            source.open()?.use { TextDecoding.readSample(it) }
        } catch (_: IOException) {
            null
        } catch (_: SecurityException) {
            null
        } ?: return problem(ImportFileProblem.UNREADABLE)
        val encoding = when (val probe = TextDecoding.probe(sample.bytes, sample.truncated)) {
            TextProbe.Empty -> return problem(ImportFileProblem.EMPTY)
            TextProbe.Binary -> return problem(ImportFileProblem.NOT_TEXT)
            is TextProbe.Text -> probe.encoding
        }
        val text = TextDecoding.decode(sample.bytes, encoding)
        val detected = DelimitedReader.detectSeparator(text)
        // Una biblioteca no se separa con espacios: los textos de las descripciones los llevan.
        val separator = if (detected == ImportSeparator.SPACES) ImportSeparator.COMMA else detected
        val head = DelimitedReader.rows(StringReader(text), separator).take(HEAD_ROWS).toList()
        if ((head.maxOfOrNull { it.cells.size } ?: 0) < 1) return problem(ImportFileProblem.NO_COLUMNS)
        val libraries = repository.libraries().first().filter { !it.isDefault }
        mutable.value = CodeImportUiState(
            stage = CodeImportStage.WIZARD,
            fileName = source.name,
            encoding = encoding,
            separator = separator,
            decimal = DecimalDetector.detect(head, separator),
            analyzing = true,
            libraries = libraries,
            target =
            preselected?.takeIf { id -> libraries.any { it.id == id } }?.let { CodeImportTarget.Existing(it) } ?: CodeImportTarget.New,
        )
        analyze()
    }

    // --- Las opciones (S023/RF-12, RF-14, RF-16). Cada cambio vuelve a analizar el fichero. ---

    fun setEncoding(encoding: ImportEncoding) = change { it.copy(encoding = encoding) }

    fun setSeparator(separator: ImportSeparator) = change { it.copy(separator = separator) }

    fun setDecimal(decimal: ImportDecimal) = change { it.copy(decimal = decimal) }

    fun setPolicy(policy: DuplicatePolicy) {
        if (mutable.value.stage != CodeImportStage.WIZARD) return
        mutable.update { it.copy(policy = policy) }
    }

    fun setTarget(target: CodeImportTarget) {
        if (mutable.value.stage != CodeImportStage.WIZARD) return
        mutable.update { it.copy(target = target, analyzing = true) }
        analyze(reread = false)
    }

    private fun change(transform: (CodeImportUiState) -> CodeImportUiState) {
        if (mutable.value.stage != CodeImportStage.WIZARD) return
        mutable.update { transform(it).copy(analyzing = true) }
        analyze()
    }

    /** Vuelve a leer el fichero (o solo recalcula los repetidos si [reread] es falso) sin crear nada (RF-12). */
    private fun analyze(reread: Boolean = true) {
        analysisJob?.cancel()
        analysisJob = viewModelScope.launch(io) {
            val options = mutable.value
            if (reread || parse == null) {
                val read = try {
                    read(options)
                } catch (e: ProblemException) {
                    return@launch problem(e.problem)
                }
                if (read == null) {
                    parse = null
                    mutable.update {
                        it.copy(
                            analyzing = false,
                            missingCode = true,
                            preview = emptyList(),
                            validCount = 0,
                            invalid = emptyList(),
                            invalidCount = 0,
                        )
                    }
                    return@launch
                }
                parse = read
            }
            existingCodes =
                (options.target as? CodeImportTarget.Existing)?.let { repository.library(it.libraryId).first()?.codes }.orEmpty()
            val current = checkNotNull(parse)
            val incoming = current.codes.map { it.code }
            mutable.update {
                it.copy(
                    analyzing = false,
                    missingCode = false,
                    preview = current.codes.take(PREVIEW_ROWS).map { p -> p.toPreview() },
                    validCount = current.codes.size,
                    invalid = current.invalid.take(MAX_LISTED),
                    invalidCount = current.invalid.size,
                    conflicts = CodeMerge.conflicts(existingCodes, incoming),
                )
            }
        }
    }

    private class ProblemException(val problem: ImportFileProblem) : Exception()

    private class TooBigException : IOException("Fichero demasiado grande")

    /** Corta la lectura de un fichero que pasa del límite de tamaño cuando el selector no dio su tamaño. */
    private class LimitedInputStream(input: InputStream, private val limit: Long) : FilterInputStream(input) {
        private var count = 0L

        override fun read(): Int = super.read().also { if (it >= 0) add(1) }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
            super.read(buffer, offset, length).also { if (it > 0) add(it.toLong()) }

        private fun add(bytes: Long) {
            count += bytes
            if (count > limit) throw TooBigException()
        }
    }

    /** Lee el fichero entero con las opciones; null si falta la columna `Code`. */
    private fun read(options: CodeImportUiState): CodeCsvParse? {
        val stream = checkNotNull(source).open() ?: throw ProblemException(ImportFileProblem.UNREADABLE)
        try {
            stream.use {
                val reader = TextDecoding.reader(LimitedInputStream(it, DelimitedReader.MAX_BYTES), options.encoding)
                val rows = DelimitedReader.rows(reader, options.separator).take(DelimitedReader.MAX_ROWS + 2).toList()
                // La cabecera cuenta como fila, como en S028/RF-3.
                if (DelimitedReader.limitExceeded(null, rows.size) == ImportLimit.TOO_MANY_ROWS) {
                    throw ProblemException(ImportFileProblem.TOO_MANY_ROWS)
                }
                return when (val result = CodeLibraryCsvReader.parse(rows.asSequence(), options.decimal)) {
                    CodeCsvResult.MissingCode -> null
                    is CodeCsvResult.Parsed -> result.parse
                }
            }
        } catch (_: TooBigException) {
            throw ProblemException(ImportFileProblem.TOO_BIG)
        } catch (_: IOException) {
            throw ProblemException(ImportFileProblem.UNREADABLE)
        } catch (_: SecurityException) {
            throw ProblemException(ImportFileProblem.UNREADABLE)
        }
    }

    /** Confirma: importa en una sola transacción o no importa nada (RF-14, RF-16). */
    fun confirm() {
        val current = mutable.value
        val parsed = parse
        if (!current.canImport || parsed == null) return
        mutable.update { it.copy(stage = CodeImportStage.IMPORTING, writeFailed = false) }
        viewModelScope.launch(io) {
            val target = when (val t = current.target) {
                CodeImportTarget.New -> ImportTarget.NewLibrary(baseName)
                is CodeImportTarget.Existing -> ImportTarget.Existing(t.libraryId)
            }
            val incoming = parsed.codes.map { it.code }
            val result = try {
                repository.importCodes(target, incoming, current.policy)
            } catch (_: SQLiteException) {
                null
            } catch (_: IOException) {
                null
            }
            when (result) {
                is CodeImportResult.Imported -> {
                    log.info(LogCategory.APP, "Importada 1 biblioteca de códigos")
                    mutable.update {
                        it.copy(
                            stage = CodeImportStage.RESULT,
                            outcome = CodeImportOutcome(result.libraryId, result.entered, result.skipped, parsed.invalid.size),
                        )
                    }
                }
                else -> mutable.update { it.copy(stage = CodeImportStage.WIZARD, writeFailed = true) }
            }
        }
    }

    private fun ParsedCode.toPreview() = CodePreviewRow(line, code.code, code.description, code.geometry, code.color, code.attributes.size)

    private companion object {
        const val HEAD_ROWS = 200
        const val PREVIEW_ROWS = 10
        const val MAX_LISTED = 1_000
    }
}
