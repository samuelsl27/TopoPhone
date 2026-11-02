package es.topophone.data.linear

import androidx.room.withTransaction
import es.topophone.core.model.alignment.section.CrossSection
import es.topophone.core.model.log.AppLog
import es.topophone.core.model.log.LogCategory
import es.topophone.core.model.survey.linear.LinearSurvey
import es.topophone.core.model.survey.linear.LinearSurveyKind
import es.topophone.core.model.survey.linear.SurveyFlag
import es.topophone.core.model.survey.linear.SurveyPoint
import es.topophone.data.alignment.AlignmentRepository
import es.topophone.data.alignment.AlignmentToSave
import es.topophone.data.db.TopoPhoneDatabase
import es.topophone.data.section.SectionRepository
import es.topophone.data.section.SectionSaveResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Cómo acabó añadir un punto a una toma (S052/RF-2). */
enum class SurveyAddResult { ADDED, ALREADY_IN, CLOSED, NOT_FOUND }

/** Cómo acabó terminar una toma de eje (S052/RF-5, RF-8): el eje creado y, si el proyecto no tenía eje activo, el que quedó activo. */
sealed interface FinishAxisResult {
    data class Finished(val alignmentId: String, val activatedId: String?) : FinishAxisResult

    /** La toma no existe, no es de eje o ya está terminada. */
    data object NotOpen : FinishAxisResult
}

/** Cómo acabó guardar un perfil transversal (S052/RF-20). */
sealed interface FinishCrossResult {
    data object Finished : FinishCrossResult

    /** La toma no existe, no es transversal, no tiene eje o ya está terminada. */
    data object NotOpen : FinishCrossResult

    /** S050 rechazó el perfil (PK fuera del eje, códigos o desplazamientos mal): no se guardó nada. */
    data class Rejected(val result: SectionSaveResult) : FinishCrossResult
}

/**
 * Las tomas lineales de un proyecto (S052). Abrir una toma, añadir un punto, excluirlo, terminarla y borrarla son transacciones únicas con
 * durabilidad completa (RNF-T6), así que lo medido sobrevive a un cierre de la app y la toma se reanuda (RF-9). Los números van sin
 * redondear. Borrar una toma o su eje no borra ningún punto (RF-8); el log lleva el hecho, nunca nombres ni coordenadas (RF-23).
 */
interface LinearSurveyRepository {
    /** Las tomas del proyecto por fecha: las terminadas son el historial de perfiles (RF-20, RF-22). */
    fun surveys(projectId: String): Flow<List<LinearSurvey>>

    /** Los puntos de la toma por orden de medida, sin los que están en la papelera. */
    fun points(surveyId: String): Flow<List<SurveyPoint>>

    suspend fun survey(id: String): LinearSurvey?

    suspend fun open(survey: LinearSurvey)

    suspend fun addPoint(surveyId: String, point: SurveyPoint): SurveyAddResult

    /** Incluye o excluye un punto de la toma sin tocar el punto (RF-4, RF-21). */
    suspend fun setIncluded(surveyId: String, pointId: String, included: Boolean): Boolean

    /** Termina una toma de eje con el eje ya construido. Si el proyecto no tenía eje activo, el nuevo queda activo (RF-8). */
    suspend fun finishAxis(surveyId: String, axis: AlignmentToSave): FinishAxisResult

    /** Termina un perfil longitudinal: solo lo cierra, sus puntos ya están guardados. */
    suspend fun finishLongitudinal(surveyId: String): Boolean

    /**
     * Termina un perfil transversal y guarda [section] como el perfil de terreno vigente del eje en ese PK (S050), todo en una
     * transacción. El perfil que había queda en el historial: es la toma terminada anterior (RF-20).
     */
    suspend fun finishCross(surveyId: String, section: CrossSection): FinishCrossResult

    suspend fun delete(surveyId: String)
}

