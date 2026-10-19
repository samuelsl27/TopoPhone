package es.topophone.app.ui.export

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.Lazy
import dagger.hilt.android.lifecycle.HiltViewModel
import es.topophone.core.formats.csv.BaseProjection
import es.topophone.core.formats.csv.ColumnSeparator
import es.topophone.core.formats.csv.CsvExport
import es.topophone.core.formats.csv.CsvOptions
import es.topophone.core.formats.csv.DecimalSeparator
import es.topophone.core.formats.csv.ExportColumn
import es.topophone.core.formats.csv.ExportFormat
import es.topophone.core.model.log.AppLog
import es.topophone.core.model.log.LogCategory
import es.topophone.core.model.point.GeodeticPosition
import es.topophone.core.model.point.Point
import es.topophone.core.model.point.PointBase
import es.topophone.core.model.project.Project
import es.topophone.core.survey.ProjectPositionCalculator
import es.topophone.data.export.ExportPreferences
import es.topophone.data.point.PointRepository
import es.topophone.data.project.ProjectRepository
import java.io.BufferedWriter
import java.io.IOException
import java.io.OutputStream
import java.io.OutputStreamWriter
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Los puntos elegidos en la lista de objetos para exportar (S012/RF-2). Va en un singleton para no pasar miles de ids
 * por la ruta; si Android cierra la app entretanto, se pierde y no hay puntos que exportar (S012/RF-4).
 */
@Singleton
class ExportSelection @Inject constructor() {
    @Volatile var ids: Set<String> = emptySet()
}

/** Cómo acabó una exportación (S012/RF-17, RF-18, RF-20). */
enum class ExportResult { SAVED, FAILED }

/** Lo que muestra la pantalla de exportación (S012). */
data class ExportUiState(
    val loaded: Boolean = false,
    val projectName: String = "",
    val pointCount: Int = 0,
    val formats: List<ExportFormat> = ExportFormat.entries,
    val options: CsvOptions = CsvOptions(),
    val result: ExportResult? = null,
) {
    /** Hay puntos, el separador y el decimal se distinguen y el personalizado tiene columnas (S012/RF-4, RF-8, RF-14). */
    val canExport: Boolean get() = loaded && pointCount > 0 && options.valid && options.columns.isNotEmpty()
}

/**
 * Exportación de los puntos de un proyecto, o de los seleccionados, a CSV (S012): los ajustes se recuerdan al
 * cambiarlos (S012/RF-21) y el fichero se escribe en cualquier `OutputStream`, sea del selector de ficheros o de la
 * carpeta de compartir.
 */
@HiltViewModel
class ExportViewModel @Inject constructor(
    private val points: PointRepository,
    private val projects: ProjectRepository,
    private val preferences: ExportPreferences,
    private val selection: ExportSelection,
    private val log: AppLog = AppLog.NONE,
    private val calculator: Lazy<ProjectPositionCalculator> = Lazy { ProjectPositionCalculator() },
) : ViewModel() {
    private data class Content(val project: Project, val points: List<Point>)

    private val content = MutableStateFlow<Content?>(null)
    private val result = MutableStateFlow<ExportResult?>(null)

    val state: StateFlow<ExportUiState> = combine(content, preferences.options, result) { loaded, remembered, outcome ->
        if (loaded == null) return@combine ExportUiState()
        val formats = CsvExport.formatsFor(loaded.project.crs)
        // S012/RF-10: lo último elegido puede no valer en este proyecto.
        val options = if (remembered.format in formats) remembered else remembered.copy(format = ExportFormat.ALL)
        ExportUiState(true, loaded.project.name, loaded.points.size, formats, options, outcome)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), ExportUiState())

    /** Con [selectionOnly], solo los puntos de [ExportSelection] (S012/RF-2); si no, todos los activos (S012/RF-1). */
    fun start(projectId: String, selectionOnly: Boolean) {
        if (content.value?.project?.id == projectId) return
        viewModelScope.launch {
            val project = projects.project(projectId) ?: return@launch
            val all = points.pointsForExport(projectId)
            val chosen = if (selectionOnly) selection.ids.let { ids -> all.filter { it.id in ids } } else all
            content.value = Content(project, chosen)
        }
    }

    fun setFormat(format: ExportFormat) = change { it.copy(format = format) }

    fun setSeparator(separator: ColumnSeparator) = change { it.copy(separator = separator) }

    fun setDecimal(decimal: DecimalSeparator) = change { it.copy(decimal = decimal) }

    /** S012/RF-8. */
    fun toggleColumn(column: ExportColumn) = change {
        it.copy(customColumns = if (column in it.customColumns) it.customColumns - column else it.customColumns + column)
    }

    /** Escribe el CSV en [out] en UTF-8 sin BOM (S012/RF-16); false si no se ha podido (S012/RF-20). El proyecto no cambia. */
    suspend fun writeTo(out: OutputStream): Boolean {
        val loaded = content.value ?: return false
        val options = state.value.options
        if (!state.value.canExport) return false
        return withContext(Dispatchers.IO) {
            try {
                BufferedWriter(OutputStreamWriter(out, Charsets.UTF_8)).use { writer ->
                    CsvExport.write(loaded.points.asSequence(), loaded.project, options, writer, baseProjection(loaded.project))
                }
                true
            } catch (_: IOException) {
                false
            } catch (_: SecurityException) {
                false
            }
        }
    }

    /**
     * La base de cada punto en el SC actual del proyecto (S015/RF-22). Casi todos los puntos comparten base: se calcula
     * una vez por posición.
     */
    private fun baseProjection(project: Project): (PointBase) -> BaseProjection {
        val known = HashMap<GeodeticPosition, BaseProjection>()
        return { base ->
            known.getOrPut(base.position) {
                val position = calculator.get().fromGround(base.position, project.crs, project.heightSystem)
                BaseProjection(position.easting, position.northing, position.height)
            }
        }
    }

    fun report(outcome: ExportResult?) {
        result.value = outcome
        // S014/RF-5
        val project = state.value.projectName
        when (outcome) {
            ExportResult.SAVED -> log.info(
                LogCategory.APP,
                "CSV exportado: «$project», ${state.value.pointCount} puntos, ${state.value.options.format}",
            )
            ExportResult.FAILED -> log.error(LogCategory.APP, "No se pudo exportar el CSV de «$project»")
            null -> Unit
        }
    }

    private fun change(update: (CsvOptions) -> CsvOptions) {
        viewModelScope.launch { preferences.save(update(preferences.options.first())) } // S012/RF-21
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
