package es.topophone.app.ui.surface

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import es.topophone.app.di.IoDispatcher
import es.topophone.app.ui.points.ImportSource
import es.topophone.core.formats.landxml.LandXmlSurfaceInfo
import es.topophone.core.model.cad.CadSourceCrs
import es.topophone.core.model.log.AppLog
import es.topophone.core.model.log.LogCategory
import es.topophone.core.model.project.LinearUnit
import es.topophone.core.model.project.Project
import es.topophone.core.model.project.ProjectCrs
import es.topophone.core.model.surface.SurfaceExtent
import es.topophone.core.survey.CadPlacement
import es.topophone.core.survey.SurfaceProjector
import es.topophone.data.map.MapViewportRepository
import es.topophone.data.point.PointRepository
import es.topophone.data.project.ProjectRepository
import es.topophone.data.surface.SurfaceImportFailure
import es.topophone.data.surface.SurfaceImportResult
import es.topophone.data.surface.SurfaceImportSummary
import es.topophone.data.surface.SurfaceImporter
import es.topophone.data.surface.SurfaceRepository
import es.topophone.data.surface.SurfaceScanResult
import java.io.IOException
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

/** Por qué no se abre la pantalla de importación de la superficie (S042/RF-3, RF-4). */
enum class SurfaceFileProblem { EMPTY, TOO_BIG, NOT_XML, NOT_LANDXML, UNSUPPORTED_VERSION, NO_SURFACE, UNREADABLE }

/** En qué punto está la importación de un LandXML. */
enum class SurfaceImportStage { LOADING, PROBLEM, CONFIG, IMPORTING, RESULT }

/** Lo que dejó una importación: la superficie nueva y su resumen (S042/RF-13). */
data class SurfaceImportOutcome(val surfaceId: String, val summary: SurfaceImportSummary)

/** Lo que muestra la pantalla de importar un LandXML (S042). */
data class SurfaceImportUiState(
    val stage: SurfaceImportStage = SurfaceImportStage.LOADING,
    val fileName: String = "",
    val fileProblem: SurfaceFileProblem? = null,
    /** La versión del LandXML rechazado (`2.0`…), para decirla en el aviso (RF-2). */
    val version: String? = null,
    val projectName: String = "",
    val projectCrs: ProjectCrs = ProjectCrs.ETRS89_UTM30,
    val source: CadSourceCrs = CadSourceCrs.Project,
    val unit: LinearUnit = LinearUnit.METRE,
    /** Los sistemas UTM entre los que se puede elegir «otro sistema» (RF-6). */
    val availableSources: List<ProjectCrs> = ProjectCrs.entries.filter { it.isUtm },
    val declaredUnit: LinearUnit? = null,
    /** Las superficies del fichero que se pueden importar; si hay más de una se elige (RF-5). */
    val surfaces: List<LandXmlSurfaceInfo> = emptyList(),
    val selected: Int = 0,
    val scanning: Boolean = false,
    val bytesRead: Long = 0,
    val totalBytes: Long? = null,
    /** El recuadro de la superficie elegida en el sistema del proyecto (RF-8); null si no cabe en él. */
    val extent: SurfaceExtent? = null,
    val outsideArea: Boolean = false,
    val distanceKm: Double? = null,
    val farWarning: Boolean = false,
    val farConfirmed: Boolean = false,
    val duplicateName: Boolean = false,
    val duplicateConfirmed: Boolean = false,
    /** Los bytes leídos durante la importación, para el progreso (RF-10). */
    val importBytes: Long = 0,
    /** Por qué la última importación no guardó nada (RF-11); null si no ha fallado. */
    val failure: SurfaceImportFailure? = null,
    /** El usuario canceló la última importación: no se guardó nada (RF-11). */
    val cancelled: Boolean = false,
    val result: SurfaceImportOutcome? = null,
) {
    /** La superficie elegida del fichero. */
    val chosen: LandXmlSurfaceInfo? get() = surfaces.getOrNull(selected)

    /** Hay algo que importar y nada lo impide: se ha explorado, la superficie cabe en el sistema y los avisos están confirmados. */
    val canImport: Boolean
        get() = stage == SurfaceImportStage.CONFIG &&
            !scanning &&
            chosen != null &&
            !outsideArea &&
            (!farWarning || farConfirmed) &&
            (!duplicateName || duplicateConfirmed)
}

/**
 * La importación de un LandXML a un proyecto (S042): comprueba el fichero por su contenido y su tamaño, lo explora para dar sus
 * superficies y su unidad, deja elegir la superficie, el sistema y la unidad, y avisa de una superficie lejana o con un nombre de
 * fichero ya importado (RF-1 a RF-10, RF-15).
 */
