package es.topophone.app.ui.gpkg

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import es.topophone.app.di.IoDispatcher
import es.topophone.app.ui.points.ImportSource
import es.topophone.core.formats.gpkg.GpkgFileKind
import es.topophone.core.model.cad.GpkgLayerInfo
import es.topophone.core.model.log.AppLog
import es.topophone.core.model.log.LogCategory
import es.topophone.core.model.project.ProjectCrs
import es.topophone.data.gpkg.GeoPackageReader
import es.topophone.data.gpkg.GpkgOpenFailure
import es.topophone.data.gpkg.GpkgOpenResult
import es.topophone.data.gpkg.GpkgSession
import es.topophone.data.project.ProjectRepository
import java.io.IOException
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

/** Una capa de la lista: lo que dice el fichero y si se importa (S048/RF-5). */
data class GpkgLayerRow(val info: GpkgLayerInfo, val marked: Boolean = true)

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
) {
    val markedLayers: List<GpkgLayerRow> get() = layers.filter { it.marked }

    /** Hay al menos una capa marcada y nada lo impide. */
    val canImport: Boolean get() = stage == GpkgImportStage.CONFIG && markedLayers.isNotEmpty()
}

/**
 * La importación de un GeoPackage a un proyecto (S048): reconoce el fichero por su contenido, lo abre de solo lectura y lista sus
 * capas con geometría para marcar cuáles se importan (RF-1 a RF-7). La sesión del fichero vive lo que la pantalla y se cierra,
 * borrando su copia temporal, al salir (RNF-T5).
 */
@HiltViewModel
class GpkgImportViewModel @Inject constructor(
    private val reader: GeoPackageReader,
    private val projects: ProjectRepository,
    @param:IoDispatcher private val io: CoroutineDispatcher,
    private val log: AppLog = AppLog.NONE,
) : ViewModel() {
    private val mutable = MutableStateFlow(GpkgImportUiState())
    val state: StateFlow<GpkgImportUiState> = mutable.asStateFlow()

    private var started = false
    private var session: GpkgSession? = null

    /** Prepara la importación de [source] en el proyecto [projectId]; solo la primera vez (RF-1). */
    fun start(projectId: String, source: ImportSource) {
        if (started) return
        started = true
        mutable.value = GpkgImportUiState(fileName = source.name)
        viewModelScope.launch(io) { load(projectId, source) }
    }

    private fun problem(problem: GpkgFileProblem) {
        mutable.update { it.copy(stage = GpkgImportStage.PROBLEM, problem = problem) }
    }

    private suspend fun load(projectId: String, source: ImportSource) {
        val project = projects.project(projectId) ?: return problem(GpkgFileProblem.UNREADABLE)
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
                session = result.session
                mutable.value = GpkgImportUiState(
                    stage = GpkgImportStage.CONFIG,
                    fileName = source.name,
                    projectName = project.name,
                    projectCrs = project.crs,
                    layers = result.session.layers.map { GpkgLayerRow(it) },
                    leftOutTables = result.session.leftOutTables,
                )
            }
        }
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
            state.copy(layers = state.layers.map { if (it.info.table == table) it.copy(marked = marked) else it })
        }
    }

    override fun onCleared() {
        session?.close()
        session = null
    }
}
