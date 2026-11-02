package es.topophone.app.ui.linear

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import es.topophone.app.di.IoDispatcher
import es.topophone.app.ui.collector.PointSavedListener
import es.topophone.core.model.alignment.AlignmentSource
import es.topophone.core.model.format.NumberInput
import es.topophone.core.model.log.AppLog
import es.topophone.core.model.log.LogCategory
import es.topophone.core.model.point.Point
import es.topophone.core.model.project.LinearUnit
import es.topophone.core.model.survey.linear.LinearSurvey
import es.topophone.core.model.survey.linear.LinearSurveyKind
import es.topophone.core.model.survey.linear.SurveyFlag
import es.topophone.core.model.survey.linear.SurveyPoint
import es.topophone.core.survey.FieldAxisBuilder
import es.topophone.core.survey.FieldAxisIssue
import es.topophone.core.survey.FieldAxisResult
import es.topophone.core.survey.FieldVertex
import es.topophone.core.survey.PolylineLength
import es.topophone.data.alignment.AlignmentRepository
import es.topophone.data.alignment.AlignmentToSave
import es.topophone.data.linear.FinishAxisResult
import es.topophone.data.linear.LinearSurveyRepository
import es.topophone.data.point.PointRepository
import es.topophone.data.project.ProjectRepository
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Un punto de la toma con lo que se ve de él: su nombre, su posición, su cota y si sigue incluido (S052/RF-3, RF-4). */
data class AxisSurveyRow(
    val pointId: String,
    val name: String,
    val easting: Double,
    val northing: Double,
    val heightM: Double?,
    val included: Boolean,
)

/**
 * Lo que se escribe al terminar la toma (S052/RF-5, RF-7): el nombre, el PK inicial y el radio de cada vértice interior en la unidad del
 * proyecto (vacío: sin curva), por número de vértice desde 1, y si se crea también la rasante.
 */
data class AxisFinishDraft(
    val name: String = "",
    val startChainage: String = "0",
    val radii: Map<Int, String> = emptyMap(),
    val withGrade: Boolean = false,
)

/** Qué impide crear el eje (S052/RF-5, RF-6). El vértice se cuenta desde 1 entre los puntos incluidos. */
sealed interface AxisSurveyProblem {
    data object EmptyName : AxisSurveyProblem

    data object NameTaken : AxisSurveyProblem

    /** El PK inicial (vértice null) o el radio del vértice no es un número. */
    data class InvalidNumber(val vertex: Int?) : AxisSurveyProblem

    data object TooFewPoints : AxisSurveyProblem

    data class CoincidentPoints(val vertex: Int) : AxisSurveyProblem

    data class InvalidRadius(val vertex: Int) : AxisSurveyProblem

    data class RadiusDoesNotFit(val vertex: Int) : AxisSurveyProblem

    data object WriteError : AxisSurveyProblem
}

/** El eje que se acaba de crear (S052/RF-5, RF-8): su nombre, su longitud, si se activó y si tiene rasante. */
data class AxisSurveyResult(val name: String, val lengthM: Double, val activated: Boolean, val hasGrade: Boolean)

/** Lo que muestra el panel de «Tomar eje» (S052/RF-1 a RF-9). */
data class AxisSurveyUiState(
    val loading: Boolean = true,
    val unit: LinearUnit = LinearUnit.METRE,
    val survey: LinearSurvey? = null,
    val rows: List<AxisSurveyRow> = emptyList(),
    /** La longitud en planta de los puntos incluidos (RF-3). */
    val lengthM: Double = 0.0,
    /** Todos los puntos incluidos tienen cota: se puede pedir la rasante (RF-7). */
    val canGrade: Boolean = false,
    val finish: AxisFinishDraft? = null,
    val problem: AxisSurveyProblem? = null,
    val saving: Boolean = false,
    val result: AxisSurveyResult? = null,
) {
    val included: List<AxisSurveyRow> get() = rows.filter { it.included }
}