@HiltViewModel
class SurfaceImportViewModel @Inject constructor(
    private val surfaces: SurfaceRepository,
    private val importer: SurfaceImporter,
    private val projects: ProjectRepository,
    private val points: PointRepository,
    private val viewports: MapViewportRepository,
    @param:IoDispatcher private val io: CoroutineDispatcher,
    private val log: AppLog = AppLog.NONE,
) : ViewModel() {
    private val mutable = MutableStateFlow(SurfaceImportUiState())
    val state: StateFlow<SurfaceImportUiState> = mutable.asStateFlow()

    private var started = false
    private var project: Project? = null
    private var source: ImportSource? = null
    private var projectCenter: Pair<Double, Double>? = null
    private var unitTouched = false

    /** El tamaño máximo que se lee de un fichero cuyo tamaño no se conocía de antemano (RF-4); los tests lo bajan. */
    internal var maxBytes: Long = MAX_BYTES

    /** Prepara la importación de [source] en el proyecto [projectId]; solo la primera vez (RF-1). */
    fun start(projectId: String, source: ImportSource) {
        if (started) return
        started = true
        this.source = source
        mutable.value = SurfaceImportUiState(fileName = source.name, totalBytes = source.sizeBytes)
        viewModelScope.launch(io) { load(projectId, source) }
    }

    private fun problem(problem: SurfaceFileProblem, version: String? = null) {
        mutable.update { it.copy(stage = SurfaceImportStage.PROBLEM, fileProblem = problem, version = version, scanning = false) }
    }

    private suspend fun load(projectId: String, source: ImportSource) {
        val project = projects.project(projectId) ?: return problem(SurfaceFileProblem.UNREADABLE)
        this.project = project
        val size = source.sizeBytes
        if (size != null && size > maxBytes) return problem(SurfaceFileProblem.TOO_BIG)
        if (size == 0L) return problem(SurfaceFileProblem.EMPTY)
        val taken = surfaces.surfaces(projectId).first().map { it.fileName }
        projectCenter = centerOf(project)
        mutable.value = SurfaceImportUiState(
            stage = SurfaceImportStage.CONFIG,
            fileName = source.name,
            projectName = project.name,
            projectCrs = project.crs,
            unit = project.linearUnit,
            scanning = true,
            totalBytes = size,
            duplicateName = source.name in taken,
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
            return problem(SurfaceFileProblem.TOO_BIG)
        } catch (_: IOException) {
            return problem(SurfaceFileProblem.UNREADABLE)
        } catch (_: SecurityException) {
            return problem(SurfaceFileProblem.UNREADABLE)
        }
        // El importador convierte cualquier fallo de lectura en un resultado: si se pasó del límite, ese fallo es este.
        if (exceeded) return problem(SurfaceFileProblem.TOO_BIG)
        val scan = when (result) {
            is SurfaceScanResult.Failed -> return problem(result.reason.toProblem(), result.version)
            is SurfaceScanResult.Found -> result.scan
        }
        val importable = scan.importable
        if (importable.isEmpty()) return problem(SurfaceFileProblem.NO_SURFACE)
        mutable.update { current ->
            val declared = scan.declaredUnit
            current.copy(
                surfaces = importable,
                selected = 0,
                declaredUnit = declared,
                unit = if (!unitTouched && declared != null) declared else current.unit,
            )
        }
        recompute()
        // La exploración acaba cuando ya están la extensión y los avisos, para que nadie vea una superficie a medias calcular.
        mutable.update { it.copy(scanning = false) }
    }

    private fun SurfaceImportFailure.toProblem() = when (this) {
        SurfaceImportFailure.NOT_LANDXML -> SurfaceFileProblem.NOT_LANDXML
        SurfaceImportFailure.UNSUPPORTED_VERSION -> SurfaceFileProblem.UNSUPPORTED_VERSION
        SurfaceImportFailure.MALFORMED -> SurfaceFileProblem.NOT_XML
        SurfaceImportFailure.NO_SURFACE -> SurfaceFileProblem.NO_SURFACE
        else -> SurfaceFileProblem.UNREADABLE
    }

    /** La posición del proyecto: el centro de sus puntos o el de su encuadre del mapa; sin ninguno no se conoce y no se avisa (RF-9). */
    private suspend fun centerOf(project: Project): Pair<Double, Double>? {
        val grounds = points.points(project.id).first().map { it.coordinates.ground }
        if (grounds.isNotEmpty()) return grounds.map { it.latitudeDeg }.average() to grounds.map { it.longitudeDeg }.average()
        viewports.viewport(project.id)?.let { return it.latitudeDeg to it.longitudeDeg }
        return null
    }

    /** Elige la superficie [index] de las del fichero (RF-5). */
    fun select(index: Int) {
        if (index !in mutable.value.surfaces.indices) return
        mutable.update { it.copy(selected = index, farConfirmed = false) }
        recompute()
    }

    fun setSource(source: CadSourceCrs) {
        mutable.update { it.copy(source = source, farConfirmed = false) }
        recompute()
    }

    fun setUnit(unit: LinearUnit) {
        unitTouched = true
        mutable.update { it.copy(unit = unit, farConfirmed = false) }
        recompute()
    }

    fun confirmFar() = mutable.update { it.copy(farConfirmed = true) }

    fun confirmDuplicate() = mutable.update { it.copy(duplicateConfirmed = true) }

    /** El recuadro de la superficie en el sistema del proyecto y su distancia al proyecto, con las opciones de ahora (RF-8, RF-9). */
    private fun recompute() {
        val project = project ?: return
        val current = mutable.value
        val raw = current.chosen ?: return
        val box = SurfaceProjector(project.crs, current.source, current.unit)
            .cornersExtent(raw.minEasting, raw.maxEasting, raw.minNorthing, raw.maxNorthing)
        if (box == null) {
            mutable.update { it.copy(extent = null, outsideArea = true, distanceKm = null, farWarning = false) }
            return
        }
        val centre = CadPlacement.geographic(project.crs, box.centerEasting, box.centerNorthing)
        val km = centre?.let { c -> projectCenter?.let { p -> CadPlacement.distanceKm(p.first, p.second, c.first, c.second) } }
        mutable.update { it.copy(extent = box, outsideArea = false, distanceKm = km, farWarning = CadPlacement.isFar(km)) }
    }

    private var importJob: Job? = null

    /**
     * Importa la superficie elegida con las opciones de ahora, en segundo plano y con progreso (RF-10). Si falla o se cancela no
     * queda nada guardado (RF-11) y se vuelve a la configuración; si acaba, queda el resumen (RF-13).
     */
    fun import() {
        val current = mutable.value
        if (!current.canImport) return
        val project = project ?: return
        val source = source ?: return
        val chosen = current.chosen ?: return
        mutable.update { it.copy(stage = SurfaceImportStage.IMPORTING, importBytes = 0, failure = null, cancelled = false) }
        importJob = viewModelScope.launch(io) {
            try {
                val result = importer.import(
                    projectId = project.id,
                    crs = project.crs,
                    fileName = source.name,
                    surfaceIndex = chosen.index,
                    source = current.source,
                    unit = current.unit,
                    openStream = { source.open() ?: throw IOException("No se puede abrir el fichero") },
                    onProgress = { read -> mutable.update { it.copy(importBytes = read) } },
                )
                when (result) {
                    is SurfaceImportResult.Imported -> {
                        log.info(LogCategory.APP, importedMessage(result.summary))
                        mutable.update {
                            it.copy(stage = SurfaceImportStage.RESULT, result = SurfaceImportOutcome(result.surfaceId, result.summary))
                        }
                    }

                    is SurfaceImportResult.Failed -> {
                        log.error(LogCategory.APP, "Importación LandXML fallida: ${result.reason.name.lowercase()}")
                        mutable.update { it.copy(stage = SurfaceImportStage.CONFIG, failure = result.reason) }
                    }
                }
            } catch (cancelled: CancellationException) {
                log.info(LogCategory.APP, "Importación LandXML cancelada: no se ha guardado nada")
                mutable.update { it.copy(stage = SurfaceImportStage.CONFIG, cancelled = true) }
                throw cancelled
            }
        }
    }

    /** Cancela la importación en curso (RF-10). */
    fun cancel() {
        importJob?.cancel()
    }

    /** El log no lleva nombres ni coordenadas, solo los recuentos. */
    private fun importedMessage(summary: SurfaceImportSummary) =
        "Superficie LandXML importada: ${summary.summary.vertexCount} puntos, ${summary.summary.triangleCount} triángulos, " +
            "${summary.summary.holeCount} huecos" + if (summary.activated) " (activa)" else ""

    /** Más de [MAX_BYTES] leídos de un fichero cuyo tamaño no se conocía de antemano (RF-4). */
    private class TooBigException : IOException()

    companion object {
        /** RF-4: el límite de tamaño de un LandXML, como el de un DXF. */
        const val MAX_BYTES = 100L * 1024 * 1024
    }
}
