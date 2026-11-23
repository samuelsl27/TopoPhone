package es.topophone.data.localization

import androidx.room.withTransaction
import es.topophone.core.model.localization.Localization
import es.topophone.core.model.localization.LocalizationLimits
import es.topophone.core.model.localization.LocalizationOrigin
import es.topophone.core.model.localization.LocalizationPair
import es.topophone.core.model.localization.LocalizationParams
import es.topophone.core.model.localization.LocalizationStatus
import es.topophone.core.model.localization.PairResidualValues
import es.topophone.core.model.project.ProjectCrsText
import es.topophone.data.db.TopoPhoneDatabase
import es.topophone.data.project.toProject
import java.util.UUID
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

/**
 * Las localizaciones de un proyecto (S039). Cada proyecto tiene como mucho un borrador, una localización activa y las archivadas (RF-16, RF-21). Todo
 * cambio de más de una fila va en una transacción (RNF-T6).
 */
interface LocalizationRepository {
    /** El borrador del proyecto con sus pares, o null si aún no hay (se crea al añadir el primer par). */
    fun draft(projectId: String): Flow<Localization?>

    /** La localización activa del proyecto con sus pares, o null. */
    fun active(projectId: String): Flow<Localization?>

    /** Las localizaciones quitadas o sustituidas, la más reciente primero (RF-21). */
    fun history(projectId: String): Flow<List<Localization>>

    /** Añade [pair] al final del borrador, que se crea si no existe; false si ya está el punto o hay 50 pares (RF-3, RF-4). */
    suspend fun addPair(projectId: String, pair: LocalizationPair): Boolean

    suspend fun removePair(projectId: String, pairId: String)

    /** Activa o excluye las componentes de un par sin borrarlo (RF-5). */
    suspend fun setComponents(projectId: String, pairId: String, useHorizontal: Boolean, useVertical: Boolean)

    /** El usuario confirma el uso de un par de baja calidad (RF-6). */
    suspend fun confirmPair(projectId: String, pairId: String)

    suspend fun setFixScale(projectId: String, fixScale: Boolean)

    suspend fun setLimits(projectId: String, residualLimitM: Double, scaleLimit: Double)

    /** Borra el borrador y sus pares. */
    suspend fun discardDraft(projectId: String)
}

@OptIn(ExperimentalCoroutinesApi::class)
class RoomLocalizationRepository(private val database: TopoPhoneDatabase) : LocalizationRepository {
    private val dao = database.localizations()

    override fun draft(projectId: String): Flow<Localization?> = withPairs(dao.withStatus(projectId, LocalizationStatus.DRAFT.name))

    override fun active(projectId: String): Flow<Localization?> = withPairs(dao.withStatus(projectId, LocalizationStatus.ACTIVE.name))

    override fun history(projectId: String): Flow<List<Localization>> =
        dao.archived(projectId).map { rows -> rows.map { it.toModel(dao.pairsNow(it.id)) } }

    private fun withPairs(source: Flow<LocalizationEntity?>): Flow<Localization?> = source.flatMapLatest { entity ->
        if (entity == null) flowOf(null) else dao.pairs(entity.id).map { entity.toModel(it) }
    }

    override suspend fun addPair(projectId: String, pair: LocalizationPair): Boolean = database.withTransaction {
        val draftId = ensureDraft(projectId) ?: return@withTransaction false
        val existing = dao.pairsNow(draftId)
        if (existing.size >= LocalizationLimits.MAX_PAIRS) return@withTransaction false
        if (pair.pointId != null && existing.any { it.pointId == pair.pointId }) return@withTransaction false
        dao.insertPairs(listOf(pair.toEntity(draftId, position = existing.size)))
        true
    }

    override suspend fun removePair(projectId: String, pairId: String) {
        database.withTransaction {
            val draft = dao.withStatusNow(projectId, LocalizationStatus.DRAFT.name)
            if (draft != null) {
                dao.deletePair(pairId)
                // Las posiciones siguen seguidas para que la lista conserve su orden.
                dao.pairsNow(draft.id).forEachIndexed { i, row -> if (row.position != i) dao.updatePair(row.copy(position = i)) }
            }
        }
    }

    override suspend fun setComponents(projectId: String, pairId: String, useHorizontal: Boolean, useVertical: Boolean) {
        updatePair(projectId, pairId) { it.copy(useHorizontal = useHorizontal, useVertical = useVertical) }
    }

    override suspend fun confirmPair(projectId: String, pairId: String) {
        updatePair(projectId, pairId) { it.copy(confirmed = true) }
    }

