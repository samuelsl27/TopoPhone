package es.topophone.data.slope

import androidx.room.withTransaction
import es.topophone.core.model.alignment.section.HeightKind
import es.topophone.core.model.alignment.slope.ManualEdge
import es.topophone.core.model.alignment.slope.SlopeIssue
import es.topophone.core.model.alignment.slope.SlopeRule
import es.topophone.core.model.alignment.slope.SlopeSide
import es.topophone.core.model.log.AppLog
import es.topophone.core.model.log.LogCategory
import es.topophone.core.survey.SlopeValidator
import es.topophone.data.db.TopoPhoneDatabase
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Un talud guardado con su identificador (para borrarlo) y su fecha (S051/RF-4). */
data class StoredSlope(val id: String, val rule: SlopeRule, val createdAtMs: Long)

/** Cómo acabó guardar un talud (S051/RF-3, RF-4). */
sealed interface SlopeSaveResult {
    data class Saved(val id: String) : SlopeSaveResult

    /** No se guardó: el motivo. */
    data class Rejected(val issue: SlopeIssue) : SlopeSaveResult

    data object AxisNotFound : SlopeSaveResult
}

/**
 * Los taludes de los ejes (S051). Cada alta y cada baja es una transacción única con durabilidad completa (RNF-T6); se valida antes de
 * guardar y se guarda sin redondear. Se borran con el eje y no tocan ningún punto (RF-4); el log lleva el hecho, nunca coordenadas.
 */
interface SlopeRepository {
    fun rules(alignmentId: String): Flow<List<StoredSlope>>

    suspend fun add(alignmentId: String, rule: SlopeRule): SlopeSaveResult

    suspend fun delete(id: String)
}

class RoomSlopeRepository(
    private val database: TopoPhoneDatabase,
    private val log: AppLog = AppLog.NONE,
    private val clock: () -> Long = System::currentTimeMillis,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) : SlopeRepository {
    private val dao = database.slopes()
    private val alignmentDao = database.alignments()

    override fun rules(alignmentId: String): Flow<List<StoredSlope>> = dao.rules(alignmentId).map { rows ->
        rows.map { StoredSlope(it.id, it.toModel(), it.createdAtMs) }
    }

    override suspend fun add(alignmentId: String, rule: SlopeRule): SlopeSaveResult = database.withTransaction {
        val axis = alignmentDao.byId(alignmentId) ?: return@withTransaction SlopeSaveResult.AxisNotFound
        val others = dao.rulesNow(alignmentId).map { it.toModel() }
        SlopeValidator.validate(rule, axis.startChainage, axis.startChainage + axis.lengthM, others)?.let {
            return@withTransaction SlopeSaveResult.Rejected(it)
        }
        val id = newId()
        dao.insert(
            SlopeRuleEntity(
                id,
                alignmentId,
                rule.side.name,
                rule.fromChainage,
                rule.toChainage,
                rule.cutRatio,
                rule.fillRatio,
                rule.edgeCode,
                rule.manualEdge?.offsetM,
                rule.manualEdge?.heightM,
                rule.manualEdge?.heightKind?.name,
                clock(),
            ),
        )
        log.info(LogCategory.APP, "Guardado 1 talud")
        SlopeSaveResult.Saved(id)
    }

    override suspend fun delete(id: String) {
        dao.delete(id)
        log.info(LogCategory.APP, "Borrado 1 talud")
    }

    private fun SlopeRuleEntity.toModel() = SlopeRule(
        SlopeSide.valueOf(side),
        fromChainage,
        toChainage,
        cutRatio,
        fillRatio,
        edgeCode,
        if (manualOffset != null && manualHeight != null && manualHeightKind != null) {
            ManualEdge(manualOffset, manualHeight, HeightKind.valueOf(manualHeightKind))
        } else {
            null
        },
    )
}
