package es.topophone.app.ui.points

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import es.topophone.app.di.IoDispatcher
import es.topophone.core.formats.csv.ColumnSuggester
import es.topophone.core.formats.csv.DecimalDetector
import es.topophone.core.formats.csv.DelimitedReader
import es.topophone.core.formats.csv.HeaderDetector
import es.topophone.core.formats.csv.ImportLimit
import es.topophone.core.formats.csv.TextDecoding
import es.topophone.core.formats.csv.TextProbe
import es.topophone.core.model.log.AppLog
import es.topophone.core.model.point.ImportCoordinates
import es.topophone.core.model.point.ImportCounts
import es.topophone.core.model.point.ImportOptions
import es.topophone.core.model.point.ImportPreviewRow
import es.topophone.core.model.point.ImportRowError
import es.topophone.core.model.project.Project
import es.topophone.core.model.project.ProjectCrs
import es.topophone.core.survey.ImportAnalyzer
import es.topophone.data.point.PointRepository
import es.topophone.data.project.ProjectRepository
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.io.StringReader
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * El fichero que se importa (S028/RF-1): su nombre, su tamaño si el selector lo da y cómo abrirlo cada vez que hace falta, sin
 * copiarlo (RNF-T5). [open] devuelve null si no se puede abrir.
 */
class ImportSource(val name: String, val sizeBytes: Long?, val open: () -> InputStream?)

/** Por qué no se abre el asistente (S028/RF-2, RF-3). */
enum class ImportFileProblem { EMPTY, NOT_TEXT, NO_COLUMNS, TOO_BIG, TOO_MANY_ROWS, UNREADABLE }

/** En qué punto está el asistente. */
enum class ImportStage { LOADING, PROBLEM, WIZARD }

/** Lo que muestra el asistente de importación (S028). */
data class PointImportUiState(
    val stage: ImportStage = ImportStage.LOADING,
    val fileName: String = "",
    val fileProblem: ImportFileProblem? = null,
    val projectName: String = "",
    /** Un proyecto en ED50 solo admite altitud (S011/RF-17; S028/RF-13). */
    val ed50: Boolean = false,
    val options: ImportOptions = ImportOptions(),
    val columnCount: Int = 0,
    /** La primera fila del fichero, para dar nombre a cada columna. */
    val headerCells: List<String> = emptyList(),
    val analyzing: Boolean = false,
    val preview: List<ImportPreviewRow> = emptyList(),
    val counts: ImportCounts = ImportCounts(),
    val errors: List<ImportRowError> = emptyList(),
    val blockedByDuplicates: Boolean = false,
) {
    /** Hay algo que importar y nada lo impide: la asignación vale, las comas no se pisan y no se cancela por un repetido (RF-8, RF-11, RF-17). */
    val canImport: Boolean
        get() = stage == ImportStage.WIZARD &&
            !analyzing &&
            options.mapping.isValid &&
            !options.decimalConflict &&
            options.coordinates.isAllowed(ed50) &&
            !blockedByDuplicates &&
            counts.valid > 0
}

/**
 * El asistente de importación de puntos de un CSV o TXT (S028): lee el fichero por flujo, detecta sus opciones, analiza el fichero
 * entero con ellas y muestra la vista previa y los recuentos sin crear ningún punto (RF-21, RF-30).
 */