/**
 * La toma de un eje con el receptor (S052/RF-1 a RF-9). Los puntos los mide el recolector de siempre y son puntos normales del proyecto
 * (RF-2); esta toma solo los agrupa, en el orden de medida, y los une para dibujar y medir la poligonal (RF-3). «Quitar último» excluye
 * el último incluido sin borrarlo (RF-4). Al terminar crea el eje con rectas por los puntos y arcos donde se dio el radio (RF-5, RF-6),
 * con rasante si se pide (RF-7), con el origen «Tomado en campo» (RF-8). Una toma abierta se reanuda al volver (RF-9).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class AxisSurveyViewModel @Inject constructor(
    private val surveys: LinearSurveyRepository,
    private val alignments: AlignmentRepository,
    private val points: PointRepository,
    private val projects: ProjectRepository,
    @param:IoDispatcher private val io: CoroutineDispatcher,
    private val log: AppLog = AppLog.NONE,
) : ViewModel() {
    private val mutable = MutableStateFlow(AxisSurveyUiState())
    val state: StateFlow<AxisSurveyUiState> = mutable.asStateFlow()

    private var projectId: String? = null

    /** Quien recibe cada punto que guarda el recolector y lo añade a la toma abierta (RF-2). */
    val pointListener = PointSavedListener { saved ->
        val survey = mutable.value.survey ?: return@PointSavedListener
        surveys.addPoint(survey.id, SurveyPoint(saved.id, 0, true, null, null, null, SurveyFlag.NONE))
    }

    /** Prepara la toma del proyecto [id]: si ya hay una abierta la recupera (RF-9). Solo la primera vez. */
    fun start(id: String) {
        if (projectId != null) return
        projectId = id
        viewModelScope.launch(io) {
            val unit = projects.project(id)?.linearUnit ?: LinearUnit.METRE
            val open = surveys.surveys(id).map { list -> list.firstOrNull { it.kind == LinearSurveyKind.AXIS && it.isOpen } }
            open.flatMapLatest { survey ->
                if (survey == null) {
                    flowOf(Triple(null, emptyList<SurveyPoint>(), emptyList<Point>()))
                } else {
                    combine(surveys.points(survey.id), points.points(id)) { members, all -> Triple(survey, members, all) }
                }
            }.collect { (survey, members, all) ->
                val byId = all.associateBy { it.id }
                val rows = members.mapNotNull { member ->
                    val point = byId[member.pointId] ?: return@mapNotNull null
                    val c = point.coordinates
                    val easting = c.easting ?: return@mapNotNull null
                    val northing = c.northing ?: return@mapNotNull null
                    AxisSurveyRow(point.id, point.name, easting, northing, c.height, member.included)
                }
                val included = rows.filter { it.included }
                mutable.update {
                    it.copy(
                        loading = false,
                        unit = unit,
                        survey = survey,
                        rows = rows,
                        lengthM = PolylineLength.of(included.map { r -> FieldVertex(r.easting, r.northing) }),
                        canGrade = included.isNotEmpty() && included.all { r -> r.heightM != null },
                    )
                }
            }
        }
    }

    /** Empieza una toma de eje si no hay ninguna abierta (RF-1). */
    fun begin() {
        val id = projectId ?: return
        if (mutable.value.survey != null) return
        viewModelScope.launch(io) {
            surveys.open(
                LinearSurvey(UUID.randomUUID().toString(), id, LinearSurveyKind.AXIS, null, "", createdAtMs = System.currentTimeMillis()),
            )
        }
    }

    /** Excluye de la toma el último punto incluido sin borrarlo del proyecto (RF-4). */
    fun removeLast() {
        val survey = mutable.value.survey ?: return
        val last = mutable.value.included.lastOrNull() ?: return
        viewModelScope.launch(io) { surveys.setIncluded(survey.id, last.pointId, false) }
    }

    /** Descarta la toma abierta sin crear eje; los puntos se quedan en el proyecto (RF-8). */
    fun discard() {
        val survey = mutable.value.survey ?: return
        mutable.update { it.copy(finish = null, problem = null) }
        viewModelScope.launch(io) { surveys.delete(survey.id) }
    }

    /**
     * Abre el formulario de terminar con un nombre propuesto, [baseName] o ese con un número si otro eje ya lo tiene (RF-5). El texto lo
     * pone la pantalla, que es quien tiene los recursos.
     */
    fun startFinish(baseName: String) {
        val id = projectId ?: return
        if (mutable.value.included.size < 2) {
            mutable.update { it.copy(problem = AxisSurveyProblem.TooFewPoints) }
            return
        }
        viewModelScope.launch(io) {
            val names = alignments.summaries(id).first().map { it.name }.toSet()
            mutable.update { it.copy(finish = AxisFinishDraft(name = proposedName(baseName, names)), problem = null) }
        }
    }

    fun updateFinish(change: (AxisFinishDraft) -> AxisFinishDraft) = mutable.update {
        it.finish?.let { draft -> it.copy(finish = change(draft), problem = null) } ?: it
    }

    fun cancelFinish() = mutable.update { it.copy(finish = null, problem = null) }

    fun dismissResult() = mutable.update { it.copy(result = null) }

    /** Crea el eje con lo escrito; si no se puede, dice el vértice y el motivo y no crea nada (RF-5, RF-6). */
    fun finish() {
        val current = mutable.value
        val draft = current.finish ?: return
        val survey = current.survey ?: return
        val id = projectId ?: return
        val name = draft.name.trim()
        if (name.isEmpty()) return fail(AxisSurveyProblem.EmptyName)
        val start = if (draft.startChainage.isBlank()) 0.0 else NumberInput.parse(draft.startChainage)?.let(current.unit::toMetres)
        if (start == null) return fail(AxisSurveyProblem.InvalidNumber(null))
        val included = current.included
        val vertices = included.mapIndexed { i, row ->
            val text = draft.radii[i + 1].orEmpty()
            val radius = if (text.isBlank()) 0.0 else NumberInput.parse(text)?.let(current.unit::toMetres)
            if (radius == null) return fail(AxisSurveyProblem.InvalidNumber(i + 1))
            FieldVertex(row.easting, row.northing, row.heightM, radius)
        }
        mutable.update { it.copy(saving = true, problem = null) }
        viewModelScope.launch(io) {
            if (alignments.summaries(id).first().any { it.name.equals(name, ignoreCase = true) }) {
                mutable.update { it.copy(saving = false, problem = AxisSurveyProblem.NameTaken) }
                return@launch
            }
            when (val built = FieldAxisBuilder.build(vertices, start, draft.withGrade && current.canGrade)) {
                is FieldAxisResult.Failed -> mutable.update { it.copy(saving = false, problem = built.issue.toProblem()) }
                is FieldAxisResult.Built -> {
                    val item = AlignmentToSave(name, null, AlignmentSource.FIELD, built.elements, built.profile, built.extent, false)
                    when (val done = surveys.finishAxis(survey.id, item)) {
                        is FinishAxisResult.Finished -> {
                            log.info(LogCategory.APP, "Eje tomado en campo")
                            val length = built.elements.sumOf { it.length }
                            mutable.update {
                                it.copy(
                                    saving = false,
                                    finish = null,
                                    result = AxisSurveyResult(name, length, done.activatedId != null, built.profile != null),
                                )
                            }
                        }
                        FinishAxisResult.NotOpen -> mutable.update { it.copy(saving = false, problem = AxisSurveyProblem.WriteError) }
                    }
                }
            }
        }
    }

    private fun fail(problem: AxisSurveyProblem) = mutable.update { it.copy(problem = problem) }

    private fun proposedName(base: String, taken: Set<String>): String {
        val lower = taken.map { it.lowercase() }.toSet()
        if (base.lowercase() !in lower) return base
        return generateSequence(2) { it + 1 }.map { "$base $it" }.first { it.lowercase() !in lower }
    }

    private fun FieldAxisIssue.toProblem(): AxisSurveyProblem = when (this) {
        FieldAxisIssue.TooFewPoints -> AxisSurveyProblem.TooFewPoints
        is FieldAxisIssue.CoincidentPoints -> AxisSurveyProblem.CoincidentPoints(vertex)
        is FieldAxisIssue.InvalidRadius -> AxisSurveyProblem.InvalidRadius(vertex)
        is FieldAxisIssue.RadiusDoesNotFit -> AxisSurveyProblem.RadiusDoesNotFit(vertex)
    }
}
