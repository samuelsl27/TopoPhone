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
import es.topophone.core.model.log.LogCategory
import es.topophone.core.model.point.ColumnMapping
import es.topophone.core.model.point.ColumnRole
import es.topophone.core.model.point.DelimitedRow
import es.topophone.core.model.point.DuplicatePolicy
import es.topophone.core.model.point.EntryForm
import es.topophone.core.model.point.HeightKind
import es.topophone.core.model.point.ImportCoordinates
import es.topophone.core.model.point.ImportCounts
import es.topophone.core.model.point.ImportDecimal
import es.topophone.core.model.point.ImportEncoding
import es.topophone.core.model.point.ImportErrorReason
import es.topophone.core.model.point.ImportOptions
import es.topophone.core.model.point.ImportPreviewRow
import es.topophone.core.model.point.ImportRowError
import es.topophone.core.model.point.ImportSeparator
import es.topophone.core.model.project.LinearUnit
import es.topophone.core.model.project.Project
import es.topophone.core.model.project.ProjectCrs
import es.topophone.core.survey.ImportAnalysis
import es.topophone.core.survey.ImportAnalyzer
import es.topophone.core.survey.ImportReportText
import es.topophone.data.point.PointRepository
import es.topophone.data.point.PointSaveResult
import es.topophone.data.project.ProjectRepository
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.StringReader
import java.util.UUID
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
import kotlinx.coroutines.withContext

/**
 * El fichero que se importa (S028/RF-1): su nombre, su tamaño si el selector lo da y cómo abrirlo cada vez que hace falta, sin
 * copiarlo (RNF-T5). [open] devuelve null si no se puede abrir.
 */
class ImportSource(val name: String, val sizeBytes: Long?, val open: () -> InputStream?)

/** Por qué no se abre el asistente (S028/RF-2, RF-3). */
enum class ImportFileProblem { EMPTY, NOT_TEXT, NO_COLUMNS, TOO_BIG, TOO_MANY_ROWS, UNREADABLE }

/** En qué punto está el asistente. */
enum class ImportStage { LOADING, PROBLEM, WIZARD, IMPORTING, RESULT }

/**
 * El resultado de una importación (S028/RF-28, RF-29): los recuentos de esa pasada, la importación para poder deshacerla y, si se
 * deshizo, cuántos puntos pasaron a la papelera.
 */
data class PointImportResult(val counts: ImportCounts, val importId: String, val undone: Boolean = false, val undoneCount: Int = 0) {
    val canUndo: Boolean get() = !undone && counts.valid > 0
}

