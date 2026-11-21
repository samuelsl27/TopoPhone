package es.topophone.app.ui.alignment

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import es.topophone.app.di.IoDispatcher
import es.topophone.app.ui.crs.LibrarySourceUi
import es.topophone.app.ui.points.ImportSource
import es.topophone.core.formats.landxml.LandXmlAlignmentInfo
import es.topophone.core.formats.landxml.LandXmlAlignmentReader
import es.topophone.core.geodesy.GeoPoint
import es.topophone.core.model.cad.CadSourceCrs
import es.topophone.core.model.log.AppLog
import es.topophone.core.model.log.LogCategory
import es.topophone.core.model.project.CorrectionsDatum
import es.topophone.core.model.project.LibraryCrs
import es.topophone.core.model.project.LinearUnit
import es.topophone.core.model.project.Project
import es.topophone.core.model.project.ProjectCrs
import es.topophone.data.alignment.AlignmentImportFailure
import es.topophone.data.alignment.AlignmentImportResult
import es.topophone.data.alignment.AlignmentImportSummary
import es.topophone.data.alignment.AlignmentImporter
import es.topophone.data.alignment.AlignmentScanResult
import es.topophone.data.project.ProjectRepository
import java.io.IOException
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Por qué no se abre la lista de alineaciones de un LandXML (S049/RF-3). */
enum class AlignmentFileProblem { EMPTY, TOO_BIG, NOT_XML, NOT_LANDXML, UNSUPPORTED_VERSION, NO_ALIGNMENT, UNREADABLE }

/** En qué punto está la importación de ejes de un LandXML. */
enum class AlignmentImportStage { LOADING, PROBLEM, CONFIG, IMPORTING, RESULT }

/** Lo que muestra la pantalla de importar los ejes de un LandXML (S049/RF-2, RF-3, RF-11, RF-13). */
data class AlignmentImportUiState(
    val stage: AlignmentImportStage = AlignmentImportStage.LOADING,
    val fileName: String = "",
    val fileProblem: AlignmentFileProblem? = null,
    /** La versión del LandXML rechazado (`2.0`…), para decirla en el aviso. */
    val version: String? = null,
    val projectName: String = "",
    val projectCrs: ProjectCrs = ProjectCrs.ETRS89_UTM30,
    /** El datum de las correcciones del proyecto, para construir el SC de origen de la biblioteca (S057/RF-2, RF-8). */
    val corrections: CorrectionsDatum = CorrectionsDatum.ETRS89,
    /** Sin centro del proyecto aquí: el cambio de datum es el más preciso de todos los del SC (S057/RF-8). */
    val centre: GeoPoint? = null,
    /** El SC de la biblioteca elegido como origen, con su cambio de datum y su confirmación (S057/RF-1, RF-8 a RF-10). */
    val library: LibrarySourceUi? = null,
    /** La unidad del proyecto, con la que se escriben los PK (RNF-T9). */
    val projectUnit: LinearUnit = LinearUnit.METRE,
    /** El sistema en que vienen las coordenadas del eje: el del proyecto, u otro UTM de la app (RF-11). */
    val source: CadSourceCrs = CadSourceCrs.Project,
    val unit: LinearUnit = LinearUnit.METRE,
    /** Los sistemas UTM entre los que se puede elegir «otro sistema» (RF-11). */
    val availableSources: List<ProjectCrs> = ProjectCrs.entries.filter { it.isUtm },
    val declaredUnit: LinearUnit? = null,
    /** Las alineaciones del fichero con su longitud, su PK inicial y si traen rasante (RF-2). */
    val alignments: List<LandXmlAlignmentInfo> = emptyList(),
    /** Las que se importan: por defecto todas (RF-2). */
    val selected: Set<Int> = emptySet(),
    val scanning: Boolean = false,
    val bytesRead: Long = 0,
    val totalBytes: Long? = null,
    /** Los bytes leídos durante la importación, para el progreso. */
    val importBytes: Long = 0,
    /** Por qué la última importación no guardó nada; null si no ha fallado. */
    val failure: AlignmentImportFailure? = null,
    /** El usuario canceló la última importación: no se guardó nada. */
    val cancelled: Boolean = false,
    val summary: AlignmentImportSummary? = null,
) {
    /** Hay algo que importar y nada lo impide: se ha explorado el fichero y se ha marcado alguna alineación (RF-2). */
    val canImport: Boolean
        get() = stage == AlignmentImportStage.CONFIG &&
            !scanning &&
            selected.isNotEmpty() &&
            (source !is CadSourceCrs.Library || library?.ready == true)
}

