package es.topophone.app.ui.cad

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import es.topophone.app.di.IoDispatcher
import es.topophone.app.ui.points.ImportSource
import es.topophone.core.formats.dxf.DxfFileKind
import es.topophone.core.formats.dxf.DxfProbe
import es.topophone.core.model.cad.CadEntity
import es.topophone.core.model.cad.CadEntityKind
import es.topophone.core.model.cad.CadExtent
import es.topophone.core.model.cad.CadImportOptions
import es.topophone.core.model.cad.CadImportSummary
import es.topophone.core.model.cad.CadSourceCrs
import es.topophone.core.model.log.AppLog
import es.topophone.core.model.log.LogCategory
import es.topophone.core.model.project.LinearUnit
import es.topophone.core.model.project.Project
import es.topophone.core.model.project.ProjectCrs
import es.topophone.core.survey.CadPlacement
import es.topophone.core.survey.CadProjection
import es.topophone.core.survey.CadProjector
import es.topophone.data.cad.CadDrawingImporter
import es.topophone.data.cad.CadImportFailure
import es.topophone.data.cad.CadImportResult
import es.topophone.data.cad.CadRepository
import es.topophone.data.map.MapViewportRepository
import es.topophone.data.point.PointRepository
import es.topophone.data.project.ProjectRepository
import java.io.IOException
import java.io.OutputStream
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Por qué no se abre la pantalla de importación del dibujo (S040/RF-2 a RF-5, RF-13). */
enum class CadFileProblem { EMPTY, NOT_DXF, DWG, BINARY_DXF, TOO_BIG, UNREADABLE, NO_ELEMENTS }

/** En qué punto está la importación de un DXF. */
enum class CadImportStage { LOADING, PROBLEM, CONFIG, IMPORTING, RESULT }

/** Lo que dejó una importación: el dibujo nuevo y su resumen (S040/RF-18). */
data class CadImportOutcome(val drawingId: String, val summary: CadImportSummary)

/** Lo que muestra la pantalla de importar un DXF (S040). */
data class CadImportUiState(
    val stage: CadImportStage = CadImportStage.LOADING,
    val fileName: String = "",
    val fileProblem: CadFileProblem? = null,
    /** La versión del DWG rechazado (`AC1032`…), para decirla en el aviso (RF-3). */
    val dwgVersion: String? = null,
    val projectName: String = "",
    val projectCrs: ProjectCrs = ProjectCrs.ETRS89_UTM30,
    val options: CadImportOptions = CadImportOptions(),
    /** Los sistemas UTM entre los que se puede elegir «otro sistema» (RF-6). */
    val availableSources: List<ProjectCrs> = ProjectCrs.entries.filter { it.isUtm },
    val declaredUnit: LinearUnit? = null,
    val scanning: Boolean = false,
    val bytesRead: Long = 0,
    val totalBytes: Long? = null,
    val elementCount: Int = 0,
    /** El fichero acabó bien; si no, se avisa de que el dibujo está incompleto (RF-13). */
    val scanComplete: Boolean = true,
    /** El recuadro del dibujo en el sistema del proyecto (RF-9); null si no cabe en él. */
    val extent: CadExtent? = null,
    val outsideArea: Boolean = false,
    val distanceKm: Double? = null,
    val farWarning: Boolean = false,
    val farConfirmed: Boolean = false,
    val duplicateName: Boolean = false,
    val duplicateConfirmed: Boolean = false,
    /** Los bytes leídos durante la importación, para el progreso (RF-11). */
    val importBytes: Long = 0,
    /** Por qué la última importación no guardó nada (RF-12); null si no ha fallado. */
    val failure: CadImportFailure? = null,
    /** El usuario canceló la última importación: no se guardó nada (RF-12). */
    val cancelled: Boolean = false,
    val result: CadImportOutcome? = null,
) {
    /** Hay algo que importar y nada lo impide: se ha explorado, el dibujo cabe en el sistema y los avisos están confirmados. */
    val canImport: Boolean
        get() = stage == CadImportStage.CONFIG &&
            !scanning &&
            elementCount > 0 &&
            !outsideArea &&
            (!farWarning || farConfirmed) &&
            (!duplicateName || duplicateConfirmed)
}

/**
 * La importación de un DXF a un proyecto (S040): reconoce el fichero por su contenido, lo explora para dar su extensión, deja
 * elegir el sistema, la unidad y las cotas, y avisa de un dibujo lejano o con un nombre ya importado (RF-1 a RF-10, RF-20).
 */