class RoomLinearSurveyRepository(
    private val database: TopoPhoneDatabase,
    private val alignments: AlignmentRepository,
    private val sections: SectionRepository,
    private val log: AppLog = AppLog.NONE,
    private val clock: () -> Long = System::currentTimeMillis,
) : LinearSurveyRepository {
    private val dao = database.linearSurveys()

    override fun surveys(projectId: String): Flow<List<LinearSurvey>> = dao.surveys(projectId).map { rows -> rows.map { it.toModel() } }

    override fun points(surveyId: String): Flow<List<SurveyPoint>> = dao.points(surveyId).map { rows -> rows.map { it.toModel() } }

    override suspend fun survey(id: String): LinearSurvey? = dao.byId(id)?.toModel()

    override suspend fun open(survey: LinearSurvey) {
        dao.insertSurvey(survey.toEntity())
        log.info(LogCategory.APP, "Abierta 1 toma")
    }

    override suspend fun addPoint(surveyId: String, point: SurveyPoint): SurveyAddResult = database.withTransaction {
        val survey = dao.byId(surveyId) ?: return@withTransaction SurveyAddResult.NOT_FOUND
        if (survey.finishedAtMs != null) return@withTransaction SurveyAddResult.CLOSED
        if (dao.contains(surveyId, point.pointId) > 0) return@withTransaction SurveyAddResult.ALREADY_IN
        // El orden lo da la toma, no quien llama: así dos puntos que llegan casi a la vez no repiten número.
        dao.insertPoint(point.copy(ordinal = dao.maxOrdinal(surveyId) + 1).toEntity(surveyId))
        SurveyAddResult.ADDED
    }

    override suspend fun setIncluded(surveyId: String, pointId: String, included: Boolean): Boolean {
        val changed = dao.setIncluded(surveyId, pointId, included) > 0
        if (changed) log.info(LogCategory.APP, if (included) "Incluido 1 punto en una toma" else "Quitado 1 punto de una toma")
        return changed
    }

    override suspend fun finishAxis(surveyId: String, axis: AlignmentToSave): FinishAxisResult = database.withTransaction {
        val survey = dao.byId(surveyId)
        if (survey == null || survey.kind != LinearSurveyKind.AXIS.name || survey.finishedAtMs != null) {
            return@withTransaction FinishAxisResult.NotOpen
        }
        val now = clock()
        val added = alignments.add(survey.projectId, listOf(axis), now)
        dao.finish(surveyId, added.ids.single(), now)
        log.info(LogCategory.APP, "Terminada 1 toma de eje")
        FinishAxisResult.Finished(added.ids.single(), added.activatedId)
    }

    override suspend fun finishLongitudinal(surveyId: String): Boolean = database.withTransaction {
        val survey = dao.byId(surveyId)
        if (survey == null || survey.kind != LinearSurveyKind.LONGITUDINAL.name || survey.finishedAtMs != null) {
            return@withTransaction false
        }
        dao.finish(surveyId, survey.alignmentId, clock())
        log.info(LogCategory.APP, "Terminado 1 perfil longitudinal")
        true
    }

    override suspend fun finishCross(surveyId: String, section: CrossSection): FinishCrossResult = database.withTransaction {
        val survey = dao.byId(surveyId)
        val axisId = survey?.alignmentId
        if (survey == null || axisId == null || survey.kind != LinearSurveyKind.CROSS.name || survey.finishedAtMs != null) {
            return@withTransaction FinishCrossResult.NotOpen
        }
        val saved = sections.addSections(axisId, listOf(section))
        if (saved !is SectionSaveResult.Saved) return@withTransaction FinishCrossResult.Rejected(saved)
        dao.finish(surveyId, axisId, clock())
        log.info(LogCategory.APP, "Guardado 1 perfil transversal")
        FinishCrossResult.Finished
    }

    override suspend fun delete(surveyId: String) {
        dao.delete(surveyId)
        log.info(LogCategory.APP, "Borrada 1 toma")
    }

    private fun LinearSurveyEntity.toModel() = LinearSurvey(
        id, projectId, LinearSurveyKind.valueOf(kind), alignmentId, name, chainageFrom, chainageTo, intervalM, chainage, guided, toleranceM,
        createdAtMs, finishedAtMs,
    )

    private fun LinearSurvey.toEntity() = LinearSurveyEntity(
        id, projectId, kind.name, alignmentId, name, chainageFrom, chainageTo, intervalM, chainage, guided, toleranceM, createdAtMs,
        finishedAtMs,
    )

    private fun LinearSurveyPointEntity.toModel() =
        SurveyPoint(pointId, ordinal, included, chainage, offsetM, dHeight, SurveyFlag.valueOf(flag), code)

    private fun SurveyPoint.toEntity(surveyId: String) =
        LinearSurveyPointEntity(surveyId, pointId, ordinal, included, chainageM, offsetM, dHeightM, flag.name, code)
}