/**
 * La importación de los ejes de un LandXML a un proyecto (S049/RF-2 a RF-5, RF-11 a RF-13): comprueba el fichero por su contenido y su
 * tamaño, lo explora para dar sus alineaciones y su unidad, deja marcar cuáles se importan y elegir el sistema y la unidad, importa en
 * segundo plano con progreso y deja el resumen de lo importado y lo ignorado.
 */
@HiltViewModel
class AlignmentImportViewModel @Inject constructor(
    private val importer: AlignmentImporter,
    private val projects: ProjectRepository,
    @param:IoDispatcher private val io: CoroutineDispatcher,
    private val log: AppLog = AppLog.NONE,
) : ViewModel() {
    private val mutable = MutableStateFlow(AlignmentImportUiState())
    val state: StateFlow<AlignmentImportUiState> = mutable.asStateFlow()

    private var started = false
    private var project: Project? = null
    private var source: ImportSource? = null
    private var unitTouched = false

    /** El tamaño máximo que se lee de un fichero cuyo tamaño no se conocía de antemano (RF-3); los tests lo bajan. */
    internal var maxBytes: Long = LandXmlAlignmentReader.MAX_BYTES

    /** Prepara la importación de [source] en el proyecto [projectId]; solo la primera vez. */
    fun start(projectId: String, source: ImportSource) {
        if (started) return
        started = true
        this.source = source
        mutable.value = AlignmentImportUiState(fileName = source.name, totalBytes = source.sizeBytes)
        viewModelScope.launch(io) { load(projectId, source) }
    }

    private fun problem(problem: AlignmentFileProblem, version: String? = null) {
        mutable.update { it.copy(stage = AlignmentImportStage.PROBLEM, fileProblem = problem, version = version, scanning = false) }
    }

    private suspend fun load(projectId: String, source: ImportSource) {
        val project = projects.project(projectId) ?: return problem(AlignmentFileProblem.UNREADABLE)
        this.project = project
        val size = source.sizeBytes
        if (size != null && size > maxBytes) return problem(AlignmentFileProblem.TOO_BIG)
        if (size == 0L) return problem(AlignmentFileProblem.EMPTY)
        mutable.value = AlignmentImportUiState(
            stage = AlignmentImportStage.CONFIG,
            fileName = source.name,
            projectName = project.name,
            projectCrs = project.crs,
            corrections = project.correctionsDatum,
            projectUnit = project.linearUnit,
            unit = project.linearUnit,
            scanning = true,
            totalBytes = size,
        )
        scan(source)
    }

    private suspend fun scan(source: ImportSource) {
        var exceeded = false
        val result = try {
            importer.scan(
                openStream = { source.open() ?: throw IOException("No se puede abrir el fichero") },
                onProgress = { read ->
                    if (read > maxBytes) {
                        exceeded = true
                        throw TooBigException()
                    }
                    mutable.update { it.copy(bytesRead = read) }
                },
            )
        } catch (_: TooBigException) {
            return problem(AlignmentFileProblem.TOO_BIG)
        } catch (_: IOException) {
            return problem(AlignmentFileProblem.UNREADABLE)
        } catch (_: SecurityException) {
            return problem(AlignmentFileProblem.UNREADABLE)
        }
        // El importador convierte cualquier fallo de lectura en un resultado: si se pasó del límite, ese fallo es este.
        if (exceeded) return problem(AlignmentFileProblem.TOO_BIG)
        val scan = when (result) {
            is AlignmentScanResult.Failed -> return problem(result.reason.toProblem(), result.version)
            is AlignmentScanResult.Found -> result.scan
        }
        if (scan.alignments.isEmpty()) return problem(AlignmentFileProblem.NO_ALIGNMENT)
        mutable.update { current ->
            val declared = scan.declaredUnit
            current.copy(
                alignments = scan.alignments,
                selected = scan.alignments.map { it.index }.toSet(),
                declaredUnit = declared,
                unit = if (!unitTouched && declared != null) declared else current.unit,
                scanning = false,
            )
        }
    }

    private fun AlignmentImportFailure.toProblem() = when (this) {
        AlignmentImportFailure.NOT_LANDXML -> AlignmentFileProblem.NOT_LANDXML
        AlignmentImportFailure.UNSUPPORTED_VERSION -> AlignmentFileProblem.UNSUPPORTED_VERSION
        AlignmentImportFailure.MALFORMED -> AlignmentFileProblem.NOT_XML
        AlignmentImportFailure.NO_ALIGNMENT -> AlignmentFileProblem.NO_ALIGNMENT
        else -> AlignmentFileProblem.UNREADABLE
    }

    /** Marca o desmarca la alineación [index] del fichero (RF-2). */
    fun toggle(index: Int) {
        mutable.update { current ->
            if (current.alignments.none { it.index == index }) {
                current
            } else {
                current.copy(selected = if (index in current.selected) current.selected - index else current.selected + index)
            }
        }
    }

    fun setSource(source: CadSourceCrs) = mutable.update { it.copy(source = source) }

    /** El SC de la biblioteca elegido como origen: queda como origen, con su unidad propia como unidad del fichero (S057/RF-1, RF-3). */
    fun chooseLibrary(crs: LibraryCrs) {
        val library = LibrarySourceUi.of(crs, mutable.value.centre)
        unitTouched = true
        mutable.update { it.copy(library = library, source = library.source, unit = library.unit) }
    }

    /** El usuario confirma, o no, el aviso de la precisión del cambio de datum (S057/RF-9). */
    fun confirmLibrary(confirmed: Boolean) = mutable.update { it.copy(library = it.library?.confirm(confirmed)) }

    /** Tras descargar una rejilla se vuelve a mirar qué falta (S057/RF-10). */
    fun refreshLibrary() = mutable.update { it.copy(library = it.library?.refreshed(it.centre)) }

    fun setUnit(unit: LinearUnit) {
        unitTouched = true
        mutable.update { it.copy(unit = unit) }
    }

    private var importJob: Job? = null

    /**
     * Importa las alineaciones marcadas con las opciones de ahora, en segundo plano y con progreso. Si falla o se cancela no queda nada
     * guardado y se vuelve a la configuración; si acaba, queda el resumen (RF-13).
     */
    fun import() {
        val current = mutable.value
        if (!current.canImport) return
        val project = project ?: return
        val source = source ?: return
        mutable.update { it.copy(stage = AlignmentImportStage.IMPORTING, importBytes = 0, failure = null, cancelled = false) }
        importJob = viewModelScope.launch(io) {
            try {
                val result = importer.importLandXml(
                    projectId = project.id,
                    crs = project.crs,
                    fileName = source.name,
                    indices = current.selected,
                    source = current.source,
                    unit = current.unit,
                    openStream = { source.open() ?: throw IOException("No se puede abrir el fichero") },
                    onProgress = { read -> mutable.update { it.copy(importBytes = read) } },
                )
                when (result) {
                    is AlignmentImportResult.Done -> {
                        // S049/RF-16: el log lleva los recuentos, nunca nombres ni coordenadas.
                        log.info(
                            LogCategory.APP,
                            "Ejes de LandXML: ${result.summary.imported.size} importados, ${result.summary.ignored.size} ignorados",
                        )
                        mutable.update { it.copy(stage = AlignmentImportStage.RESULT, summary = result.summary) }
                    }

                    is AlignmentImportResult.Failed -> {
                        log.error(LogCategory.APP, "Importación de ejes fallida: ${result.reason.name.lowercase()}")
                        mutable.update { it.copy(stage = AlignmentImportStage.CONFIG, failure = result.reason) }
                    }
                }
            } catch (cancelled: CancellationException) {
                log.info(LogCategory.APP, "Importación de ejes cancelada: no se ha guardado nada")
                mutable.update { it.copy(stage = AlignmentImportStage.CONFIG, cancelled = true) }
                throw cancelled
            }
        }
    }

    /** Cancela la importación en curso. */
    fun cancel() {
        importJob?.cancel()
    }

    /** Más de [LandXmlAlignmentReader.MAX_BYTES] leídos de un fichero cuyo tamaño no se conocía de antemano (RF-3). */
    private class TooBigException : IOException()
}
