package es.topophone.app.ui.linear

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import es.topophone.app.di.IoDispatcher
import es.topophone.app.ui.collector.PointSavedListener
import es.topophone.app.ui.collector.SavedPoint
import es.topophone.app.ui.export.ExportSelection
import es.topophone.core.formats.csv.ProfileCsv
import es.topophone.core.formats.csv.ProfileCsvRow
import es.topophone.core.model.alignment.Alignment
import es.topophone.core.model.format.NumberInput
import es.topophone.core.model.log.AppLog
import es.topophone.core.model.log.LogCategory
import es.topophone.core.model.point.Point
import es.topophone.core.model.point.PointCoordinates
import es.topophone.core.model.project.LinearUnit
import es.topophone.core.model.project.Project
import es.topophone.core.model.survey.linear.LinearSurvey
import es.topophone.core.model.survey.linear.LinearSurveyKind
import es.topophone.core.model.survey.linear.SurveyFlag
import es.topophone.core.model.survey.linear.SurveyPoint
import es.topophone.core.survey.AlignmentGeometry
import es.topophone.core.survey.ProfileSampler
import es.topophone.core.survey.VerticalProfileEval
import es.topophone.data.alignment.AlignmentRepository
import es.topophone.data.export.ExportPreferences
import es.topophone.data.linear.LinearSurveyRepository
import es.topophone.data.point.PointRepository
import es.topophone.data.project.ProjectRepository
import java.io.BufferedWriter
import java.io.IOException
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.util.UUID
import javax.inject.Inject
import kotlin.math.max
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
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
import kotlinx.coroutines.withContext

/** Un punto del perfil longitudinal con lo que se calculó al medirlo (S052/RF-11, RF-12): PK, desplazamiento, cotas y marca. */
data class LongitudinalRow(
    val pointId: String,
    val name: String,
    val chainageM: Double?,
    val offsetM: Double?,
    val groundM: Double?,
    val referenceM: Double?,
    val dHeightM: Double?,
    val flag: SurveyFlag,
    val included: Boolean,
) {
    val inProfile: Boolean get() = included && flag == SurveyFlag.NONE
}

/**
 * Cómo se empieza el perfil (S052/RF-10): guiado, con el PK inicial, el final y el intervalo a los que la guía de replanteo lleva, o
 * libre, caminando por el eje con el modo automático por distancia. La tolerancia de eje va en la unidad del proyecto (RF-12).
 */
data class LongitudinalSetup(
    val guided: Boolean = true,
    val from: String = "",
    val to: String = "",
    val interval: String = "",
    val tolerance: String = "",
)

/** El campo de la preparación que no vale (S052/RF-10, RF-12). */
enum class LongitudinalField { FROM, TO, INTERVAL, TOLERANCE }

sealed interface LongitudinalProblem {
    data class InvalidNumber(val field: LongitudinalField) : LongitudinalProblem

    /** El PK inicial o final queda fuera del eje. */
    data class OutsideAxis(val field: LongitudinalField) : LongitudinalProblem

    /** El intervalo o la tolerancia no son positivos. */
    data class NotPositive(val field: LongitudinalField) : LongitudinalProblem

    /** Ninguno de los puntos elegidos se pudo añadir. */
    data object NoPoints : LongitudinalProblem

    data object WriteError : LongitudinalProblem
}

/** Lo que muestra el panel del perfil longitudinal (S052/RF-10 a RF-15, RF-21). */
data class LongitudinalUiState(
    val loading: Boolean = true,
    val unit: LinearUnit = LinearUnit.METRE,
    val alignment: Alignment? = null,
    /** El eje tiene rasante: sin ella solo se dibuja el terreno (caso límite de RF-13). */
    val hasGrade: Boolean = false,
    val survey: LinearSurvey? = null,
    val rows: List<LongitudinalRow> = emptyList(),
    val chart: ProfileChart = ProfileChart(ProfileAxis.CHAINAGE, emptyList(), emptyList()),
    val setup: LongitudinalSetup = LongitudinalSetup(),
    val problem: LongitudinalProblem? = null,
    /** Cuántos puntos hay elegidos en la lista de objetos para construir el perfil con ellos (RF-14). */
    val selectionCount: Int = 0,
) {
    /** Los puntos incluidos que no entran en el perfil, con su marca (RF-12). */
    val rejected: List<LongitudinalRow> get() = rows.filter { it.included && it.flag != SurveyFlag.NONE }
    val inProfile: List<LongitudinalRow> get() = rows.filter { it.inProfile }
}

