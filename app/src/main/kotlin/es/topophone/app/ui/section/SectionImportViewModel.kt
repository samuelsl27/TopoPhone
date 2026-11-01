package es.topophone.app.ui.section

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import es.topophone.app.di.IoDispatcher
import es.topophone.app.ui.points.ImportSource
import es.topophone.core.formats.csv.DelimitedReader
import es.topophone.core.formats.csv.TextDecoding
import es.topophone.core.formats.csv.TextProbe
import es.topophone.core.formats.landxml.LandXmlCrossSectInfo
import es.topophone.core.formats.landxml.LandXmlCrossSectReader
import es.topophone.core.formats.landxml.LandXmlException
import es.topophone.core.formats.landxml.LandXmlProblem
import es.topophone.core.formats.section.SectionColumns
import es.topophone.core.formats.section.SectionCsvError
import es.topophone.core.formats.section.SectionCsvReader
import es.topophone.core.formats.section.SectionCsvRow
import es.topophone.core.formats.section.SectionRole
import es.topophone.core.formats.xml.XmlFormatException
import es.topophone.core.model.alignment.AlignmentSummary
import es.topophone.core.model.alignment.section.CrossSection
import es.topophone.core.model.alignment.section.HeightKind
import es.topophone.core.model.alignment.section.SectionIssue
import es.topophone.core.model.alignment.section.SectionKind
import es.topophone.core.model.log.AppLog
import es.topophone.core.model.log.LogCategory
import es.topophone.core.model.point.DelimitedRow
import es.topophone.core.model.point.ImportDecimal
import es.topophone.core.model.point.ImportEncoding
import es.topophone.core.model.point.ImportSeparator
import es.topophone.core.model.project.LinearUnit
import es.topophone.core.survey.SectionValidator
import es.topophone.data.alignment.AlignmentRepository
import es.topophone.data.project.ProjectRepository
import es.topophone.data.section.SectionRepository
import es.topophone.data.section.SectionSaveResult
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.io.StringReader
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** De dónde vienen las secciones que se importan (S050/RF-1). */
enum class SectionImportKind { CSV, LANDXML }

/** Por qué no se abre el asistente de importar secciones (S050/RF-4, RF-5). */
enum class SectionImportProblem {
    EMPTY,
    NOT_TEXT,
    NO_COLUMNS,
    TOO_BIG,
    TOO_MANY_ROWS,
    NOT_XML,
    NOT_LANDXML,
    UNSUPPORTED_VERSION,
    NO_SECTIONS,
    NO_ALIGNMENT,
    UNREADABLE,
}

enum class SectionImportStage { LOADING, PROBLEM, CONFIG, DONE }

/** Una sección que no se guardó y por qué (S050/RF-7): su PK, si era de diseño o de terreno, y el motivo. */
data class RejectedSection(val chainageM: Double, val kind: SectionKind, val issue: SectionIssue)

/** Lo que muestra el asistente de importar secciones de un CSV o un LandXML (S050/RF-4, RF-5, RF-7). */
data class SectionImportUiState(
    val stage: SectionImportStage = SectionImportStage.LOADING,
    val kind: SectionImportKind = SectionImportKind.CSV,
    val fileName: String = "",
    val problem: SectionImportProblem? = null,
    /** La versión del LandXML rechazado (`2.0`…), para decirla en el aviso. */
    val version: String? = null,
    val alignment: AlignmentSummary? = null,
    val projectUnit: LinearUnit = LinearUnit.METRE,
    /** La unidad en que vienen el PK, los desplazamientos y las cotas del fichero (por omisión la del proyecto o la declarada). */
    val unit: LinearUnit = LinearUnit.METRE,
    val declaredUnit: LinearUnit? = null,
    /** Si la cota de un CSV es absoluta o una diferencia con la rasante (RF-4); el LandXML es siempre absoluta. */
    val heightKind: HeightKind = HeightKind.ABSOLUTE,
    val encoding: ImportEncoding = ImportEncoding.UTF8,
    val separator: ImportSeparator = ImportSeparator.COMMA,
    val decimal: ImportDecimal = ImportDecimal.POINT,
    val hasHeader: Boolean = false,
    val firstRow: List<String> = emptyList(),
    val columns: SectionColumns = SectionColumns(emptyList()),
    /** Las primeras filas leídas con la asignación de ahora. */
    val preview: List<SectionCsvRow> = emptyList(),
    val rowCount: Int = 0,
    val errors: List<SectionCsvError<SectionRole>> = emptyList(),
    /** Las secciones del LandXML, por PK, y las que se importan (por omisión todas) (RF-5). */
    val sections: List<LandXmlCrossSectInfo> = emptyList(),
    val selected: Set<Int> = emptySet(),
    val working: Boolean = false,
    val reading: Boolean = false,
    val writeError: Boolean = false,
    val savedCount: Int = 0,
    val rejected: List<RejectedSection> = emptyList(),
) {
    /** El separador y el signo decimal no pueden ser los dos la coma (S028/RF-8). */
    val decimalConflict: Boolean get() = separator == ImportSeparator.COMMA && decimal == ImportDecimal.COMMA

    val canImport: Boolean
        get() = stage == SectionImportStage.CONFIG &&
            !working &&
            !reading &&
            when (kind) {
                SectionImportKind.CSV -> columns.problem == null && errors.isEmpty() && rowCount > 0 && !decimalConflict
                SectionImportKind.LANDXML -> selected.isNotEmpty()
            }
}

