package es.topophone.app.ui.linear

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import es.topophone.app.di.IoDispatcher
import es.topophone.app.ui.collector.PointSavedListener
import es.topophone.app.ui.collector.SavedPoint
import es.topophone.core.formats.csv.ProfileCsv
import es.topophone.core.formats.csv.ProfileCsvRow
import es.topophone.core.model.alignment.Alignment
import es.topophone.core.model.alignment.ChainageInput
import es.topophone.core.model.alignment.section.CrossSection
import es.topophone.core.model.alignment.section.SectionIssue
import es.topophone.core.model.alignment.section.SectionKind
import es.topophone.core.model.alignment.section.Superelevation
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
import es.topophone.core.survey.CrossInput
import es.topophone.core.survey.CrossProfileBuilder
import es.topophone.core.survey.CrossProfileResult
import es.topophone.core.survey.ProfileSampler
import es.topophone.core.survey.SectionEval
import es.topophone.core.survey.SectionPointResult
import es.topophone.core.survey.SectionResolution
import es.topophone.core.survey.SectionResolver
import es.topophone.core.survey.VerticalProfileEval
import es.topophone.data.alignment.AlignmentRepository
import es.topophone.data.export.ExportPreferences
import es.topophone.data.linear.FinishCrossResult
import es.topophone.data.linear.LinearSurveyRepository
import es.topophone.data.point.PointRepository
import es.topophone.data.project.ProjectRepository
import es.topophone.data.section.SectionRepository
import es.topophone.data.section.SectionSaveResult
import java.io.BufferedWriter
import java.io.IOException
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.util.UUID
import javax.inject.Inject
import kotlin.math.abs
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

/** Un punto del perfil transversal con lo calculado al medirlo (S052/RF-17, RF-18): su PK, su desplazamiento, la cota de diseño y la marca. */
data class CrossRow(
    val pointId: String,
    val name: String,
    val chainageM: Double?,
    val offsetM: Double?,
    val groundM: Double?,
    val designM: Double?,
    val dHeightM: Double?,
    val flag: SurveyFlag,
    val included: Boolean,
    val code: String?,
) {
    val inProfile: Boolean get() = included && flag == SurveyFlag.NONE
}

/** El PK de la sección y la tolerancia de sección tal como se escriben, y el código para el siguiente punto (S052/RF-16, RF-17, RF-18). */
data class CrossSetup(val chainage: String = "", val tolerance: String = "", val nextCode: String = "")

/** El campo de la preparación que no vale. */
enum class CrossField { CHAINAGE, TOLERANCE }

sealed interface CrossProblem {
    data class InvalidNumber(val field: CrossField) : CrossProblem

    /** El PK queda fuera del eje. */
    data object OutsideAxis : CrossProblem

    data object NotPositive : CrossProblem

    /** No hay puntos en el perfil para guardar. */
    data object NoPoints : CrossProblem

    /** Dos puntos tienen el mismo desplazamiento (el valor, en metros). */
    data class SameOffset(val offsetM: Double) : CrossProblem

    /** Un punto del perfil no tiene cota. */
    data class MissingHeight(val offsetM: Double) : CrossProblem

    /** S050 no acepta el perfil. */
    data class Rejected(val issue: SectionIssue) : CrossProblem

    data object WriteError : CrossProblem
}

/** El perfil que se acaba de guardar: cuántos puntos tiene y si sustituyó a otro del mismo PK, que queda en el historial (RF-20). */
data class CrossSaved(val points: Int, val replacedPrevious: Boolean)

/** Lo que muestra el panel del perfil transversal (S052/RF-16 a RF-21). */
data class CrossUiState(
    val loading: Boolean = true,
    val unit: LinearUnit = LinearUnit.METRE,
    val alignment: Alignment? = null,
    val survey: LinearSurvey? = null,
    val rows: List<CrossRow> = emptyList(),
    val chart: ProfileChart = ProfileChart(ProfileAxis.OFFSET, emptyList(), emptyList()),
    /** Hay sección de diseño en el PK: sin ella el gráfico solo lleva el terreno (RF-19). */
    val hasDesign: Boolean = false,
    val setup: CrossSetup = CrossSetup(),
    val problem: CrossProblem? = null,
    val saved: CrossSaved? = null,
) {
    val inProfile: List<CrossRow> get() = rows.filter { it.inProfile }
    val rejected: List<CrossRow> get() = rows.filter { it.included && it.flag != SurveyFlag.NONE }
}

