package es.topophone.app.ui.alignment

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import es.topophone.app.di.IoDispatcher
import es.topophone.app.ui.cad.DesignSelection
import es.topophone.app.ui.stakeout.StakeLines
import es.topophone.core.model.alignment.AlignmentIssue
import es.topophone.core.model.alignment.AlignmentSource
import es.topophone.core.model.log.AppLog
import es.topophone.core.model.log.LogCategory
import es.topophone.core.model.project.ProjectCrs
import es.topophone.core.survey.AlignmentDraft
import es.topophone.core.survey.PolylineAlignmentBuilder
import es.topophone.core.survey.PolylineResult
import es.topophone.data.alignment.AlignmentImportResult
import es.topophone.data.alignment.AlignmentImporter
import es.topophone.data.project.ProjectRepository
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Cómo acabó convertir una polilínea de un dibujo en un eje (S049/RF-6, RF-7). */
sealed interface DrawingAlignmentResult {
    /** El eje se creó: su nombre, longitud, PK inicial, y si lleva rasante con las cotas de la línea. */
    data class Created(val name: String, val lengthM: Double, val startChainage: Double, val hasProfile: Boolean) : DrawingAlignmentResult

    /** El eje no se creó y por qué (RF-6, RF-12). */
    data class Rejected(val issue: AlignmentIssue) : DrawingAlignmentResult

    data object WriteError : DrawingAlignmentResult
}

data class AlignmentFromDrawingUiState(val working: Boolean = false, val result: DrawingAlignmentResult? = null)

/**
 * Convierte una polilínea de un dibujo del proyecto en un eje (S049/RF-6, RF-7, plan A5): desde el extremo más cercano al punto tocado y con
 * el PK inicial que se escriba; los arcos de sus curvas se reconstruyen y, si la línea trae cotas, se crea la rasante con sus vértices.
 */
@HiltViewModel
class AlignmentFromDrawingViewModel @Inject constructor(
    private val importer: AlignmentImporter,
    private val projects: ProjectRepository,
    @param:IoDispatcher private val io: CoroutineDispatcher,
    private val log: AppLog = AppLog.NONE,
    private val stakeLines: StakeLines = StakeLines.NONE,
) : ViewModel() {
    private val mutable = MutableStateFlow(AlignmentFromDrawingUiState())
    val state: StateFlow<AlignmentFromDrawingUiState> = mutable.asStateFlow()

    /** Crea el eje de la línea tocada [selection] con el nombre [name] y el PK inicial [startChainageM] en metros. */
    fun convert(projectId: String, selection: DesignSelection, name: String, startChainageM: Double) {
        if (mutable.value.working || name.isBlank()) return
        mutable.update { AlignmentFromDrawingUiState(working = true) }
        viewModelScope.launch(io) {
            mutable.update { AlignmentFromDrawingUiState(result = run(projectId, selection, name.trim(), startChainageM)) }
        }
    }

    /**
     * Crea el eje de la línea abierta [lineId] del proyecto (S027/RF-17): sus vértices son tramos rectos desde el primero, sus alturas son la
     * rasante sin acuerdos si hay al menos dos, y la línea y sus puntos no se tocan.
     */
    fun convertLine(projectId: String, lineId: String, name: String, startChainageM: Double) {
        if (mutable.value.working || name.isBlank()) return
        mutable.update { AlignmentFromDrawingUiState(working = true) }
        viewModelScope.launch(io) {
            mutable.update { AlignmentFromDrawingUiState(result = runLine(projectId, lineId, name.trim(), startChainageM)) }
        }
    }

    private suspend fun runLine(projectId: String, lineId: String, name: String, startChainageM: Double): DrawingAlignmentResult {
        val project = projects.project(projectId) ?: return DrawingAlignmentResult.WriteError
        val data = stakeLines.observe(lineId).first()
        // Un polígono no es un eje; una línea que no existe o sin Este y Norte tampoco tiene con qué (RF-17).
        if (data == null || data.line.closed || data.vertices.any { it.easting == null || it.northing == null }) {
            return DrawingAlignmentResult.Rejected(AlignmentIssue.NotAPolyline)
        }
        val east = DoubleArray(data.vertices.size) { data.vertices[it].easting!! }
        val north = DoubleArray(data.vertices.size) { data.vertices[it].northing!! }
        val height = if (data.vertices.any {
                it.height != null
            }
        ) {
            DoubleArray(data.vertices.size) { data.vertices[it].height ?: Double.NaN }
        } else {
            null
        }
        val built = when (val result = PolylineAlignmentBuilder.buildFromVertices(east, north, height, east[0], north[0], startChainageM)) {
            is PolylineResult.Failed -> return DrawingAlignmentResult.Rejected(result.issue)
            is PolylineResult.Built -> result
        }
        return save(
            projectId,
            project.crs,
            name,
            null,
            AlignmentSource.PROJECT_LINE,
            AlignmentDraft(built.elements, profile = built.profile),
        )
    }

    private suspend fun run(projectId: String, selection: DesignSelection, name: String, startChainageM: Double): DrawingAlignmentResult {
        val project = projects.project(projectId) ?: return DrawingAlignmentResult.WriteError
        val entity = selection.entity
        val built = when (
            val result = PolylineAlignmentBuilder.build(
                entity,
                selection.tapEasting ?: entity.easting[0],
                selection.tapNorthing ?: entity.northing[0],
                startChainageM,
            )
        ) {
            is PolylineResult.Failed -> return DrawingAlignmentResult.Rejected(result.issue)
            is PolylineResult.Built -> result
        }
        return save(
            projectId,
            project.crs,
            name,
            selection.drawingName,
            AlignmentSource.DRAWING,
            AlignmentDraft(built.elements, profile = built.profile),
        )
    }

    private suspend fun save(
        projectId: String,
        crs: ProjectCrs,
        name: String,
        fileName: String?,
        origin: AlignmentSource,
        draft: AlignmentDraft,
    ): DrawingAlignmentResult {
        return when (val result = importer.importDraft(projectId, crs, name, fileName, origin, draft)) {
            is AlignmentImportResult.Failed -> DrawingAlignmentResult.WriteError
            is AlignmentImportResult.Done -> {
                val imported = result.summary.imported.firstOrNull()
                    ?: return DrawingAlignmentResult.Rejected(result.summary.ignored.first().issue)
                // S049/RF-16: el log lleva el hecho, nunca el nombre ni las coordenadas.
                log.info(LogCategory.APP, "Eje creado desde una polilínea de un dibujo")
                DrawingAlignmentResult.Created(imported.name, imported.lengthM, imported.startChainage, imported.hasProfile)
            }
        }
    }

    fun dismiss() = mutable.update { AlignmentFromDrawingUiState() }
}