/** Los textos del informe de errores, que son de la interfaz (`strings.xml`) y no del módulo de dominio (S028/RF-24). */
class ImportReportLabels(
    val title: String,
    val summary: String,
    val empty: String,
    val lineFormat: (line: Int, reason: String) -> String,
    val reason: (ImportErrorReason) -> String,
)

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
    /** El último intento de importar no pudo escribir en la base de datos: no se ha importado nada (RF-26). */
    val writeFailed: Boolean = false,
    val result: PointImportResult? = null,
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

    internal var clock: () -> Long = System::currentTimeMillis
    internal var newId: () -> String = { UUID.randomUUID().toString() }

    /** La asignación la cambió el usuario a mano: no se vuelve a proponer sola (S028/RF-10). */
    private var mappingTouched = false

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
        schedule(reread = false, debounce = false)
    }

    // --- Las opciones (S028/RF-4 a RF-20, RF-22). Cada cambio recalcula la vista previa y los recuentos. ---

    fun setEncoding(encoding: ImportEncoding) = change(reread = true) { it.copy(encoding = encoding) }

    fun setSeparator(separator: ImportSeparator) = change(reread = true) { it.copy(separator = separator) }

    fun setDecimal(decimal: ImportDecimal) = change { it.copy(decimal = decimal) }

    /** La primera fila es o no una cabecera; sin tocar a mano la asignación, se propone otra (RF-9, RF-10). */
    fun setHasHeader(hasHeader: Boolean) =
        change { it.copy(hasHeader = hasHeader, mapping = suggestedMapping(it.copy(hasHeader = hasHeader))) }

    /** Cambia el papel de la columna [index] (RF-11). */
    fun setColumnRole(index: Int, role: ColumnRole) {
        mappingTouched = true
        change { it.copy(mapping = it.mapping.withRole(index, role)) }
    }

    /** Nombre, Este, Norte, altura y descripción (RF-11). */
    fun applyPenzd() {
        mappingTouched = true
        change { it.copy(mapping = ColumnMapping.penzd(mutable.value.columnCount)) }
    }

    /** Nombre, Norte, Este, altura y descripción (RF-11). */
    fun applyPnezd() {
        mappingTouched = true
        change { it.copy(mapping = ColumnMapping.pnezd(mutable.value.columnCount)) }
    }

    /** El SC de las coordenadas del fichero y su altura; la elipsoidal solo vale en geográficas (RF-12, RF-13). */
    fun setCoordinates(form: EntryForm, heightKind: HeightKind) = change {
        val coordinates = ImportCoordinates(form, if (form == EntryForm.PROJECT_CRS) HeightKind.PROJECT else heightKind)
        val changed = it.copy(coordinates = coordinates)
        changed.copy(mapping = suggestedMapping(changed))
    }

    fun setUnit(unit: LinearUnit) = change { it.copy(unit = unit) }

    fun setZeroAsNoHeight(zeroAsNoHeight: Boolean) = change { it.copy(zeroAsNoHeight = zeroAsNoHeight) }

    fun setDuplicates(policy: DuplicatePolicy) = change { it.copy(duplicates = policy) }

    /** Lo que se propone con las opciones de ahora; una asignación hecha a mano se respeta (RF-10). */
    private fun suggestedMapping(options: ImportOptions): ColumnMapping {
        if (mappingTouched) return options.mapping
        val header = mutable.value.headerCells
        return ColumnSuggester.suggest(if (options.hasHeader) header else null, mutable.value.columnCount, options.coordinates)
    }

    private fun change(reread: Boolean = false, transform: (ImportOptions) -> ImportOptions) {
        if (mutable.value.stage != ImportStage.WIZARD) return
        mutable.update { it.copy(options = transform(it.options)) }
        schedule(reread = reread, debounce = true)
    }

    /**
     * Con otra codificación o separador cambian las columnas, la cabecera y la asignación propuesta: se vuelven a leer las primeras
     * filas (RF-4, RF-5).
     */
    private fun refreshColumns() {
        val options = mutable.value.options
        val head = readHead(options)
        val columnCount = head.take(COLUMN_ROWS).maxOfOrNull { it.cells.size } ?: 0
        val firstRow = head.firstOrNull()?.cells.orEmpty()
        val hasHeader = columnCount >= 2 && HeaderDetector.isHeader(firstRow)
        mappingTouched = false
        mutable.update {
            val headerCells = List(columnCount) { index -> firstRow.getOrElse(index) { "" } }
            val mapping = ColumnSuggester.suggest(if (hasHeader) headerCells else null, columnCount, it.options.coordinates)
            it.copy(
                options = it.options.copy(hasHeader = hasHeader, mapping = mapping),
                columnCount = columnCount,
                headerCells = headerCells,
            )
        }
    }

    private fun readHead(options: ImportOptions): List<DelimitedRow> {
        val stream = source?.open() ?: throw IOException("No se puede abrir el fichero")
        return stream.use { DelimitedReader.rows(TextDecoding.reader(it, options.encoding), options.separator).take(HEAD_ROWS).toList() }
    }

    /** Recorre el fichero entero con [options] (S028/RF-21). [maxErrors] limita la lista de errores, no su recuento. */
    private fun analyzeFile(options: ImportOptions, maxErrors: Int, checkCancelled: () -> Unit = {}): ImportAnalysis {
        val project = checkNotNull(project)
        val stream = checkNotNull(source).open() ?: throw IOException("No se puede abrir el fichero")
        return stream.use {
            analyzer.analyze(
                rows = DelimitedReader.rows(TextDecoding.reader(it, options.encoding), options.separator),
                options = options,
                crs = project.crs,
                heightSystem = project.heightSystem,
                takenNames = takenNames,
                maxErrors = maxErrors,
                checkCancelled = checkCancelled,
            )
        }
    }

    /**
     * Analiza el fichero entero con las opciones de ahora; uno nuevo cancela el anterior y, tras un cambio, espera [debounceMs] por si
     * vienen más (S028/RF-22). Con [reread], antes vuelve a leer las columnas.
     */
    private fun schedule(reread: Boolean, debounce: Boolean) {
        if (project == null || source == null) return
        analysisJob?.cancel()
        mutable.update { it.copy(analyzing = true) }
        analysisJob = viewModelScope.launch(io) {
            if (debounce && debounceMs > 0) delay(debounceMs)
            val snapshot: ImportOptions
            val analysis = try {
                if (reread) refreshColumns()
                snapshot = mutable.value.options
                analyzeFile(snapshot, ImportAnalyzer.MAX_ERRORS) { ensureActive() }
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

    // --- Importar, deshacer y el informe (S028/RF-24 a RF-30, RF-34) ---

    /**
     * Crea los puntos de las filas válidas en una sola transacción (RF-25). Recorre el fichero otra vez con las mismas opciones y los
     * nombres del proyecto de ahora; si no se puede escribir, no crea ninguno y lo dice (RF-26).
     */
    fun import() {
        val current = mutable.value
        if (!current.canImport) return
        val project = project ?: return
        val source = source ?: return
        analysisJob?.cancel()
        mutable.update { it.copy(stage = ImportStage.IMPORTING, writeFailed = false) }
        viewModelScope.launch(io) {
            val options = current.options
            val importId = newId()
            val batch = try {
                takenNames = points.names(projectId)
                val stream = source.open() ?: throw IOException("No se puede abrir el fichero")
                stream.use {
                    analyzer.collect(
                        rows = DelimitedReader.rows(TextDecoding.reader(it, options.encoding), options.separator),
                        options = options,
                        crs = project.crs,
                        heightSystem = project.heightSystem,
                        takenNames = takenNames,
                        projectId = projectId,
                        importId = importId,
                        createdAtMs = clock(),
                        newId = newId,
                    )
                }
            } catch (_: IOException) {
                return@launch problem(ImportFileProblem.UNREADABLE)
            } catch (_: SecurityException) {
                return@launch problem(ImportFileProblem.UNREADABLE)
            }
            if (options.duplicates == DuplicatePolicy.CANCEL && batch.counts.repeated > 0) {
                // Entre el análisis y el botón ha aparecido un repetido: se vuelve a analizar y no se importa (RF-17).
                mutable.update { it.copy(stage = ImportStage.WIZARD) }
                return@launch schedule(reread = false, debounce = false)
            }
            val saved = try {
                points.createAll(batch.points)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
            if (saved == PointSaveResult.Saved) {
                log.info(LogCategory.POINTS, importedMessage(batch.counts))
                mutable.update {
                    it.copy(stage = ImportStage.RESULT, result = PointImportResult(batch.counts, importId), writeFailed = false)
                }
            } else {
                log.error(LogCategory.POINTS, "Importación CSV/TXT fallida: no se pudo escribir en la base de datos")
                mutable.update { it.copy(stage = ImportStage.WIZARD, writeFailed = true) }
                takenNames = runCatching { points.names(projectId) }.getOrDefault(takenNames) // pueden haber cambiado
                schedule(reread = false, debounce = false)
            }
        }
    }

    private fun importedMessage(counts: ImportCounts) =
        "Importación CSV/TXT: ${counts.valid} importados, ${counts.skipped} saltados, ${counts.renamed} renombrados, ${counts.errors} con error"

    /** Manda a la papelera los puntos de la importación que siguen en el proyecto (RF-29). La confirmación la pide la pantalla. */
    fun undo() {
        val result = mutable.value.result ?: return
        if (!result.canUndo) return
        viewModelScope.launch(io) {
            val moved = points.trashImport(result.importId)
            log.info(LogCategory.POINTS, "Importación CSV/TXT deshecha: $moved puntos a la papelera")
            mutable.update { it.copy(result = result.copy(undone = true, undoneCount = moved)) }
        }
    }

    /**
     * Escribe en [out], en UTF-8, el informe con todas las filas erróneas del fichero con las opciones de ahora (RF-24); false si no
     * se ha podido leer el fichero o escribir. No cambia nada.
     */
    suspend fun writeReport(out: OutputStream, labels: ImportReportLabels): Boolean = withContext(io) {
        try {
            val options = mutable.value.options
            val errors = analyzeFile(options, maxErrors = Int.MAX_VALUE).errors
            val text = ImportReportText.build(
                title = labels.title,
                summary = labels.summary,
                errors = errors,
                reasonText = labels.reason,
                lineFormat = labels.lineFormat,
                emptyText = labels.empty,
            )
            out.bufferedWriter(Charsets.UTF_8).use { it.write(text) }
            true
        } catch (_: IOException) {
            false
        } catch (_: SecurityException) {
            false
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
