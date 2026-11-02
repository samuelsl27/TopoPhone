package es.topophone.app.ui.linear

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import es.topophone.app.di.IoDispatcher
import es.topophone.core.formats.csv.ProfileCsv
import es.topophone.core.formats.csv.ProfileCsvRow
import es.topophone.core.model.log.AppLog
import es.topophone.core.model.log.LogCategory
import es.topophone.core.model.point.Point
import es.topophone.core.model.project.LinearUnit
import es.topophone.core.model.project.Project
import es.topophone.core.model.survey.linear.LinearSurvey
import es.topophone.core.model.survey.linear.LinearSurveyKind
import es.topophone.core.model.survey.linear.SurveyFlag
import es.topophone.core.model.survey.linear.SurveyPoint
import es.topophone.data.alignment.AlignmentRepository
import es.topophone.data.export.ExportPreferences
import es.topophone.data.linear.LinearSurveyRepository
import es.topophone.data.point.PointRepository
import es.topophone.data.project.ProjectRepository
import java.io.BufferedWriter
import java.io.IOException
import java.io.OutputStream
import java.io.OutputStreamWriter
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Una toma de la lista (S052/RF-22): su tipo, el eje (null si se borró), el PK de la sección o el rango del guiado, cuándo se hizo y cuántos
 * puntos forman su perfil. [open] es una toma sin terminar.
 */
data class LinearSurveyItem(
    val id: String,
    val kind: LinearSurveyKind,
    val alignmentName: String?,
    val chainageM: Double?,
    val chainageToM: Double?,
    val createdAtMs: Long,
    val finishedAtMs: Long?,
    val points: Int,
    val open: Boolean,
)

/** Un punto de una toma ya hecha, con lo que se guardó al medirlo (S052/RF-22). */
data class LinearSurveyDetailRow(
    val name: String,
    val chainageM: Double?,
    val offsetM: Double?,
    val groundM: Double?,
    val referenceM: Double?,
    val dHeightM: Double?,
    val code: String?,
    val flag: SurveyFlag,
    val included: Boolean,
) {
    val inProfile: Boolean get() = included && flag == SurveyFlag.NONE
}

/** Una toma abierta para verla: sus puntos y el alzado hecho con lo guardado (S052/RF-22). */
data class LinearSurveyDetail(val item: LinearSurveyItem, val rows: List<LinearSurveyDetailRow>, val chart: ProfileChart)

/** Lo que muestra la lista de tomas del proyecto (S052/RF-22). */
data class LinearSurveysUiState(
    val loading: Boolean = true,
    val projectName: String = "",
    val unit: LinearUnit = LinearUnit.METRE,
    val items: List<LinearSurveyItem> = emptyList(),
    val detail: LinearSurveyDetail? = null,
    val toDelete: LinearSurveyItem? = null,
)

/**
 * Los perfiles tomados de un proyecto (S052/RF-22): la lista con su tipo, eje, PK, fecha y número de puntos, verlos, exportarlos a CSV y
 * borrarlos con confirmación. Borrar una toma no borra ningún punto del proyecto.
 */
