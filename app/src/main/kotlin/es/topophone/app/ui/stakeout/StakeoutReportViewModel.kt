package es.topophone.app.ui.stakeout

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import es.topophone.app.ui.export.ExportResult
import es.topophone.app.ui.export.ExportSelection
import es.topophone.core.formats.csv.CsvOptions
import es.topophone.core.formats.csv.ExportFileName
import es.topophone.core.formats.csv.StakeoutReportCsv
import es.topophone.core.model.log.AppLog
import es.topophone.core.model.log.LogCategory
import es.topophone.core.model.point.StakeoutReport
import es.topophone.core.model.project.Project
import es.topophone.data.export.ExportPreferences
import es.topophone.data.point.PointRepository
import es.topophone.data.project.ProjectRepository
import java.io.BufferedWriter
import java.io.IOException
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.time.Instant
import java.time.ZoneId
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Lo que muestra el informe de replanteo (S019/RF-17 a RF-20): el [report] con sus filas y su resumen, y si se puede exportar.
 * El separador y el signo decimal son los de la exportación de puntos (S012/RF-13).
 */
data class StakeoutReportUiState(
    val loaded: Boolean = false,
    val projectName: String = "",
    val project: Project? = null,
    val report: StakeoutReport = StakeoutReport.of(emptyList()),
    val options: CsvOptions = CsvOptions(),
    val result: ExportResult? = null,
) {
    /** Hay algún replanteo que informar y el separador y el decimal se distinguen (S019/RF-20, RF-23). */
    val canExport: Boolean get() = loaded && !report.isEmpty && options.valid
}

/**
 * El informe de replanteo de un proyecto o de los puntos seleccionados en la lista de objetos (S019/A4, A5): el resumen en
 * pantalla y el CSV, que se escribe en cualquier `OutputStream`, sea del selector de ficheros o de la carpeta de compartir.
 */
@HiltViewModel
class StakeoutReportViewModel @Inject constructor(
    private val points: PointRepository,
    private val projects: ProjectRepository,
    private val preferences: ExportPreferences,
    private val selection: ExportSelection,
    private val log: AppLog = AppLog.NONE,
) : ViewModel() {
    private class Content(val project: Project, val report: StakeoutReport)

    private val content = MutableStateFlow<Content?>(null)
    private val result = MutableStateFlow<ExportResult?>(null)

    val state: StateFlow<StakeoutReportUiState> = combine(content, preferences.options, result) { loaded, options, outcome ->
        if (loaded == null) return@combine StakeoutReportUiState()
        StakeoutReportUiState(true, loaded.project.name, loaded.project, loaded.report, options, outcome)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), StakeoutReportUiState())

    /**
     * Con [selectionOnly], solo los puntos de [ExportSelection] (S019/RF-18); si no, todos los del proyecto. La papelera no entra
     * nunca (RF-19): la exportación de puntos no la devuelve.
     */
    fun start(projectId: String, selectionOnly: Boolean) {
        if (content.value?.project?.id == projectId) return
        viewModelScope.launch {
            val project = projects.project(projectId) ?: return@launch
            val ids = if (selectionOnly) selection.ids else null
            content.value = Content(project, StakeoutReport.of(points.pointsForExport(projectId), ids))
        }
    }

    /** El nombre propuesto para el fichero: `<proyecto>_<etiqueta>_<AAAAMMDD-HHMM>.csv` (S019/RF-24; S012/RF-19). */
    fun fileName(label: String, at: Instant, zone: ZoneId): String = ExportFileName.of(state.value.projectName, label, at, zone)

    /** Escribe el CSV en [out] en UTF-8 sin BOM; false si no hay nada que informar o no se ha podido escribir (S019/RF-20, RF-24). */
    suspend fun writeTo(out: OutputStream): Boolean {
        val loaded = content.value ?: return false
        val current = state.value
        if (!current.canExport) return false
        return withContext(Dispatchers.IO) {
            try {
                BufferedWriter(OutputStreamWriter(out, Charsets.UTF_8)).use { writer ->
                    StakeoutReportCsv.write(loaded.report.rows, loaded.project, current.options.separator, current.options.decimal, writer)
                }
                true
            } catch (_: IOException) {
                false
            } catch (_: SecurityException) {
                false
            }
        }
    }

    /** Anota en el log cómo acabó guardar o compartir, sin rutas ni coordenadas (S014/RF-5), y lo enseña (S019/RF-24). */
    fun report(outcome: ExportResult?) {
        result.value = outcome
        val project = state.value.projectName
        when (outcome) {
            ExportResult.SAVED -> log.info(
                LogCategory.APP,
                "Informe de replanteo exportado: «$project», ${state.value.report.rows.size} replanteos",
            )
            ExportResult.FAILED -> log.error(LogCategory.APP, "No se pudo exportar el informe de replanteo de «$project»")
            null -> Unit
        }
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
