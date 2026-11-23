package es.topophone.app.ui.raster

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import es.topophone.app.di.IoDispatcher
import es.topophone.app.ui.crs.LibrarySourceUi
import es.topophone.app.ui.points.ImportSource
import es.topophone.app.ui.projects.ProjectCenterProvider
import es.topophone.core.formats.raster.RasterCrsDeclaration
import es.topophone.core.formats.raster.RasterCrsResolver
import es.topophone.core.formats.raster.RasterHeader
import es.topophone.core.formats.raster.RasterLimits
import es.topophone.core.formats.raster.RasterReadStats
import es.topophone.core.geodesy.GeoPoint
import es.topophone.core.geodesy.GeoidModels
import es.topophone.core.model.cad.CadSourceCrs
import es.topophone.core.model.log.AppLog
import es.topophone.core.model.log.LogCategory
import es.topophone.core.model.project.CorrectionsDatum
import es.topophone.core.model.project.CrsArea
import es.topophone.core.model.project.HeightSystem
import es.topophone.core.model.project.LibraryCrs
import es.topophone.core.model.project.LinearUnit
import es.topophone.core.model.project.Project
import es.topophone.core.model.project.ProjectCrs
import es.topophone.core.model.project.SpainCrs
import es.topophone.core.model.surface.SurfaceExtent
import es.topophone.core.survey.CadPlacement
import es.topophone.core.survey.HeightConversion
import es.topophone.core.survey.ProjectPositionCalculator
import es.topophone.core.survey.RasterFootprint
import es.topophone.core.survey.SourceHeights
import es.topophone.core.survey.TerrainView
import es.topophone.core.survey.Undulation
import es.topophone.data.project.ProjectRepository
import es.topophone.data.surface.RasterImportFailure
import es.topophone.data.surface.RasterImportOptions
import es.topophone.data.surface.RasterImportResult
import es.topophone.data.surface.RasterImportSummary
import es.topophone.data.surface.RasterImporter
import es.topophone.data.surface.RasterMeasureResult
import es.topophone.data.surface.RasterScanResult
import es.topophone.data.surface.RasterStageResult
import es.topophone.data.surface.StagedRaster
import es.topophone.data.surface.SurfaceRepository
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

/** En qué punto está la importación de un ráster (S045/RF-1 a RF-16). */
enum class RasterImportStage { LOADING, PROBLEM, CONFIG, IMPORTING, RESULT }

/** Lo que dejó una importación: el ráster nuevo y su resumen (S045/RF-16). */
data class RasterImportOutcome(val surfaceId: String, val summary: RasterImportSummary)