@HiltViewModel
class LinearSurveysViewModel @Inject constructor(
    private val surveys: LinearSurveyRepository,
    private val alignments: AlignmentRepository,
    private val points: PointRepository,
    private val projects: ProjectRepository,
    private val exports: ExportPreferences,
    @param:IoDispatcher private val io: CoroutineDispatcher,
    private val log: AppLog = AppLog.NONE,
) : ViewModel() {
    private val mutable = MutableStateFlow(LinearSurveysUiState())
    val state: StateFlow<LinearSurveysUiState> = mutable.asStateFlow()

    private var projectId: String? = null
    private var project: Project? = null

    /** Prepara la lista del proyecto [id]; solo la primera vez. */
    fun start(id: String) {
        if (projectId != null) return
        projectId = id
        viewModelScope.launch(io) {
            project = projects.project(id)
            val unit = project?.linearUnit ?: LinearUnit.METRE
            combine(surveys.surveys(id), alignments.summaries(id), surveys.profileCounts(id)) { list, axes, counts ->
                val names = axes.associate { it.id to it.name }
                list.sortedByDescending { it.createdAtMs }.map { it.toItem(names, counts) }
            }.collect { items ->
                mutable.update { it.copy(loading = false, projectName = project?.name.orEmpty(), unit = unit, items = items) }
            }
        }
    }

    private fun LinearSurvey.toItem(names: Map<String, String>, counts: Map<String, Int>) = LinearSurveyItem(
        id = id,
        kind = kind,
        alignmentName = alignmentId?.let { names[it] },
        chainageM = chainage ?: chainageFrom,
        chainageToM = chainageTo,
        createdAtMs = createdAtMs,
        finishedAtMs = finishedAtMs,
        points = counts[id] ?: 0,
        open = isOpen,
    )

    /** Abre una toma para verla: sus puntos con lo guardado y su alzado (RF-22). */
    fun view(id: String) {
        val item = mutable.value.items.firstOrNull { it.id == id } ?: return
        val project = projectId ?: return
        viewModelScope.launch(io) {
            val members = surveys.points(id).first()
            val byId = points.points(project).first().associateBy { it.id }
            val rows = members.mapNotNull { m -> byId[m.pointId]?.let { detailRow(it, m) } }
            mutable.update { it.copy(detail = LinearSurveyDetail(item, rows, chartOf(item.kind, rows))) }
        }
    }

    private fun detailRow(point: Point, m: SurveyPoint): LinearSurveyDetailRow {
        val ground = point.coordinates.height
        val reference = if (ground != null && m.dHeightM != null) ground - m.dHeightM!! else null
        return LinearSurveyDetailRow(point.name, m.chainageM, m.offsetM, ground, reference, m.dHeightM, m.code, m.flag, m.included)
    }

    /** El alzado con lo guardado: la referencia pasa por la cota de referencia de cada punto (la rasante o la sección en ese sitio). */
    private fun chartOf(kind: LinearSurveyKind, rows: List<LinearSurveyDetailRow>): ProfileChart {
        val axis = if (kind == LinearSurveyKind.CROSS) ProfileAxis.OFFSET else ProfileAxis.CHAINAGE
        fun x(r: LinearSurveyDetailRow) = if (axis == ProfileAxis.OFFSET) r.offsetM else r.chainageM
        val drawn = rows.filter { it.included && x(it) != null && it.groundM != null }.sortedBy { x(it) }
        val chart = drawn.map { ProfileChartPoint(it.name, x(it)!!, it.groundM!!, it.referenceM, it.dHeightM, it.inProfile, it.flag) }
        val reference = chart.filter { it.inProfile && it.referenceM != null }.map { it.x to it.referenceM!! }
        return ProfileChart(axis, chart, reference)
    }

    fun closeDetail() = mutable.update { it.copy(detail = null) }

    fun requestDelete(id: String) = mutable.update { state -> state.copy(toDelete = state.items.firstOrNull { it.id == id }) }

    fun cancelDelete() = mutable.update { it.copy(toDelete = null) }

    /** Borra la toma pedida (con su confirmación hecha) sin tocar ningún punto del proyecto (RF-22). */
    fun confirmDelete() {
        val item = mutable.value.toDelete ?: return
        mutable.update { it.copy(toDelete = null, detail = it.detail?.takeIf { d -> d.item.id != item.id }) }
        viewModelScope.launch(io) { surveys.delete(item.id) }
    }

    /** Las filas del CSV de la toma que se está viendo: los puntos de su perfil, por PK o por desplazamiento (RF-22). */
    fun csvRows(): List<ProfileCsvRow> {
        val detail = mutable.value.detail ?: return emptyList()
        val cross = detail.item.kind == LinearSurveyKind.CROSS
        return detail.rows.filter { it.inProfile && it.chainageM != null && it.offsetM != null }
            .sortedBy { if (cross) it.offsetM else it.chainageM }
            .mapIndexed { i, r ->
                ProfileCsvRow(if (cross) r.code ?: "P${i + 1}" else null, r.chainageM!!, r.offsetM!!, r.groundM, r.referenceM, r.dHeightM)
            }
    }

    /** Escribe el CSV de la toma que se está viendo en [out]; false si no hay nada que exportar o no se pudo (RF-22). */
    suspend fun writeCsv(out: OutputStream): Boolean {
        val project = project ?: return false
        val kind = mutable.value.detail?.item?.kind ?: return false
        val rows = csvRows()
        val options = exports.options.first()
        if (kind == LinearSurveyKind.AXIS || rows.isEmpty() || !options.valid) return false
        return withContext(Dispatchers.IO) {
            try {
                BufferedWriter(OutputStreamWriter(out, Charsets.UTF_8)).use { writer ->
                    ProfileCsv.write(kind, rows, project, options.separator, options.decimal, writer)
                }
                log.info(LogCategory.APP, "Perfil exportado desde la lista: ${rows.size} puntos")
                true
            } catch (_: IOException) {
                false
            } catch (_: SecurityException) {
                false
            }
        }
    }
}