/**
 * Importa las secciones de un CSV o de un LandXML a un eje (S050/RF-4, RF-5, RF-7): comprueba el fichero por su contenido, propone las
 * columnas y las opciones como en S028, deja elegir la unidad y si la cota es absoluta o relativa, y guarda en una sola transacción
 * las secciones que valen; las que no, quedan en el resumen con su PK y el motivo.
 */
@HiltViewModel
class SectionImportViewModel @Inject constructor(
    private val sections: SectionRepository,
    private val alignments: AlignmentRepository,
    private val projects: ProjectRepository,
    @param:IoDispatcher private val io: CoroutineDispatcher,
    private val log: AppLog = AppLog.NONE,
) : ViewModel() {
    private val mutable = MutableStateFlow(SectionImportUiState())
    val state: StateFlow<SectionImportUiState> = mutable.asStateFlow()

    private var started = false
    private var source: ImportSource? = null
    private var parsedRows: List<SectionCsvRow> = emptyList()
    private var unitTouched = false

    /** El tamaño máximo que se lee de un LandXML cuyo tamaño no se conocía; los tests lo bajan. */
    internal var maxBytes: Long = LandXmlCrossSectReader.MAX_BYTES

    /** Prepara el asistente con [source] para el eje [alignmentId]; solo la primera vez. */
    fun start(projectId: String, alignmentId: String, kind: SectionImportKind, source: ImportSource) {
        if (started) return
        started = true
        this.source = source
        mutable.value = SectionImportUiState(kind = kind, fileName = source.name)
        viewModelScope.launch(io) {
            when (kind) {
                SectionImportKind.CSV -> loadCsv(projectId, alignmentId, source)
                SectionImportKind.LANDXML -> loadLandXml(projectId, alignmentId, source)
            }
        }
    }

    private fun problem(problem: SectionImportProblem, version: String? = null) {
        mutable.update { it.copy(stage = SectionImportStage.PROBLEM, problem = problem, version = version, reading = false) }
    }

    private suspend fun axisOf(projectId: String, alignmentId: String): AlignmentSummary? =
        alignments.summaries(projectId).first().firstOrNull { it.id == alignmentId }

    // ----------------------------------------------------------------------------------------------------------------- CSV
    private suspend fun loadCsv(projectId: String, alignmentId: String, source: ImportSource) {
        val project = projects.project(projectId) ?: return problem(SectionImportProblem.UNREADABLE)
        val axis = axisOf(projectId, alignmentId) ?: return problem(SectionImportProblem.NO_ALIGNMENT)
        if (DelimitedReader.limitExceeded(source.sizeBytes, rows = 0) != null) return problem(SectionImportProblem.TOO_BIG)
        val sample = try {
            source.open()?.use { TextDecoding.readSample(it) }
        } catch (_: IOException) {
            null
        } catch (_: SecurityException) {
            null
        } ?: return problem(SectionImportProblem.UNREADABLE)
        val encoding = when (val probe = TextDecoding.probe(sample.bytes, sample.truncated)) {
            TextProbe.Empty -> return problem(SectionImportProblem.EMPTY)
            TextProbe.Binary -> return problem(SectionImportProblem.NOT_TEXT)
            is TextProbe.Text -> probe.encoding
        }
        val text = TextDecoding.decode(sample.bytes, encoding)
        val separator = DelimitedReader.detectSeparator(text)
        val head = DelimitedReader.rows(StringReader(text), separator).take(HEAD_ROWS).toList()
        val first = head.firstOrNull()?.cells.orEmpty()
        if (first.size < 3) return problem(SectionImportProblem.NO_COLUMNS)
        val hasHeader = SectionCsvReader.hasHeader(first)
        val body = if (hasHeader) head.drop(1) else head
        mutable.value = SectionImportUiState(
            stage = SectionImportStage.CONFIG,
            kind = SectionImportKind.CSV,
            fileName = source.name,
            alignment = axis,
            projectUnit = project.linearUnit,
            unit = project.linearUnit,
            encoding = encoding,
            separator = separator,
            decimal = SectionCsvReader.detectDecimal(body, separator),
            hasHeader = hasHeader,
            firstRow = first,
            columns = SectionCsvReader.suggest(first, hasHeader),
            reading = true,
        )
        reparse()
    }

    fun setUnit(unit: LinearUnit) {
        unitTouched = true
        mutable.update { it.copy(unit = unit) }
    }

    fun setHeightKind(heightKind: HeightKind) = mutable.update { it.copy(heightKind = heightKind) }

    fun setEncoding(encoding: ImportEncoding) = change { it.copy(encoding = encoding) }

    fun setSeparator(separator: ImportSeparator) = change { it.copy(separator = separator) }

    fun setDecimal(decimal: ImportDecimal) = change { it.copy(decimal = decimal) }

    fun setHasHeader(hasHeader: Boolean) = change { it.copy(hasHeader = hasHeader) }

    /** Cambia el papel de la columna [index]; un papel no se puede repetir, así que el que lo tenía lo pierde (RF-4). */
    fun setRole(index: Int, role: SectionRole) = change { current ->
        val roles = current.columns.roles.mapIndexed { i, r ->
            if (i != index &&
                role != SectionRole.IGNORE &&
                r == role
            ) {
                SectionRole.IGNORE
            } else {
                r
            }
        }
        current.copy(columns = SectionColumns(roles).withRole(index, role))
    }

    private fun change(update: (SectionImportUiState) -> SectionImportUiState) {
        mutable.update { update(it).copy(rejected = emptyList(), writeError = false, reading = true) }
        viewModelScope.launch(io) { reparse() }
    }

    /** Lee el fichero entero con las opciones de ahora y deja la vista previa y los errores (RF-4). */
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
        } ?: return problem(SectionImportProblem.UNREADABLE)
        if (rows.size > DelimitedReader.MAX_ROWS) return problem(SectionImportProblem.TOO_MANY_ROWS)
        val first = rows.firstOrNull()?.cells.orEmpty()
        val body: List<DelimitedRow> = if (current.hasHeader) rows.drop(1) else rows
        val columns = if (current.columns.roles.size == first.size) current.columns else SectionCsvReader.suggest(first, current.hasHeader)
        if (columns.problem != null) {
            parsedRows = emptyList()
            mutable.update {
                it.copy(firstRow = first, columns = columns, preview = emptyList(), rowCount = 0, errors = emptyList(), reading = false)
            }
            return
        }
        val result = SectionCsvReader.parse(body.asSequence(), columns, current.decimal)
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

    // -------------------------------------------------------------------------------------------------------------- LandXML
    private suspend fun loadLandXml(projectId: String, alignmentId: String, source: ImportSource) {
        val project = projects.project(projectId) ?: return problem(SectionImportProblem.UNREADABLE)
        val axis = axisOf(projectId, alignmentId) ?: return problem(SectionImportProblem.NO_ALIGNMENT)
        val size = source.sizeBytes
        if (size != null && size > maxBytes) return problem(SectionImportProblem.TOO_BIG)
        if (size == 0L) return problem(SectionImportProblem.EMPTY)
        val scan = try {
            val stream = source.open() ?: return problem(SectionImportProblem.UNREADABLE)
            LimitedInputStream(stream, maxBytes).use { LandXmlCrossSectReader.scan(it) }
        } catch (_: TooBigException) {
            return problem(SectionImportProblem.TOO_BIG)
        } catch (e: LandXmlException) {
            return when (e.problem) {
                LandXmlProblem.NOT_LANDXML -> problem(SectionImportProblem.NOT_LANDXML)
                LandXmlProblem.UNSUPPORTED_VERSION -> problem(SectionImportProblem.UNSUPPORTED_VERSION, e.version)
            }
        } catch (_: XmlFormatException) {
            return problem(SectionImportProblem.NOT_XML)
        } catch (_: IOException) {
            return problem(SectionImportProblem.UNREADABLE)
        } catch (_: SecurityException) {
            return problem(SectionImportProblem.UNREADABLE)
        }
        if (scan.sections.isEmpty()) return problem(SectionImportProblem.NO_SECTIONS)
        mutable.value = SectionImportUiState(
            stage = SectionImportStage.CONFIG,
            kind = SectionImportKind.LANDXML,
            fileName = source.name,
            alignment = axis,
            projectUnit = project.linearUnit,
            unit = if (!unitTouched && scan.declaredUnit != null) scan.declaredUnit!! else project.linearUnit,
            declaredUnit = scan.declaredUnit,
            sections = scan.sections,
            selected = scan.sections.map { it.index }.toSet(),
        )
    }

    /** Marca o desmarca la sección [index] del LandXML (RF-5). */
    fun toggle(index: Int) = mutable.update {
        it.copy(selected = if (index in it.selected) it.selected - index else it.selected + index)
    }

    /** Marca o desmarca todas las secciones de un tipo: las de diseño o las de terreno (RF-5). */
    fun selectKind(kind: SectionKind, selected: Boolean) = mutable.update { current ->
        val ofKind = current.sections.filter { it.kind == kind }.map { it.index }.toSet()
        current.copy(selected = if (selected) current.selected + ofKind else current.selected - ofKind)
    }

    // --------------------------------------------------------------------------------------------------------------- guardar
    /** Guarda las secciones que valen; las que no, quedan en el resumen con su PK y el motivo (RF-7). */
    fun import() {
        val current = mutable.value
        if (!current.canImport) return
        val axis = current.alignment ?: return
        mutable.update { it.copy(working = true, rejected = emptyList(), writeError = false) }
        viewModelScope.launch(io) {
            val all = when (current.kind) {
                SectionImportKind.CSV -> SectionCsvReader.toSections(parsedRows, current.heightKind)
                SectionImportKind.LANDXML -> try {
                    val stream = source?.open()
                    if (stream ==
                        null
                    ) {
                        null
                    } else {
                        LimitedInputStream(stream, maxBytes).use { LandXmlCrossSectReader.read(it, current.selected) }
                    }
                } catch (_: IOException) {
                    null
                } catch (_: XmlFormatException) {
                    null
                } catch (_: TooBigException) {
                    null
                }
            }
            if (all == null) {
                mutable.update { it.copy(working = false, writeError = true) }
                return@launch
            }
            val converted = all.map { toMetres(it, current.unit) }
            val rejected = ArrayList<RejectedSection>()
            val valid = ArrayList<CrossSection>()
            for (s in converted) {
                val issue = SectionValidator.validate(s, axis.startChainage, axis.endChainage)
                if (issue == null) valid += s else rejected += RejectedSection(s.chainage, s.kind, issue)
            }
            val saved = if (valid.isEmpty()) {
                0
            } else {
                when (val result = sections.addSections(axis.id, valid)) {
                    is SectionSaveResult.Saved -> result.ids.size
                    else -> {
                        mutable.update { it.copy(working = false, writeError = true) }
                        return@launch
                    }
                }
            }
            if (saved > 0) log.info(LogCategory.APP, "Secciones importadas: $saved")
            mutable.update { it.copy(working = false, stage = SectionImportStage.DONE, savedCount = saved, rejected = rejected) }
        }
    }

    private fun toMetres(s: CrossSection, unit: LinearUnit): CrossSection = if (unit == LinearUnit.METRE) {
        s
    } else {
        s.copy(
            chainage = unit.toMetres(s.chainage),
            points = s.points.map { it.copy(offsetM = unit.toMetres(it.offsetM), heightM = unit.toMetres(it.heightM)) },
        )
    }

    private class TooBigException : IOException("Fichero demasiado grande")

    private class LimitedInputStream(input: InputStream, private val limit: Long) : FilterInputStream(input) {
        private var total = 0L

        override fun read(): Int = super.read().also { if (it >= 0) advance(1) }

        override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, len).also { if (it > 0) advance(it.toLong()) }

        private fun advance(n: Long) {
            total += n
            if (total > limit) throw TooBigException()
        }
    }

    private companion object {
        const val HEAD_ROWS = 200
        const val PREVIEW_ROWS = 8
    }
}