/** Lo que muestra la pantalla de importar un ráster de cotas (S045). */
data class RasterImportUiState(
    val stage: RasterImportStage = RasterImportStage.LOADING,
    val fileName: String = "",
    /** Por qué no se abre la pantalla de importación (RF-3 a RF-5); null si se abre. */
    val problem: RasterImportFailure? = null,
    val projectName: String = "",
    val projectCrs: ProjectCrs = ProjectCrs.ETRS89_UTM30,
    val projectHeightSystem: HeightSystem = HeightSystem.ORTHOMETRIC,
    /** El datum de las correcciones del proyecto y su centro, para construir el SC de origen de la biblioteca (S057/RF-2, RF-8). */
    val corrections: CorrectionsDatum = CorrectionsDatum.ETRS89,
    val centre: GeoPoint? = null,
    /** El SC de la biblioteca de origen, declarado por el fichero o elegido, con su cambio de datum y su confirmación (S057/RF-1, RF-4, RF-8 a RF-10). */
    val library: LibrarySourceUi? = null,
    /** Si las cotas del fichero se pueden pasar al sistema de alturas del proyecto (S057/RF-19, RF-20). */
    val heightConversion: HeightConversion = HeightConversion.Same,
    /** La cabecera del fichero (RF-6 a RF-9, RF-11). */
    val header: RasterHeader? = null,
    /** Lo que dice el fichero de su sistema (RF-8). */
    val declaration: RasterCrsDeclaration = RasterCrsDeclaration.Undeclared,
    val source: CadSourceCrs = CadSourceCrs.Project,
    /** Los sistemas entre los que se puede elegir (RF-8). */
    val availableSources: List<ProjectCrs> = ProjectCrs.entries,
    val unit: LinearUnit = LinearUnit.METRE,
    val declaredUnit: LinearUnit? = null,
    val heightSystem: HeightSystem = HeightSystem.ORTHOMETRIC,
    /** Se está copiando y midiendo el fichero (RF-11). */
    val scanning: Boolean = true,
    val bytesCopied: Long = 0,
    val totalBytes: Long? = null,
    val measuredRows: Long = 0,
    /** Las celdas con y sin dato y las cotas del fichero, en su unidad y su sistema de alturas (RF-11). */
    val stats: RasterReadStats? = null,
    /** La diferencia entre el sistema de alturas del proyecto y el del ráster en el centro del ráster; null si no hace falta o no hay geoide. */
    val geoidShiftM: Double? = null,
    val extent: SurfaceExtent? = null,
    val outsideArea: Boolean = false,
    val outsideReason: RasterImportFailure? = null,
    val distanceKm: Double? = null,
    val farWarning: Boolean = false,
    val farConfirmed: Boolean = false,
    val duplicateName: Boolean = false,
    val duplicateConfirmed: Boolean = false,
    /** Las filas leídas durante la importación, para el progreso (RF-13). */
    val importRows: Long = 0,
    val importTotalRows: Long = 0,
    /** Por qué la última importación no guardó nada (RF-13); null si no ha fallado. */
    val failure: RasterImportFailure? = null,
    /** El usuario canceló la última importación: no se guardó nada (RF-13). */
    val cancelled: Boolean = false,
    val result: RasterImportOutcome? = null,
) {
    /** El porcentaje de celdas sin dato (RF-11). */
    val noDataPercent: Double?
        get() = stats?.let { 100.0 * it.noDataCells / (it.dataCells + it.noDataCells).coerceAtLeast(1) }

    /** Las cotas mínima y máxima en metros y en el sistema de alturas del proyecto (RF-11); null sin geoide si hace falta. */
    val minElevationM: Double? get() = stats?.let { converted(it.minValue) }
    val maxElevationM: Double? get() = stats?.let { converted(it.maxValue) }

    private fun converted(value: Double): Double? {
        val metres = value * unit.metres
        if (heightSystem == projectHeightSystem) return metres
        val n = geoidShiftM ?: return null
        return if (heightSystem == HeightSystem.ELLIPSOIDAL) metres - n else metres + n
    }

    /** Hay algo que importar y nada lo impide: está medido, cabe en el sistema y los avisos están confirmados (RF-12, RF-15). */
    val canImport: Boolean
        get() = stage == RasterImportStage.CONFIG &&
            !scanning &&
            header != null &&
            !outsideArea &&
            (!farWarning || farConfirmed) &&
            (!duplicateName || duplicateConfirmed) &&
            (source !is CadSourceCrs.Library || library?.ready == true)
}

/**
 * La importación de un ráster de cotas a un proyecto (S045): copia el fichero elegido, lo explora por su contenido, lo mide, deja elegir
 * el sistema, la unidad y el sistema de alturas, y avisa de un ráster lejano o con un nombre de fichero ya importado (RF-1 a RF-16).
 */
