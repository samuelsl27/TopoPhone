package es.topophone.app.ui.gpkg

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import es.topophone.app.di.IoDispatcher
import es.topophone.app.ui.points.ImportSource
import es.topophone.app.ui.projects.ProjectCenterProvider
import es.topophone.core.formats.gpkg.GpkgCrs
import es.topophone.core.formats.gpkg.GpkgCrsCatalog
import es.topophone.core.formats.gpkg.GpkgFileKind
import es.topophone.core.model.cad.CadExtent
import es.topophone.core.model.cad.GpkgLayerInfo
import es.topophone.core.model.log.AppLog
import es.topophone.core.model.log.LogCategory
import es.topophone.core.model.project.CentralPoint
import es.topophone.core.model.project.Project
import es.topophone.core.model.project.ProjectCrs
import es.topophone.core.survey.CadPlacement
import es.topophone.data.gpkg.GeoPackageReader
import es.topophone.data.gpkg.GeoPackageScan
import es.topophone.data.gpkg.GpkgLayerIndex
import es.topophone.data.gpkg.GpkgOpenFailure
import es.topophone.data.gpkg.GpkgOpenResult
import es.topophone.data.gpkg.GpkgSession
import es.topophone.data.project.ProjectRepository
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Por qué no se abre la pantalla de importación del GeoPackage (S048/RF-2 a RF-4). */
enum class GpkgFileProblem { EMPTY, NOT_GEOPACKAGE, DAMAGED, NO_LAYERS, TOO_BIG, NO_SPACE, UNREADABLE }

/** En qué punto está la importación de un GeoPackage. */
enum class GpkgImportStage { LOADING, PROBLEM, CONFIG, IMPORTING, RESULT }

/**
 * Una capa de la lista: lo que dice el fichero, si se importa (S048/RF-5), su sistema de coordenadas —el que declara el fichero y el que
 * eligió el usuario— (RF-8, RF-9) y dónde cae en el proyecto (RF-12).
 */
data class GpkgLayerRow(
    val info: GpkgLayerInfo,
    val marked: Boolean = true,
    /** Lo que declara el fichero frente a los sistemas de la app. */
    val crs: GpkgCrs = GpkgCrs.Undeclared,
    /** El sistema que el usuario eligió para esta capa en lugar del que declara el fichero. */
    val chosen: ProjectCrs? = null,
    /** La extensión de la capa en el sistema del proyecto (RF-12); null si no se ha calculado o no cabe en él. */
    val extent: CadExtent? = null,
    /** La capa tiene objetos y su extensión no cabe en el sistema del proyecto con el sistema elegido. */
    val outsideArea: Boolean = false,
    val distanceKm: Double? = null,
) {
    /** El sistema con el que se importa: el elegido, o el del fichero si es uno de la app; null mientras falte (RF-9). */
    val effective: GpkgCrs.Supported? get() = chosen?.let(GpkgCrsCatalog::supported) ?: (crs as? GpkgCrs.Supported)
}

/** Lo que muestra la pantalla de importar un GeoPackage (S048). */
data class GpkgImportUiState(
    val stage: GpkgImportStage = GpkgImportStage.LOADING,
    val fileName: String = "",
    val problem: GpkgFileProblem? = null,
    val projectName: String = "",
    val projectCrs: ProjectCrs = ProjectCrs.ETRS89_UTM30,
    val layers: List<GpkgLayerRow> = emptyList(),
    /** Las tablas sin geometría o de teselas que no se importan (RF-6). */
    val leftOutTables: List<String> = emptyList(),
    /** Se están calculando las extensiones de las capas (RF-12). */
    val scanning: Boolean = false,
    /** Alguna capa marcada queda a más de 100 km del centro del proyecto y hay que confirmarlo (RF-12). */
    val farWarning: Boolean = false,
    val farConfirmed: Boolean = false,
) {
    val markedLayers: List<GpkgLayerRow> get() = layers.filter { it.marked }

    /** Hay capas marcadas, todas con sistema, y los avisos están confirmados. */
    val canImport: Boolean
        get() = stage == GpkgImportStage.CONFIG &&
            !scanning &&
            markedLayers.isNotEmpty() &&
            markedLayers.all { it.effective != null } &&
            (!farWarning || farConfirmed)
}

/**
 * La importación de un GeoPackage a un proyecto (S048): reconoce el fichero por su contenido, lo abre de solo lectura y lista sus
 * capas con geometría para marcar cuáles se importan (RF-1 a RF-7); propone el sistema de coordenadas de cada una y deja elegir otro
 * (RF-8 a RF-10), y calcula su extensión y su distancia al centro del proyecto, con el aviso de más de 100 km (RF-12). La sesión del
 * fichero vive lo que la pantalla y se cierra, borrando su copia temporal, al salir (RNF-T5).
 */