/**
 * El perfil longitudinal del terreno a lo largo de un eje (S052/RF-10 a RF-15, RF-21). Los puntos los mide el recolector (guiado por
 * el replanteo por PK o libre con el modo automático por distancia) o ya están en el proyecto (selección): de cada uno se guarda su PK,
 * su desplazamiento reales, la diferencia de cota con la rasante y la marca según la tolerancia de eje (RF-11, RF-12, RF-14). Quitar un
 * punto del perfil no lo borra del proyecto (RF-21).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class LongitudinalViewModel @Inject constructor(
    private val surveys: LinearSurveyRepository,
    private val alignments: AlignmentRepository,
    private val points: PointRepository,
    private val projects: ProjectRepository,
    private val exports: ExportPreferences,
    private val selection: ExportSelection,
    @param:IoDispatcher private val io: CoroutineDispatcher,
    private val log: AppLog = AppLog.NONE,
) : ViewModel() {
    private val mutable = MutableStateFlow(LongitudinalUiState())
    val state: StateFlow<LongitudinalUiState> = mutable.asStateFlow()

    private var projectId: String? = null
    private var alignmentId: String? = null
    private var project: Project? = null
    private var geometry: AlignmentGeometry? = null
    private var grade: VerticalProfileEval? = null

    /** Quien recibe cada punto que guarda el recolector y lo añade al perfil abierto (RF-11). */
    val pointListener = PointSavedListener { saved -> add(saved) }

    /** Prepara el perfil del eje [alignment] del proyecto [project]: si hay uno abierto lo recupera. Solo la primera vez. */
    fun start(project: String, alignment: String) {
        if (projectId != null) return
        projectId = project
        alignmentId = alignment
        viewModelScope.launch(io) {
            this@LongitudinalViewModel.project = projects.project(project)
            val unit = this@LongitudinalViewModel.project?.linearUnit ?: LinearUnit.METRE
            val axis = alignments.alignment(alignment)
            geometry = axis?.let { AlignmentGeometry(it.elements) }
            grade = axis?.profile?.let { VerticalProfileEval(it) }
            mutable.update { it.copy(unit = unit, alignment = axis, hasGrade = axis?.profile != null, selectionCount = selection.ids.size) }
            surveys.surveys(project).map { list ->
                list.firstOrNull { it.kind == LinearSurveyKind.LONGITUDINAL && it.alignmentId == alignment && it.isOpen }
            }.flatMapLatest { survey ->
                if (survey == null) {
                    flowOf(Triple(null, emptyList<SurveyPoint>(), emptyList<Point>()))
                } else {
                    combine(surveys.points(survey.id), points.points(project)) { members, all -> Triple(survey, members, all) }
                }
            }.collect { (survey, members, all) -> publish(survey, members, all) }
        }
    }

    private fun publish(survey: LinearSurvey?, members: List<SurveyPoint>, all: List<Point>) {
        val byId = all.associateBy { it.id }
        val rows = members.mapNotNull { member ->
            val point = byId[member.pointId] ?: return@mapNotNull null
            val ground = point.coordinates.height
            val reference = if (ground != null && member.dHeightM != null) ground - member.dHeightM!! else null
            LongitudinalRow(
                point.id, point.name, member.chainageM, member.offsetM, ground, reference, member.dHeightM, member.flag, member.included,
            )
        }
        mutable.update { it.copy(loading = false, survey = survey, rows = rows, chart = chartOf(rows)) }
    }

    /** El gráfico con los puntos incluidos por PK creciente y la rasante muestreada entre el primero y el último (RF-13). */
    private fun chartOf(rows: List<LongitudinalRow>): ProfileChart {
        val drawn = rows.filter { it.included && it.chainageM != null && it.groundM != null }
            .sortedBy { it.chainageM }
            .map { r ->
                ProfileChartPoint(r.name, r.chainageM!!, r.groundM!!, r.referenceM, r.dHeightM, r.inProfile, r.flag)
            }
        val inProfile = drawn.filter { it.inProfile }
        val eval = grade
        val reference = if (eval == null || inProfile.size < 2) {
            emptyList()
        } else {
            val low = inProfile.first().x
            val high = inProfile.last().x
            val steps = max(1, REFERENCE_SAMPLES - 1)
            (0..steps).mapNotNull { i ->
                val x = low + (high - low) * i / steps
                eval.at(x)?.let { x to it.elevation }
            }
        }
        return ProfileChart(ProfileAxis.CHAINAGE, drawn, reference)
    }

    /** Vuelve a contar los puntos elegidos en la lista de objetos, que cambian fuera de este panel (RF-14). */
    fun refreshSelection() = mutable.update { it.copy(selectionCount = selection.ids.size) }

    fun updateSetup(change: (LongitudinalSetup) -> LongitudinalSetup) = mutable.update { it.copy(setup = change(it.setup), problem = null) }

    /**
     * Empieza el perfil con lo escrito: la tolerancia de eje (vacía: 0,50 m) y, si es guiado, el PK inicial, el final y el intervalo, que
     * deben caer en el eje (RF-10, RF-12). Devuelve la toma abierta, o null si algo no vale (el motivo queda en el estado).
     */
    fun begin(onOpened: (LinearSurvey) -> Unit = {}) {
        val current = mutable.value
        val project = projectId ?: return
        val axis = current.alignment ?: return
        if (current.survey != null) return
        val setup = current.setup
        val unit = current.unit
        fun fail(problem: LongitudinalProblem) = mutable.update { it.copy(problem = problem) }
        fun number(text: String, field: LongitudinalField): Double? {
            val value = NumberInput.parse(text)
            if (value == null) fail(LongitudinalProblem.InvalidNumber(field))
            return value
        }
        val tolerance = if (setup.tolerance.isBlank()) {
            LinearSurvey.DEFAULT_AXIS_TOLERANCE_M
        } else {
            val value = number(setup.tolerance, LongitudinalField.TOLERANCE)?.let(unit::toMetres) ?: return
            if (!(value > 0.0)) return fail(LongitudinalProblem.NotPositive(LongitudinalField.TOLERANCE))
            value
        }
        var from: Double? = null
        var to: Double? = null
        var interval: Double? = null
        if (setup.guided) {
            from = number(setup.from, LongitudinalField.FROM)?.let(unit::toMetres) ?: return
            to = number(setup.to, LongitudinalField.TO)?.let(unit::toMetres) ?: return
            interval = number(setup.interval, LongitudinalField.INTERVAL)?.let(unit::toMetres) ?: return
            if (!(interval > 0.0)) return fail(LongitudinalProblem.NotPositive(LongitudinalField.INTERVAL))
            if (from < axis.startChainage || from > axis.endChainage) return fail(LongitudinalProblem.OutsideAxis(LongitudinalField.FROM))
            if (to < axis.startChainage || to > axis.endChainage) return fail(LongitudinalProblem.OutsideAxis(LongitudinalField.TO))
        }
        val survey = LinearSurvey(
            UUID.randomUUID().toString(), project, LinearSurveyKind.LONGITUDINAL, axis.id, axis.name, from, to, interval, null,
            setup.guided, tolerance, System.currentTimeMillis(),
        )
        viewModelScope.launch(io) {
            surveys.open(survey)
            onOpened(survey)
        }
    }

    /** Añade al perfil un punto recién guardado por el recolector (RF-11). */
    private suspend fun add(saved: SavedPoint) {
        val survey = mutable.value.survey ?: return
        surveys.addPoint(survey.id, sample(saved.id, saved.coordinates, survey))
    }

    /** Lo que se guarda de un punto en el perfil: PK, desplazamiento, diferencia de cota y marca (RF-11, RF-12). */
    private fun sample(pointId: String, c: PointCoordinates, survey: LinearSurvey): SurveyPoint {
        val geo = geometry
        val easting = c.easting
        val northing = c.northing
        if (geo == null ||
            easting == null ||
            northing == null
        ) {
            return SurveyPoint(pointId, 0, true, null, null, null, SurveyFlag.NO_PROJECTION)
        }
        val sampled = ProfileSampler.longitudinal(
            geo,
            grade,
            easting,
            northing,
            c.height,
            survey.toleranceM ?: LinearSurvey.DEFAULT_AXIS_TOLERANCE_M,
        )
        return SurveyPoint(pointId, 0, true, sampled.chainageM, sampled.offsetM, sampled.dHeightM, sampled.flag)
    }

    /** Construye el perfil con los puntos elegidos en la lista de objetos (RF-14). */
    fun addSelected(onOpened: (LinearSurvey) -> Unit = {}) = addFromSelection(selection.ids.toList(), onOpened)

    /**
     * Construye el perfil con unos puntos del proyecto ya medidos, proyectados sobre el eje (RF-14): abre un perfil libre con ellos si no hay
     * uno abierto, o los añade al abierto. Los que ya están en el perfil se saltan.
     */
    fun addFromSelection(pointIds: List<String>, onOpened: (LinearSurvey) -> Unit = {}) {
        val project = projectId ?: return
        val axis = mutable.value.alignment ?: return
        viewModelScope.launch(io) {
            val byId = points.points(project).first().associateBy { it.id }
            val chosen = pointIds.mapNotNull { byId[it] }
            if (chosen.isEmpty()) {
                mutable.update { it.copy(problem = LongitudinalProblem.NoPoints) }
                return@launch
            }
            val survey = mutable.value.survey ?: LinearSurvey(
                UUID.randomUUID().toString(),
                project,
                LinearSurveyKind.LONGITUDINAL,
                axis.id,
                axis.name,
                guided = false,
                toleranceM = mutable.value.setup.tolerance.takeIf { it.isNotBlank() }?.let { NumberInput.parse(it) }
                    ?.let(mutable.value.unit::toMetres)?.takeIf { it > 0.0 },
                createdAtMs = System.currentTimeMillis(),
            ).also { surveys.open(it) }
            chosen.forEach { point -> surveys.addPoint(survey.id, sample(point.id, point.coordinates, survey)) }
            log.info(LogCategory.APP, "Perfil longitudinal con ${chosen.size} puntos elegidos")
            onOpened(survey)
        }
    }

    /** Quita un punto del perfil sin borrarlo del proyecto; volver a medirlo crea otro punto (RF-21). */
    fun removePoint(pointId: String) {
        val survey = mutable.value.survey ?: return
        viewModelScope.launch(io) { surveys.setIncluded(survey.id, pointId, false) }
    }

    /** Termina el perfil: queda en la lista de perfiles tomados con sus puntos (RF-22). */
    fun finish() {
        val survey = mutable.value.survey ?: return
        viewModelScope.launch(io) {
            if (!surveys.finishLongitudinal(survey.id)) mutable.update { it.copy(problem = LongitudinalProblem.WriteError) }
        }
    }

    /** Descarta el perfil abierto sin borrar ningún punto. */
    fun discard() {
        val survey = mutable.value.survey ?: return
        viewModelScope.launch(io) { surveys.delete(survey.id) }
    }

    fun dismissProblem() = mutable.update { it.copy(problem = null) }

    /** Las filas del CSV del perfil: los puntos que entran, por PK creciente, con la cota de la rasante y la diferencia (RF-15). */
    fun csvRows(): List<ProfileCsvRow> = mutable.value.inProfile.filter { it.chainageM != null && it.offsetM != null }
        .sortedBy { it.chainageM }
        .map { ProfileCsvRow(null, it.chainageM!!, it.offsetM!!, it.groundM, it.referenceM, it.dHeightM) }

    /**
     * Escribe el CSV del perfil en [out], en UTF-8 sin BOM y con el separador y el signo decimal de la exportación (RF-15); false si no hay
     * puntos en el perfil, las opciones no se distinguen o no se pudo escribir.
     */
    suspend fun writeCsv(out: OutputStream): Boolean {
        val project = project ?: return false
        val rows = csvRows()
        val options = exports.options.first()
        if (rows.isEmpty() || !options.valid) return false
        return withContext(Dispatchers.IO) {
            try {
                BufferedWriter(OutputStreamWriter(out, Charsets.UTF_8)).use { writer ->
                    ProfileCsv.write(LinearSurveyKind.LONGITUDINAL, rows, project, options.separator, options.decimal, writer)
                }
                log.info(LogCategory.APP, "Perfil longitudinal exportado: ${rows.size} puntos")
                true
            } catch (_: IOException) {
                false
            } catch (_: SecurityException) {
                false
            }
        }
    }

    private companion object {
        /** Los puntos con que se dibuja la rasante entre el primero y el último punto del perfil. */
        const val REFERENCE_SAMPLES = 200
    }
}