@HiltViewModel
class RasterImportViewModel @Inject constructor(
    private val surfaces: SurfaceRepository,
    private val importer: RasterImporter,
    private val projects: ProjectRepository,
    private val centers: ProjectCenterProvider,
    @param:IoDispatcher private val io: CoroutineDispatcher,
    private val log: AppLog = AppLog.NONE,
) : ViewModel() {
    private val mutable = MutableStateFlow(RasterImportUiState())
    val state: StateFlow<RasterImportUiState> = mutable.asStateFlow()

    private var started = false
    private var project: Project? = null
    private var source: ImportSource? = null
    private var staged: StagedRaster? = null
    private var projectCenter: Pair<Double, Double>? = null
    private var unitTouched = false

    /** Prepara la importación de [source] en el proyecto [projectId]; solo la primera vez (RF-1). */
    fun start(projectId: String, source: ImportSource) {
        if (started) return
        started = true
        this.source = source
        mutable.value = RasterImportUiState(fileName = source.name, totalBytes = source.sizeBytes)
        viewModelScope.launch(io) { load(projectId, source) }
    }

    private fun problem(reason: RasterImportFailure) {
        mutable.update { it.copy(stage = RasterImportStage.PROBLEM, problem = reason, scanning = false) }
    }

    private suspend fun load(projectId: String, source: ImportSource) {
        val project = projects.project(projectId) ?: return problem(RasterImportFailure.READ_ERROR)
        this.project = project
        val size = source.sizeBytes
        if (size != null && size > RasterLimits.MAX_FILE_BYTES) return problem(RasterImportFailure.TOO_LARGE)
        if (size == 0L) return problem(RasterImportFailure.EMPTY)
        val taken = surfaces.surfaces(projectId).first().map { it.fileName }
        projectCenter = centers.center(project)?.let { it.latitudeDeg to it.longitudeDeg } // S006/RF-37: un único cálculo
        mutable.value = RasterImportUiState(
            stage = RasterImportStage.CONFIG,
            fileName = source.name,
            projectName = project.name,
            projectCrs = project.crs,
            projectHeightSystem = project.heightSystem,
            corrections = project.correctionsDatum,
            centre = projectCenter?.let { GeoPoint(it.first, it.second) },
            heightSystem = project.heightSystem,
            totalBytes = size,
            duplicateName = source.name in taken,
        )

        val copy = importer.stage(
            openStream = { source.open() ?: throw IOException("No se puede abrir el fichero") },
            onProgress = { bytes -> mutable.update { it.copy(bytesCopied = bytes) } },
        )
        val file = when (copy) {
            is RasterStageResult.Failed -> return problem(copy.reason)
            is RasterStageResult.Staged -> copy.staged
        }
        staged = file
        val header = when (val scan = importer.scan(file)) {
            is RasterScanResult.Failed -> return problem(scan.reason)
            is RasterScanResult.Found -> scan.header
        }
        val declaration = RasterCrsResolver.resolve(header.epsg)
        val library = (declaration as? RasterCrsDeclaration.Library)?.let { libraryOf(it.epsg, project) }
        mutable.update {
            it.copy(
                header = header,
                declaration = declaration,
                library = library,
                source = library?.source ?: defaultSource(declaration, project.crs),
                unit = header.verticalUnit ?: LinearUnit.METRE,
                declaredUnit = header.verticalUnit,
                totalBytes = size ?: it.bytesCopied,
            )
        }
        recompute()
        measure(file)
    }

    /**
     * El SC de la biblioteca que declara el fichero (S057/RF-4), construido con las correcciones y el centro del proyecto; `null` si es el del
     * proyecto (no hay que reproyectar, RF-12) o si no se puede calcular.
     */
    private fun libraryOf(epsg: Int, project: Project): LibrarySourceUi? =
        LibrarySourceUi.declared(epsg, project.crs, project.correctionsDatum, mutable.value.centre)

    /** El sistema que se propone: el que declara el fichero si es uno de la app (el del proyecto si coincide), y si no el del proyecto (RF-8). */
    private fun defaultSource(declaration: RasterCrsDeclaration, crs: ProjectCrs): CadSourceCrs = when (declaration) {
        is RasterCrsDeclaration.Supported -> if (declaration.epsg == crs.epsg) CadSourceCrs.Project else declaration.source
        else -> CadSourceCrs.Project
    }

    private suspend fun measure(file: StagedRaster) {
        when (
            val measured = importer.measure(file) { rows, _ ->
                mutable.update { it.copy(measuredRows = rows) }
            }
        ) {
            is RasterMeasureResult.Failed -> problem(measured.reason)
            is RasterMeasureResult.Measured -> {
                if (measured.stats.dataCells == 0L) return problem(RasterImportFailure.NO_DATA_CELLS)
                mutable.update { it.copy(stats = measured.stats) }
                recompute()
                // La medición acaba cuando ya están la extensión y los avisos, para que nadie vea un ráster a medias calcular.
                mutable.update { it.copy(scanning = false) }
            }
        }
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

    /** El SC de la biblioteca elegido como origen. La unidad de la malla es la del SC; la unidad que se elige aquí es la de las cotas (S057/RF-1, RF-15). */
    fun chooseLibrary(crs: LibraryCrs) {
        val library = LibrarySourceUi.of(crs, mutable.value.centre)
        mutable.update { it.copy(library = library, source = library.source, farConfirmed = false) }
        recompute()
    }

    /** El usuario confirma, o no, el aviso de la precisión del cambio de datum (S057/RF-9). */
    fun confirmLibrary(confirmed: Boolean) = mutable.update { it.copy(library = it.library?.confirm(confirmed)) }

    /** Tras descargar una rejilla o un geoide se vuelve a mirar qué falta y se recalcula (S057/RF-10, RF-20). */
    fun refreshLibrary() {
        mutable.update { it.copy(library = it.library?.refreshed(it.centre)) }
        recompute()
    }

    fun setHeightSystem(heightSystem: HeightSystem) {
        mutable.update { it.copy(heightSystem = heightSystem) }
        recompute()
    }

    fun confirmFar() = mutable.update { it.copy(farConfirmed = true) }

    fun confirmDuplicate() = mutable.update { it.copy(duplicateConfirmed = true) }

    /** La extensión del ráster en el sistema del proyecto, su distancia al proyecto y el cambio de altura, con las opciones de ahora (RF-11, RF-12). */
    private fun recompute() {
        val project = project ?: return
        val current = mutable.value
        val header = current.header ?: return
        when (
            val footprint = RasterFootprint.of(
                project.crs,
                current.source,
                header.originX,
                header.originY,
                header.stepX,
                header.stepY,
                header.columns,
                header.rows,
                TerrainView.of(project.terrain, project.crs) ?: TerrainView.GRID, // S058/RF-17
            )
        ) {
            is RasterFootprint.Outside -> mutable.update {
                it.copy(
                    extent = null,
                    outsideArea = true,
                    outsideReason = if (footprint.reason.name ==
                        "OUTSIDE_ED50_GRID"
                    ) {
                        RasterImportFailure.OUTSIDE_ED50_GRID
                    } else {
                        RasterImportFailure.OUTSIDE_CRS_AREA
                    },
                    distanceKm = null,
                    farWarning = false,
                    geoidShiftM = null,
                    heightConversion = SourceHeights.conversion(project.crs, project.heightSystem, it.heightSystem),
                )
            }
            is RasterFootprint.Inside -> {
                val box = footprint.extent
                val centre = CadPlacement.geographic(project.crs, box.centerEasting, box.centerNorthing)
                val km = centre?.let { c -> projectCenter?.let { p -> CadPlacement.distanceKm(p.first, p.second, c.first, c.second) } }
                mutable.update {
                    it.copy(
                        extent = box,
                        outsideArea = false,
                        outsideReason = null,
                        distanceKm = km,
                        farWarning = CadPlacement.isFar(km),
                        geoidShiftM = if (it.heightSystem ==
                            project.heightSystem
                        ) {
                            null
                        } else {
                            centre?.let { c -> undulation(project.crs, c) }
                        },
                        heightConversion = SourceHeights.conversion(project.crs, project.heightSystem, it.heightSystem),
                    )
                }
            }
        }
    }

    /** La ondulación del geoide del ámbito del proyecto en la latitud y la longitud dadas (RF-10). */
    private fun undulation(crs: ProjectCrs, latLon: Pair<Double, Double>): Double? {
        if (crs is LibraryCrs) {
            // Un proyecto de la biblioteca usa el geoide que eligió (S057/RF-19); sin él, o sin instalar, no hay cambio de altura.
            return (ProjectPositionCalculator().engineFor(crs).undulation(latLon.first, latLon.second) as? Undulation.Value)?.metres
        }
        val geoid = if ((crs as? SpainCrs)?.area == CrsArea.CANARIAS) GeoidModels.egm08RednapCanarias else GeoidModels.egm08RednapPeninsula
        return geoid.undulationM(latLon.first, latLon.second)
    }

    private var importJob: Job? = null

    /**
     * Importa el ráster con las opciones de ahora, en segundo plano y con progreso (RF-13). Si falla o se cancela no queda nada
     * guardado y se vuelve a la configuración; si acaba, queda el resumen (RF-16).
     */
    fun import() {
        val current = mutable.value
        if (!current.canImport) return
        val project = project ?: return
        val source = source ?: return
        val file = staged ?: return
        mutable.update { it.copy(stage = RasterImportStage.IMPORTING, importRows = 0, failure = null, cancelled = false) }
        importJob = viewModelScope.launch(io) {
            try {
                val result = importer.import(
                    projectId = project.id,
                    crs = project.crs,
                    staged = file,
                    fileName = source.name,
                    options = RasterImportOptions(current.source, current.unit, current.heightSystem),
                    onProgress = { rows, total -> mutable.update { it.copy(importRows = rows, importTotalRows = total) } },
                    terrain = project.terrain, // S058/RF-17
                )
                when (result) {
                    is RasterImportResult.Imported -> {
                        log.info(LogCategory.APP, importedMessage(result.summary))
                        mutable.update {
                            it.copy(stage = RasterImportStage.RESULT, result = RasterImportOutcome(result.surfaceId, result.summary))
                        }
                    }

                    is RasterImportResult.Failed -> {
                        log.error(LogCategory.APP, "Importación de ráster fallida: ${result.reason.name.lowercase()}")
                        mutable.update { it.copy(stage = RasterImportStage.CONFIG, failure = result.reason) }
                    }
                }
            } catch (cancelled: CancellationException) {
                log.info(LogCategory.APP, "Importación de ráster cancelada: no se ha guardado nada")
                mutable.update { it.copy(stage = RasterImportStage.CONFIG, cancelled = true) }
                throw cancelled
            }
        }
    }

    /** Cancela la importación en curso (RF-13). */
    fun cancel() {
        importJob?.cancel()
    }

    /** El log no lleva nombres ni coordenadas, solo los recuentos. */
    private fun importedMessage(summary: RasterImportSummary) =
        "Ráster importado: ${summary.columns} × ${summary.rows} celdas, ${summary.summary.dataCells} con dato y " +
            "${summary.summary.noDataCells} sin dato" + if (summary.activated) " (activo)" else ""

    override fun onCleared() {
        staged?.close()
        staged = null
        super.onCleared()
    }
}