/**
 * El perfil transversal del terreno en un PK de un eje (S052/RF-16 a RF-21). Los puntos los mide el recolector con la guía del
 * replanteo por PK hacia ese PK (RF-16): de cada uno se guarda su PK y desplazamiento reales, el código de sección, la diferencia de cota con
 * la sección de diseño y la marca si el error en PK pasa de la tolerancia de sección (RF-17, RF-18). «Guardar perfil» lo convierte en el perfil
 * de terreno de S050 en ese PK y cierra la toma; el anterior queda en el historial (RF-20). Quitar un punto no lo borra del proyecto (RF-21).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class CrossViewModel @Inject constructor(
    private val surveys: LinearSurveyRepository,
    private val alignments: AlignmentRepository,
    private val sections: SectionRepository,
    private val points: PointRepository,
    private val projects: ProjectRepository,
    private val exports: ExportPreferences,
    @param:IoDispatcher private val io: CoroutineDispatcher,
    private val log: AppLog = AppLog.NONE,
) : ViewModel() {
    private val mutable = MutableStateFlow(CrossUiState())
    val state: StateFlow<CrossUiState> = mutable.asStateFlow()

    private var projectId: String? = null
    private var project: Project? = null
    private var geometry: AlignmentGeometry? = null
    private var grade: VerticalProfileEval? = null
    private var resolver: SectionResolver? = null

    /** Quien recibe cada punto que guarda el recolector y lo añade al perfil abierto (RF-17). */
    val pointListener = PointSavedListener { saved -> add(saved) }

    /** Prepara el perfil transversal del eje [alignment]: si hay uno abierto lo recupera (RF-9). Solo la primera vez. */
    fun start(project: String, alignment: String) {
        if (projectId != null) return
        projectId = project
        viewModelScope.launch(io) {
            this@CrossViewModel.project = projects.project(project)
            val axis = alignments.alignment(alignment)
            geometry = axis?.let { AlignmentGeometry(it.elements) }
            grade = axis?.profile?.let { VerticalProfileEval(it) }
            val templates = sections.templates(alignment).first().map { it.template }
            val stored = sections.sections(alignment).first().map { it.section }
            val rows = sections.superelevation(alignment).first()
            resolver = SectionResolver(templates, rows.takeIf { it.isNotEmpty() }?.let(::Superelevation), stored)
            mutable.update { it.copy(unit = this@CrossViewModel.project?.linearUnit ?: LinearUnit.METRE, alignment = axis) }
            surveys.surveys(project).map { list ->
                list.firstOrNull { it.kind == LinearSurveyKind.CROSS && it.alignmentId == alignment && it.isOpen }
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
            val design = if (ground != null && member.dHeightM != null) ground - member.dHeightM!! else null
            CrossRow(
                point.id,
                point.name,
                member.chainageM,
                member.offsetM,
                ground,
                design,
                member.dHeightM,
                member.flag,
                member.included,
                member.code,
            )
        }
        val section = survey?.chainage?.let { designAt(it) }
        mutable.update {
            it.copy(
                loading = false,
                survey = survey,
                rows = rows,
                chart = chartOf(rows, section, survey?.chainage),
                hasDesign = section != null,
            )
        }
    }

    /** La sección de diseño del PK, si hay una (RF-19). */
    private fun designAt(chainage: Double): CrossSection? = (resolver?.at(chainage) as? SectionResolution.Found)?.section

    /** El gráfico: los puntos por desplazamiento y la sección de diseño del PK como referencia (RF-19). */
    private fun chartOf(rows: List<CrossRow>, design: CrossSection?, chainage: Double?): ProfileChart {
        val drawn = rows.filter { it.included && it.offsetM != null && it.groundM != null }.sortedBy { it.offsetM }.map { r ->
            ProfileChartPoint(r.name, r.offsetM!!, r.groundM!!, r.designM, r.dHeightM, r.inProfile, r.flag)
        }
        val geo = geometry
        val reference = if (design == null || geo == null || chainage == null) {
            emptyList()
        } else {
            design.points.mapNotNull { p ->
                (SectionEval.byCode(design, geo, grade, p.code) as? SectionPointResult.Value)?.elevationM?.let { p.offsetM to it }
            }.sortedBy { it.first }
        }
        return ProfileChart(ProfileAxis.OFFSET, drawn, reference)
    }

    fun updateSetup(change: (CrossSetup) -> CrossSetup) = mutable.update { it.copy(setup = change(it.setup), problem = null, saved = null) }

    /**
     * Empieza el perfil del PK escrito, que debe caer en el eje, con la tolerancia de sección escrita (vacía: 0,10 m) (RF-16, RF-18).
     * [onOpened] recibe la toma abierta para llevar la guía de replanteo hasta ese PK.
     */
    fun begin(onOpened: (LinearSurvey) -> Unit = {}) {
        val current = mutable.value
        val project = projectId ?: return
        val axis = current.alignment ?: return
        if (current.survey != null) return
        val unit = current.unit
        fun fail(problem: CrossProblem) = mutable.update { it.copy(problem = problem) }
        val chainage = ChainageInput.parse(current.setup.chainage, unit) ?: return fail(CrossProblem.InvalidNumber(CrossField.CHAINAGE))
        if (chainage < axis.startChainage || chainage > axis.endChainage) return fail(CrossProblem.OutsideAxis)
        val tolerance = if (current.setup.tolerance.isBlank()) {
            LinearSurvey.DEFAULT_SECTION_TOLERANCE_M
        } else {
            val value =
                NumberInput.parse(current.setup.tolerance)?.let(unit::toMetres)
                    ?: return fail(CrossProblem.InvalidNumber(CrossField.TOLERANCE))
            if (!(value > 0.0)) return fail(CrossProblem.NotPositive)
            value
        }
        val survey = LinearSurvey(
            UUID.randomUUID().toString(), project, LinearSurveyKind.CROSS, axis.id, axis.name, chainage = chainage, guided = true,
            toleranceM = tolerance, createdAtMs = System.currentTimeMillis(),
        )
        viewModelScope.launch(io) {
            surveys.open(survey)
            onOpened(survey)
        }
    }

    /** Añade al perfil un punto recién guardado por el recolector, con el código escrito o el de la sección de diseño (RF-17). */
    private suspend fun add(saved: SavedPoint) {
        val survey = mutable.value.survey ?: return
        val chainage = survey.chainage ?: return
        val geo = geometry
        val c: PointCoordinates = saved.coordinates
        val easting = c.easting
        val northing = c.northing
        val member = if (geo == null || easting == null || northing == null) {
            SurveyPoint(saved.id, 0, true, null, null, null, SurveyFlag.NO_PROJECTION)
        } else {
            val design = designAt(chainage)
            val sample = ProfileSampler.cross(
                geo,
                grade,
                design,
                chainage,
                easting,
                northing,
                c.height,
                survey.toleranceM ?: LinearSurvey.DEFAULT_SECTION_TOLERANCE_M,
            )
            val typed = mutable.value.setup.nextCode.trim().takeIf { it.isNotEmpty() }
            SurveyPoint(
                saved.id,
                0,
                true,
                sample.chainageM,
                sample.offsetM,
                sample.dHeightM,
                sample.flag,
                typed ?: sample.offsetM?.let { designCodeAt(design, it) },
            )
        }
        surveys.addPoint(survey.id, member)
        if (mutable.value.setup.nextCode.isNotEmpty()) mutable.update { it.copy(setup = it.setup.copy(nextCode = "")) }
    }

    /** El código del punto de la sección de diseño a menos de [CODE_SNAP_M] de este desplazamiento, si lo hay (RF-17). */
    private fun designCodeAt(design: CrossSection?, offsetM: Double): String? =
        design?.points?.minByOrNull { abs(it.offsetM - offsetM) }?.takeIf { abs(it.offsetM - offsetM) <= CODE_SNAP_M }?.code

    /** Quita un punto del perfil sin borrarlo del proyecto; volver a medirlo crea otro punto (RF-21). */
    fun removePoint(pointId: String) {
        val survey = mutable.value.survey ?: return
        viewModelScope.launch(io) { surveys.setIncluded(survey.id, pointId, false) }
    }

    /**
     * Guarda el perfil: los puntos que entran, ordenados por desplazamiento, pasan a ser el perfil de terreno de S050 en el PK y la toma
     * se cierra; el que había en ese PK queda en el historial (RF-20). Si no vale, dice por qué y no guarda nada.
     */
    fun save() {
        val survey = mutable.value.survey ?: return
        val chainage = survey.chainage ?: return
        val inputs = mutable.value.inProfile.filter { it.offsetM != null }.map { CrossInput(it.offsetM!!, it.groundM, it.code) }
        val built = when (val result = CrossProfileBuilder.build(chainage, inputs)) {
            is CrossProfileResult.Built -> result.section
            CrossProfileResult.NoPoints -> return mutable.update { it.copy(problem = CrossProblem.NoPoints) }
            is CrossProfileResult.MissingHeight -> return mutable.update { it.copy(problem = CrossProblem.MissingHeight(result.offsetM)) }
            is CrossProfileResult.SameOffset -> return mutable.update { it.copy(problem = CrossProblem.SameOffset(result.offsetM)) }
        }
        viewModelScope.launch(io) {
            val axisId = survey.alignmentId
            val replaced =
                axisId != null &&
                    sections.sections(axisId).first().any { it.section.kind == SectionKind.GROUND && it.section.chainage == chainage }
            when (val done = surveys.finishCross(survey.id, built)) {
                FinishCrossResult.Finished -> {
                    log.info(LogCategory.APP, "Perfil transversal guardado con ${built.points.size} puntos")
                    mutable.update { it.copy(problem = null, saved = CrossSaved(built.points.size, replaced)) }
                }
                FinishCrossResult.NotOpen -> mutable.update { it.copy(problem = CrossProblem.WriteError) }
                is FinishCrossResult.Rejected -> mutable.update {
                    val rejected = done.result as? SectionSaveResult.Rejected
                    it.copy(problem = rejected?.let { r -> CrossProblem.Rejected(r.issue) } ?: CrossProblem.WriteError)
                }
            }
        }
    }

    /** Descarta el perfil abierto sin borrar ningún punto. */
    fun discard() {
        val survey = mutable.value.survey ?: return
        viewModelScope.launch(io) { surveys.delete(survey.id) }
    }

    fun dismissSaved() = mutable.update { it.copy(saved = null) }

    fun dismissProblem() = mutable.update { it.copy(problem = null) }

    /** Las filas del CSV: los puntos del perfil por desplazamiento, con su código, PK real, cota del terreno y de diseño y diferencia (RF-22). */
    fun csvRows(): List<ProfileCsvRow> {
        val rows = mutable.value.inProfile.filter { it.chainageM != null && it.offsetM != null }.sortedBy { it.offsetM }
        val chainage = mutable.value.survey?.chainage
        val codes = if (chainage == null) {
            null
        } else {
            (CrossProfileBuilder.build(chainage, rows.map { CrossInput(it.offsetM!!, it.groundM, it.code) }) as? CrossProfileResult.Built)
                ?.section?.points?.map { it.code }
        }
        return rows.mapIndexed { i, r ->
            ProfileCsvRow(codes?.getOrNull(i) ?: r.code, r.chainageM!!, r.offsetM!!, r.groundM, r.designM, r.dHeightM)
        }
    }

    /** Escribe el CSV del perfil en [out], en UTF-8 sin BOM, con el separador y el signo decimal de la exportación (RF-22). */
    suspend fun writeCsv(out: OutputStream): Boolean {
        val project = project ?: return false
        val rows = csvRows()
        val options = exports.options.first()
        if (rows.isEmpty() || !options.valid) return false
        return withContext(Dispatchers.IO) {
            try {
                BufferedWriter(OutputStreamWriter(out, Charsets.UTF_8)).use { writer ->
                    ProfileCsv.write(LinearSurveyKind.CROSS, rows, project, options.separator, options.decimal, writer)
                }
                log.info(LogCategory.APP, "Perfil transversal exportado: ${rows.size} puntos")
                true
            } catch (_: IOException) {
                false
            } catch (_: SecurityException) {
                false
            }
        }
    }

    private companion object {
        /** A menos de esto de un punto de la sección de diseño, el punto medido lleva su código (RF-17). */
        const val CODE_SNAP_M = 0.10
    }
}