@HiltViewModel
class GpkgImportViewModel @Inject constructor(
    private val reader: GeoPackageReader,
    private val projects: ProjectRepository,
    private val centers: ProjectCenterProvider,
    @param:IoDispatcher private val io: CoroutineDispatcher,
    private val log: AppLog = AppLog.NONE,
) : ViewModel() {
    private val mutable = MutableStateFlow(GpkgImportUiState())
    val state: StateFlow<GpkgImportUiState> = mutable.asStateFlow()

    private var started = false
    private var session: GpkgSession? = null
    private var project: Project? = null
    private var center: CentralPoint? = null
    private var scan: GeoPackageScan? = null
    private val indexes = ConcurrentHashMap<String, GpkgLayerIndex>()

    /** Prepara la importación de [source] en el proyecto [projectId]; solo la primera vez (RF-1). */
    fun start(projectId: String, source: ImportSource) {
        if (started) return
        started = true
        mutable.value = GpkgImportUiState(fileName = source.name)
        viewModelScope.launch(io) { load(projectId, source) }
    }

    private fun problem(problem: GpkgFileProblem) {
        mutable.update { it.copy(stage = GpkgImportStage.PROBLEM, problem = problem, scanning = false) }
    }

    private suspend fun load(projectId: String, source: ImportSource) {
        val project = projects.project(projectId) ?: return problem(GpkgFileProblem.UNREADABLE)
        this.project = project
        // Con el tamaño desconocido se cuenta el máximo para comprobar el espacio libre; el lector corta lo que pase del límite.
        val size = source.sizeBytes ?: GpkgFileKind.MAX_BYTES
        val result = try {
            reader.open(size) { source.open() ?: throw IOException("No se puede abrir el fichero") }
        } catch (_: IOException) {
            return problem(GpkgFileProblem.UNREADABLE)
        } catch (_: SecurityException) {
            return problem(GpkgFileProblem.UNREADABLE)
        }
        when (result) {
            is GpkgOpenResult.Failed -> {
                log.info(LogCategory.APP, "GeoPackage no abierto: ${result.reason.name.lowercase()}")
                problem(result.reason.toProblem())
            }
            is GpkgOpenResult.Opened -> {
                val opened = result.session
                session = opened
                scan = GeoPackageScan(opened)
                center = centers.center(project) // S006/RF-37: un único cálculo
                mutable.value = GpkgImportUiState(
                    stage = GpkgImportStage.CONFIG,
                    fileName = source.name,
                    projectName = project.name,
                    projectCrs = project.crs,
                    layers = opened.layers.map { GpkgLayerRow(it, crs = opened.crs(it)) },
                    leftOutTables = opened.leftOutTables,
                    scanning = true,
                )
                index(opened)
            }
        }
    }

    /** Las envolventes de cada capa, una vez; con ellas la extensión y la distancia se recalculan al cambiar el sistema (RF-12). */
    private suspend fun index(opened: GpkgSession) {
        val scan = scan ?: return
        for (layer in opened.layers) indexes[layer.table] = scan.index(layer)
        recompute()
        mutable.update { it.copy(scanning = false) }
    }

    private fun GpkgOpenFailure.toProblem() = when (this) {
        GpkgOpenFailure.EMPTY -> GpkgFileProblem.EMPTY
        GpkgOpenFailure.NOT_GEOPACKAGE -> GpkgFileProblem.NOT_GEOPACKAGE
        GpkgOpenFailure.DAMAGED -> GpkgFileProblem.DAMAGED
        GpkgOpenFailure.NO_LAYERS -> GpkgFileProblem.NO_LAYERS
        GpkgOpenFailure.TOO_LARGE -> GpkgFileProblem.TOO_BIG
        GpkgOpenFailure.NO_SPACE -> GpkgFileProblem.NO_SPACE
        GpkgOpenFailure.READ_ERROR -> GpkgFileProblem.UNREADABLE
    }

    /** Marca o desmarca la capa [table] (RF-5). */
    fun setMarked(table: String, marked: Boolean) {
        mutable.update { state ->
            state.copy(layers = state.layers.map { if (it.info.table == table) it.copy(marked = marked) else it }, farConfirmed = false)
        }
        recompute()
    }

    /** El usuario elige el sistema de coordenadas de la capa [table]; con null vuelve al que declara el fichero (RF-8, RF-9). */
    fun setCrs(table: String, crs: ProjectCrs?) {
        mutable.update { state ->
            state.copy(layers = state.layers.map { if (it.info.table == table) it.copy(chosen = crs) else it }, farConfirmed = false)
        }
        recompute()
    }

    fun confirmFar() = mutable.update { it.copy(farConfirmed = true) }

    /** La extensión de cada capa marcada en el sistema del proyecto y su distancia al centro, con los sistemas de ahora (RF-12). */
    private fun recompute() {
        val project = project ?: return
        val scan = scan ?: return
        mutable.update { state ->
            val layers = state.layers.map { row ->
                val index = indexes[row.info.table]
                val source = row.effective?.source
                if (!row.marked || index == null || source == null || index.size == 0) {
                    return@map row.copy(extent = null, outsideArea = false, distanceKm = null)
                }
                val extent = scan.extent(index, source, project.crs)
                val km = extent?.let { e -> center?.let { c -> scan.distanceToCenterM(e, project.crs, c) } }?.div(METRES_PER_KM)
                row.copy(extent = extent, outsideArea = extent == null, distanceKm = km)
            }
            state.copy(layers = layers, farWarning = layers.any { it.marked && CadPlacement.isFar(it.distanceKm) })
        }
    }

    override fun onCleared() {
        session?.close()
        session = null
    }

    private companion object {
        const val METRES_PER_KM = 1000.0
    }
}