    private suspend fun updatePair(projectId: String, pairId: String, change: (LocalizationPairEntity) -> LocalizationPairEntity) {
        database.withTransaction {
            val draft = dao.withStatusNow(projectId, LocalizationStatus.DRAFT.name)
            val row = draft?.let { dao.pairsNow(it.id) }?.firstOrNull { it.id == pairId }
            if (row != null) dao.updatePair(change(row))
        }
    }

    override suspend fun setFixScale(projectId: String, fixScale: Boolean) {
        database.withTransaction {
            val id = ensureDraft(projectId)
            val draft = id?.let { dao.byId(it) }
            if (draft != null) dao.update(draft.copy(fixScale = fixScale))
        }
    }

    override suspend fun setLimits(projectId: String, residualLimitM: Double, scaleLimit: Double) {
        database.withTransaction {
            val id = ensureDraft(projectId)
            val draft = id?.let { dao.byId(it) }
            if (draft != null) dao.update(draft.copy(residualLimitM = residualLimitM, scaleLimit = scaleLimit))
        }
    }

    override suspend fun discardDraft(projectId: String) {
        database.withTransaction {
            val draft = dao.withStatusNow(projectId, LocalizationStatus.DRAFT.name)
            if (draft != null) dao.delete(draft.id)
        }
    }

    /** El identificador del borrador del proyecto, creado con el SC y las alturas del proyecto si no existe; null si el proyecto no existe. */
    private suspend fun ensureDraft(projectId: String): String? {
        dao.withStatusNow(projectId, LocalizationStatus.DRAFT.name)?.let { return it.id }
        val project = database.projects().byId(projectId)?.toProject() ?: return null
        val id = UUID.randomUUID().toString()
        dao.insert(
            LocalizationEntity(
                id = id,
                projectId = projectId,
                status = LocalizationStatus.DRAFT.name,
                origin = LocalizationOrigin.PAIRS.name,
                name = null,
                createdAtMs = System.currentTimeMillis(),
                appliedAtMs = null,
                fixScale = false,
                residualLimitM = LocalizationLimits.DEFAULT_RESIDUAL_LIMIT_M,
                scaleLimit = LocalizationLimits.DEFAULT_SCALE_LIMIT,
                crsText = ProjectCrsText.encode(project.crs),
                heightSystem = project.heightSystem.name,
                params = null,
                rmsH = null,
                rmsV = null,
            ),
        )
        return id
    }
}

internal fun LocalizationPair.toEntity(localizationId: String, position: Int, residual: PairResidualValues? = null) =
    LocalizationPairEntity(
        id = id,
        localizationId = localizationId,
        position = position,
        pointId = pointId,
        pointName = pointName,
        scE = scE,
        scN = scN,
        scH = scH,
        knownE = knownE,
        knownN = knownN,
        knownH = knownH,
        useHorizontal = useHorizontal,
        useVertical = useVertical,
        confirmed = confirmed,
        resE = residual?.east,
        resN = residual?.north,
        resV = residual?.vertical,
    )

internal fun LocalizationPairEntity.toPair() = LocalizationPair(
    id = id,
    pointId = pointId,
    pointName = pointName,
    scE = scE,
    scN = scN,
    scH = scH,
    knownE = knownE,
    knownN = knownN,
    knownH = knownH,
    useHorizontal = useHorizontal,
    useVertical = useVertical,
    confirmed = confirmed,
)

internal fun LocalizationEntity.toModel(pairs: List<LocalizationPairEntity>) = Localization(
    id = id,
    projectId = projectId,
    status = LocalizationStatus.entries.firstOrNull { it.name == status } ?: LocalizationStatus.ARCHIVED,
    origin = LocalizationOrigin.entries.firstOrNull { it.name == origin } ?: LocalizationOrigin.PAIRS,
    name = name,
    createdAtMs = createdAtMs,
    appliedAtMs = appliedAtMs,
    fixScale = fixScale,
    residualLimitM = residualLimitM,
    scaleLimit = scaleLimit,
    crsText = crsText,
    heightSystem = heightSystem,
    params = LocalizationParams.parseOrNull(params),
    rmsHorizontal = rmsH,
    rmsVertical = rmsV,
    pairs = pairs.map { it.toPair() },
    residuals = pairs.filter { it.resE != null || it.resN != null || it.resV != null }
        .associate { it.id to PairResidualValues(it.resE, it.resN, it.resV) },
)