@HiltViewModel
class PointImportViewModel @Inject constructor(
    private val points: PointRepository,
    private val projects: ProjectRepository,
    @param:IoDispatcher private val io: CoroutineDispatcher,
    private val log: AppLog = AppLog.NONE,
) : ViewModel() {
    /** Lo que se espera tras un cambio de opción antes de analizar, para que teclear no lance un análisis por letra (RNF-T13). */
    internal var debounceMs: Long = DEBOUNCE_MS

    private val analyzer = ImportAnalyzer()
    private val mutable = MutableStateFlow(PointImportUiState())
    val state: StateFlow<PointImportUiState> = mutable.asStateFlow()

    private var started = false
    private var projectId = ""
    private var source: ImportSource? = null
    private var project: Project? = null
    private var takenNames: Set<String> = emptySet()
    private var analysisJob: Job? = null

    /** Prepara el asistente con [source] para el proyecto [projectId]; solo la primera vez (S028/RF-1). */
    fun start(projectId: String, source: ImportSource) {
        if (started) return
        started = true
        this.projectId = projectId
        this.source = source
        mutable.value = PointImportUiState(fileName = source.name)
        viewModelScope.launch(io) { load(projectId, source) }
    }

    private fun problem(problem: ImportFileProblem) {
        mutable.update { it.copy(stage = ImportStage.PROBLEM, fileProblem = problem, analyzing = false) }
    }

    private suspend fun load(projectId: String, source: ImportSource) {
        val project = projects.project(projectId) ?: return problem(ImportFileProblem.UNREADABLE)
        this.project = project
        takenNames = points.names(projectId)
        if (DelimitedReader.limitExceeded(source.sizeBytes, rows = 0) != null) return problem(ImportFileProblem.TOO_BIG)

        val sample = try {
            source.open()?.use { TextDecoding.readSample(it) }
        } catch (_: IOException) {
            null
        } catch (_: SecurityException) {
            null
        } ?: return problem(ImportFileProblem.UNREADABLE)

        val probe = TextDecoding.probe(sample.bytes, sample.truncated)
        val encoding = when (probe) {
            TextProbe.Empty -> return problem(ImportFileProblem.EMPTY)
            TextProbe.Binary -> return problem(ImportFileProblem.NOT_TEXT)
            is TextProbe.Text -> probe.encoding
        }
        val text = TextDecoding.decode(sample.bytes, encoding)
        val separator = DelimitedReader.detectSeparator(text)
        val head = DelimitedReader.rows(StringReader(text), separator).take(HEAD_ROWS).toList()
        val columnCount = head.take(COLUMN_ROWS).maxOfOrNull { it.cells.size } ?: 0
        if (columnCount < 2) return problem(ImportFileProblem.NO_COLUMNS)

        val rowCount = try {
            val stream = source.open() ?: return problem(ImportFileProblem.UNREADABLE)
            stream.use {
                DelimitedReader.countRows(TextDecoding.reader(LimitedInputStream(it, DelimitedReader.MAX_BYTES), encoding), separator)
            }
        } catch (_: TooBigException) {
            return problem(ImportFileProblem.TOO_BIG)
        } catch (_: IOException) {
            return problem(ImportFileProblem.UNREADABLE)
        } catch (_: SecurityException) {
            return problem(ImportFileProblem.UNREADABLE)
        }
        when (DelimitedReader.limitExceeded(source.sizeBytes, rowCount)) {
            ImportLimit.TOO_BIG -> return problem(ImportFileProblem.TOO_BIG)
            ImportLimit.TOO_MANY_ROWS -> return problem(ImportFileProblem.TOO_MANY_ROWS)
            null -> Unit
        }

        val decimal = DecimalDetector.detect(head, separator)
        val firstRow = head.first().cells
        val hasHeader = HeaderDetector.isHeader(firstRow)
        val coordinates = ImportCoordinates()
        val options = ImportOptions(
            encoding = encoding,
            separator = separator,
            decimal = decimal,
            hasHeader = hasHeader,
            mapping = ColumnSuggester.suggest(if (hasHeader) firstRow else null, columnCount, coordinates),
            coordinates = coordinates,
            unit = project.linearUnit,
        )
        mutable.value = PointImportUiState(
            stage = ImportStage.WIZARD,
            fileName = source.name,
            projectName = project.name,
            ed50 = project.crs == ProjectCrs.ED50_UTM29 || project.crs == ProjectCrs.ED50_UTM30 || project.crs == ProjectCrs.ED50_UTM31,
            options = options,
            columnCount = columnCount,
            headerCells = List(columnCount) { firstRow.getOrElse(it) { "" } },
            analyzing = true,
        )
        analyze(debounce = false)
    }

    /** Analiza el fichero entero con las opciones de ahora; un análisis nuevo cancela el anterior (S028/RF-22). */
    private fun analyze(debounce: Boolean) {
        val project = project ?: return
        val source = source ?: return
        analysisJob?.cancel()
        val snapshot = mutable.value.options
        mutable.update { it.copy(analyzing = true) }
        analysisJob = viewModelScope.launch(io) {
            if (debounce) delay(debounceMs)
            val analysis = try {
                val stream = source.open() ?: throw IOException("No se puede abrir el fichero")
                stream.use {
                    val rows = DelimitedReader.rows(TextDecoding.reader(it, snapshot.encoding), snapshot.separator)
                    analyzer.analyze(
                        rows = rows,
                        options = snapshot,
                        crs = project.crs,
                        heightSystem = project.heightSystem,
                        takenNames = takenNames,
                        checkCancelled = { ensureActive() },
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: IOException) {
                return@launch problem(ImportFileProblem.UNREADABLE)
            } catch (_: SecurityException) {
                return@launch problem(ImportFileProblem.UNREADABLE)
            }
            mutable.update {
                if (it.options != snapshot) {
                    it
                } else {
                    it.copy(
                        analyzing = false,
                        preview = analysis.preview,
                        counts = analysis.counts,
                        errors = analysis.errors,
                        blockedByDuplicates = analysis.blockedByDuplicates,
                    )
                }
            }
        }
    }

    /** Corta la lectura de un fichero que pasa del límite de tamaño. */
    private class TooBigException : IOException("Fichero demasiado grande")

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

    private companion object {
        const val DEBOUNCE_MS = 150L

        /** Las filas del principio con las que se detectan el signo decimal y la cabecera. */
        const val HEAD_ROWS = 200

        /** Las filas con las que se cuentan las columnas. */
        const val COLUMN_ROWS = 20
    }
}