@HiltViewModel
class CadImportViewModel @Inject constructor(
    private val cad: CadRepository,
    private val importer: CadDrawingImporter,
    private val projects: ProjectRepository,
    private val points: PointRepository,
    private val viewports: MapViewportRepository,
    @param:IoDispatcher private val io: CoroutineDispatcher,
    private val log: AppLog = AppLog.NONE,
) : ViewModel() {
    private val mutable = MutableStateFlow(CadImportUiState())
    val state: StateFlow<CadImportUiState> = mutable.asStateFlow()

    private var started = false
    private var project: Project? = null
    private var source: ImportSource? = null
    private var rawExtent: CadExtent? = null
    private var projectCenter: Pair<Double, Double>? = null

    /** El tamaño máximo que se lee de un fichero cuyo tamaño no se conocía de antemano (RF-5); los tests lo bajan. */
    internal var maxBytes: Long = DxfFileKind.MAX_BYTES
    private var unitTouched = false

    /** Prepara la importación de [source] en el proyecto [projectId]; solo la primera vez (RF-1). */
    fun start(projectId: String, source: ImportSource) {
        if (started) return
        started = true
        this.source = source
        mutable.value = CadImportUiState(fileName = source.name, totalBytes = source.sizeBytes)
        viewModelScope.launch(io) { load(projectId, source) }
    }

    private fun problem(problem: CadFileProblem, dwgVersion: String? = null) {
        mutable.update { it.copy(stage = CadImportStage.PROBLEM, fileProblem = problem, dwgVersion = dwgVersion, scanning = false) }
    }

    private suspend fun load(projectId: String, source: ImportSource) {
        val project = projects.project(projectId) ?: return problem(CadFileProblem.UNREADABLE)
        this.project = project
        val head = try {
            source.open()?.use { DxfFileKind.readHead(it) }
        } catch (_: IOException) {
            null
        } catch (_: SecurityException) {
            null
        } ?: return problem(CadFileProblem.UNREADABLE)
        when (val probe = DxfFileKind.probe(head, source.sizeBytes ?: head.size.toLong())) {
            DxfProbe.Dxf -> Unit
            DxfProbe.Empty -> return problem(CadFileProblem.EMPTY)
            DxfProbe.NotDxf -> return problem(CadFileProblem.NOT_DXF)
            DxfProbe.BinaryDxf -> return problem(CadFileProblem.BINARY_DXF)
            DxfProbe.TooLarge -> return problem(CadFileProblem.TOO_BIG)
            is DxfProbe.Dwg -> return problem(CadFileProblem.DWG, probe.version)
        }
        val taken = cad.drawings(projectId).first().map { it.drawing.fileName }
        projectCenter = centerOf(project)
        mutable.value = CadImportUiState(
            stage = CadImportStage.CONFIG,
            fileName = source.name,
            projectName = project.name,
            projectCrs = project.crs,
            options = CadImportOptions(unit = project.linearUnit),
            scanning = true,
            totalBytes = source.sizeBytes,
            duplicateName = source.name in taken,
        )
        scan(source)
    }

    private suspend fun scan(source: ImportSource) {
        val scan = try {
            importer.scan(
                openStream = { source.open() ?: throw IOException("No se puede abrir el fichero") },
                unit = mutable.value.options.unit,
                onProgress = { read ->
                    if (read > maxBytes) throw TooBigException()
                    mutable.update { it.copy(bytesRead = read) }
                },
            )
        } catch (_: TooBigException) {
            return problem(CadFileProblem.TOO_BIG)
        } catch (_: IOException) {
            return problem(CadFileProblem.UNREADABLE)
        } catch (_: SecurityException) {
            return problem(CadFileProblem.UNREADABLE)
        }
        if (scan.elements == 0) return problem(CadFileProblem.NO_ELEMENTS)
        rawExtent = scan.extent
        mutable.update { current ->
            val declared = scan.declaredUnit
            val unit = if (!unitTouched && declared != null) declared else current.options.unit
            current.copy(
                elementCount = scan.elements,
                scanComplete = scan.complete,
                declaredUnit = scan.declaredUnit,
                options = current.options.copy(unit = unit),
            )
        }
        recompute()
        // La exploración acaba cuando ya están la extensión y los avisos, para que nadie vea un dibujo a medias calcular.
        mutable.update { it.copy(scanning = false) }
    }

    /** La posición del proyecto: el centro de sus puntos o el de su encuadre del mapa; sin ninguno no se conoce y no se avisa (RF-10). */
    private suspend fun centerOf(project: Project): Pair<Double, Double>? {
        val grounds = points.points(project.id).first().map { it.coordinates.ground }
        if (grounds.isNotEmpty()) return grounds.map { it.latitudeDeg }.average() to grounds.map { it.longitudeDeg }.average()
        viewports.viewport(project.id)?.let { return it.latitudeDeg to it.longitudeDeg }
        return null
    }

    fun setSource(source: CadSourceCrs) = change { it.copy(source = source) }

    fun setUnit(unit: LinearUnit) {
        unitTouched = true
        change { it.copy(unit = unit) }
    }

    fun setUseElevations(use: Boolean) = change { it.copy(useElevations = use) }

    fun confirmFar() = mutable.update { it.copy(farConfirmed = true) }

    fun confirmDuplicate() = mutable.update { it.copy(duplicateConfirmed = true) }

    private fun change(transform: (CadImportOptions) -> CadImportOptions) {
        mutable.update { it.copy(options = transform(it.options), farConfirmed = false) }
        recompute()
    }

    /** El recuadro del dibujo en el sistema del proyecto y su distancia al proyecto, con las opciones de ahora (RF-9, RF-10). */
    private fun recompute() {
        val project = project ?: return
        val raw = rawExtent ?: return
        val options = mutable.value.options
        val corners = CadEntity(
            CadEntityKind.POLYLINE,
            doubleArrayOf(raw.minEasting, raw.maxEasting, raw.maxEasting, raw.minEasting),
            doubleArrayOf(raw.minNorthing, raw.minNorthing, raw.maxNorthing, raw.maxNorthing),
            null,
            null,
        )
        when (val result = CadProjector(project.crs, options).convert(corners)) {
            is CadProjection.Rejected -> mutable.update {
                it.copy(extent = null, outsideArea = true, distanceKm = null, farWarning = false)
            }
            is CadProjection.Converted -> {
                val box = result.entity.bounds
                val centre = CadPlacement.geographic(
                    project.crs,
                    (box.minEasting + box.maxEasting) / 2,
                    (box.minNorthing + box.maxNorthing) / 2,
                )
                val km = centre?.let { c -> projectCenter?.let { p -> CadPlacement.distanceKm(p.first, p.second, c.first, c.second) } }
                mutable.update { it.copy(extent = box, outsideArea = false, distanceKm = km, farWarning = CadPlacement.isFar(km)) }
            }
        }
    }

    private var importJob: Job? = null

    /**
     * Importa el dibujo con las opciones de ahora, en segundo plano y con progreso (RF-11). Si falla o se cancela no queda nada
     * guardado (RF-12) y se vuelve a la configuración; si acaba, queda el resumen (RF-18).
     */
    fun import() {
        val current = mutable.value
        if (!current.canImport) return
        val project = project ?: return
        val source = source ?: return
        mutable.update { it.copy(stage = CadImportStage.IMPORTING, importBytes = 0, failure = null, cancelled = false) }
        importJob = viewModelScope.launch(io) {
            try {
                val result = importer.import(
                    projectId = project.id,
                    crs = project.crs,
                    fileName = source.name,
                    options = current.options,
                    openStream = { source.open() ?: throw IOException("No se puede abrir el fichero") },
                    onProgress = { read -> mutable.update { it.copy(importBytes = read) } },
                )
                when (result) {
                    is CadImportResult.Imported -> {
                        log.info(LogCategory.APP, importedMessage(result.summary))
                        mutable.update {
                            it.copy(stage = CadImportStage.RESULT, result = CadImportOutcome(result.drawingId, result.summary))
                        }
                    }
                    is CadImportResult.Failed -> {
                        log.error(LogCategory.APP, "Importación DXF fallida: ${result.reason.name.lowercase()}")
                        mutable.update { it.copy(stage = CadImportStage.CONFIG, failure = result.reason) }
                    }
                }
            } catch (cancelled: CancellationException) {
                log.info(LogCategory.APP, "Importación DXF cancelada: no se ha guardado nada")
                mutable.update { it.copy(stage = CadImportStage.CONFIG, cancelled = true) }
                throw cancelled
            }
        }
    }

    /** Cancela la importación en curso (RF-11). */
    fun cancel() {
        importJob?.cancel()
    }

    /** El log no lleva nombres de elementos ni coordenadas, solo los recuentos (RF-32). */
    private fun importedMessage(summary: CadImportSummary) =
        "Dibujo DXF importado: ${summary.importedTotal} elementos, ${summary.ignoredTotal} ignorados, ${summary.layers} capas" +
            if (summary.incomplete) " (incompleto)" else ""

    /** Escribe el resumen de la importación como texto (RF-18); false si no se pudo escribir. */
    suspend fun writeSummary(out: OutputStream, labels: CadSummaryLabels): Boolean = withContext(io) {
        val summary = mutable.value.result?.summary ?: return@withContext false
        try {
            out.bufferedWriter(Charsets.UTF_8).use { it.write(CadSummaryText.build(summary, labels)) }
            true
        } catch (_: IOException) {
            false
        }
    }

    /** Más de [DxfFileKind.MAX_BYTES] leídos de un fichero cuyo tamaño no se conocía de antemano (RF-5). */
    private class TooBigException : IOException()
}
