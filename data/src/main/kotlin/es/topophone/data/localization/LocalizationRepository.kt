package es.topophone.data.localization

import androidx.room.withTransaction
import es.topophone.core.model.localization.ActiveLocalizationInfo
import es.topophone.core.model.localization.Localization
import es.topophone.core.model.localization.LocalizationLimits
import es.topophone.core.model.localization.LocalizationOrigin
import es.topophone.core.model.localization.LocalizationPair
import es.topophone.core.model.localization.LocalizationParams
import es.topophone.core.model.localization.LocalizationStatus
import es.topophone.core.model.localization.PairResidualValues
import es.topophone.core.model.log.AppLog
import es.topophone.core.model.log.LogCategory
import es.topophone.core.model.project.ProjectCrsText
import es.topophone.core.survey.localization.LocalizationApplier
import es.topophone.core.survey.localization.LocalizationFailure
import es.topophone.core.survey.localization.LocalizationOutcome
import es.topophone.core.survey.localization.LocalizationSolver
import es.topophone.data.db.TopoPhoneDatabase
import es.topophone.data.project.toProject
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/** Qué pasó al aplicar una localización (S039/RF-14, RF-22). */
sealed interface ApplyResult {
    /** La localización quedó activa y [pointsChanged] puntos GNSS cambiaron sus coordenadas mostradas. */
    data class Applied(val localizationId: String, val pointsChanged: Int) : ApplyResult

    /** Una localización archivada de pares pasó al borrador y falta aplicarla (RF-21). */
    data class DraftReady(val localizationId: String) : ApplyResult

    /** No hay borrador que aplicar. */
    data object NoDraft : ApplyResult

    /** El borrador no se puede calcular (RF-12). */
    data class CannotSolve(val reason: LocalizationFailure) : ApplyResult

    /** El proyecto está en coordenadas de terreno (RF-22). */
    data object TerrainActive : ApplyResult

    /** El SC o las alturas del proyecto ya no son los del borrador: sus pares no valen. */
    data object StaleCrs : ApplyResult

    /** La localización no existe o no se puede aplicar. */
    data object NotFound : ApplyResult
}

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

    /** Crea el borrador desde la localización activa (sus pares y ajustes) para editarla sin tocar la activa (RF-16); nada si ya hay borrador. */
    suspend fun startDraft(projectId: String)

    /** Cuántos puntos GNSS cambiarían sus coordenadas mostradas al aplicar o quitar una localización (RF-14). */
    suspend fun affectedPoints(projectId: String): Int

    /**
     * Aplica el borrador (RF-14, RF-16, RF-17): lo calcula, lo deja como la localización activa con la fecha y hora, pasa la activa anterior al
     * historial y reescribe las coordenadas de los puntos GNSS no editados, todo en una transacción (RNF-T6).
     */
    suspend fun apply(projectId: String, nowMs: Long = System.currentTimeMillis()): ApplyResult

    /** Quita la localización activa: pasa al historial y los puntos GNSS vuelven a sus coordenadas sin localizar (RF-21). Devuelve cuántos cambiaron. */
    suspend fun remove(projectId: String): Int

    /** Reactiva una localización archivada: una de pares pasa al borrador para volver a aplicarla; la de un SC guardado se aplica ya (RF-21, RF-28). */
    suspend fun reactivate(projectId: String, localizationId: String, nowMs: Long = System.currentTimeMillis()): ApplyResult

    /** Deja activa una localización sin pares con estos parámetros: la de un SC guardado (RF-28). */
    suspend fun applyParams(
        projectId: String,
        name: String,
        params: LocalizationParams,
        nowMs: Long = System.currentTimeMillis(),
    ): ApplyResult
}

@OptIn(ExperimentalCoroutinesApi::class)
class RoomLocalizationRepository(private val database: TopoPhoneDatabase, private val log: AppLog = AppLog.NONE) : LocalizationRepository {
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

    override suspend fun startDraft(projectId: String) {
        database.withTransaction { ensureDraft(projectId) }
    }

    override suspend fun affectedPoints(projectId: String): Int = dao.gnssCount(projectId)

    override suspend fun apply(projectId: String, nowMs: Long): ApplyResult = database.withTransaction {
        val project = database.projects().byId(projectId)?.toProject() ?: return@withTransaction ApplyResult.NotFound
        if (project.terrain != null) return@withTransaction ApplyResult.TerrainActive // RF-22
        val draft = dao.withStatusNow(projectId, LocalizationStatus.DRAFT.name) ?: return@withTransaction ApplyResult.NoDraft
        if (draft.crsText != ProjectCrsText.encode(project.crs) || draft.heightSystem != project.heightSystem.name) {
            return@withTransaction ApplyResult.StaleCrs
        }
        val pairs = dao.pairsNow(draft.id)
        val solution = when (val outcome = LocalizationSolver.solve(pairs.map { it.toPair() }, draft.fixScale)) {
            is LocalizationOutcome.Failed -> return@withTransaction ApplyResult.CannotSolve(outcome.reason)
            is LocalizationOutcome.Solved -> outcome.solution
        }
        archiveActive(projectId)
        dao.update(
            draft.copy(
                status = LocalizationStatus.ACTIVE.name,
                appliedAtMs = nowMs,
                params = solution.params.toText(),
                rmsH = solution.rmsHorizontal,
                rmsV = solution.rmsVertical,
            ),
        )
        val residuals = solution.residuals.associateBy { it.pairId }
        for (row in pairs) {
            val r = residuals[row.id]
            dao.updatePair(row.copy(resE = r?.east, resN = r?.north, resV = r?.vertical))
        }
        val changed = rewritePoints(projectId, solution.params, draft.id)
        log.info(LogCategory.POINTS, "Localización aplicada: $changed puntos GNSS cambian sus coordenadas") // RF-26: sin coordenadas
        ApplyResult.Applied(draft.id, changed)
    }

    override suspend fun applyParams(projectId: String, name: String, params: LocalizationParams, nowMs: Long): ApplyResult =
        database.withTransaction {
            val project = database.projects().byId(projectId)?.toProject() ?: return@withTransaction ApplyResult.NotFound
            if (project.terrain != null) return@withTransaction ApplyResult.TerrainActive
            archiveActive(projectId)
            val id = UUID.randomUUID().toString()
            dao.insert(
                LocalizationEntity(
                    id = id,
                    projectId = projectId,
                    status = LocalizationStatus.ACTIVE.name,
                    origin = LocalizationOrigin.SAVED_CRS.name,
                    name = name,
                    createdAtMs = nowMs,
                    appliedAtMs = nowMs,
                    fixScale = false,
                    residualLimitM = LocalizationLimits.DEFAULT_RESIDUAL_LIMIT_M,
                    scaleLimit = LocalizationLimits.DEFAULT_SCALE_LIMIT,
                    crsText = ProjectCrsText.encode(project.crs),
                    heightSystem = project.heightSystem.name,
                    params = params.toText(),
                    rmsH = null,
                    rmsV = null,
                ),
            )
            val changed = rewritePoints(projectId, params, id)
            log.info(LogCategory.POINTS, "Localización de un SC guardado aplicada: $changed puntos GNSS cambian sus coordenadas")
            ApplyResult.Applied(id, changed)
        }

    override suspend fun remove(projectId: String): Int = database.withTransaction {
        if (dao.withStatusNow(projectId, LocalizationStatus.ACTIVE.name) == null) return@withTransaction 0
        archiveActive(projectId)
        var changed = 0
        for (row in dao.gnssPoints(projectId)) {
            // Vuelven a la medida en el SC sin localizar.
            dao.setLocalized(row.id, row.measuredEasting ?: continue, row.measuredNorthing ?: continue, row.measuredHeight, null)
            changed++
        }
        log.info(LogCategory.POINTS, "Localización quitada: $changed puntos GNSS vuelven a sus coordenadas sin localizar")
        changed
    }

    override suspend fun reactivate(projectId: String, localizationId: String, nowMs: Long): ApplyResult = database.withTransaction {
        val archived = dao.byId(localizationId)?.takeIf { it.projectId == projectId && it.status == LocalizationStatus.ARCHIVED.name }
            ?: return@withTransaction ApplyResult.NotFound
        val params = LocalizationParams.parseOrNull(archived.params) ?: return@withTransaction ApplyResult.NotFound
        if (archived.origin == LocalizationOrigin.SAVED_CRS.name) {
            return@withTransaction applyParams(projectId, archived.name.orEmpty(), params, nowMs)
        }
        dao.withStatusNow(projectId, LocalizationStatus.DRAFT.name)?.let { dao.delete(it.id) }
        val id = UUID.randomUUID().toString()
        dao.insert(
            archived.copy(id = id, status = LocalizationStatus.DRAFT.name, appliedAtMs = null, params = null, rmsH = null, rmsV = null),
        )
        dao.insertPairs(copyPairs(dao.pairsNow(archived.id), id))
        ApplyResult.DraftReady(id)
    }

    private fun copyPairs(rows: List<LocalizationPairEntity>, localizationId: String) =
        rows.map { it.copy(id = UUID.randomUUID().toString(), localizationId = localizationId, resE = null, resN = null, resV = null) }

    private suspend fun archiveActive(projectId: String) {
        dao.withStatusNow(projectId, LocalizationStatus.ACTIVE.name)?.let { dao.setStatus(it.id, LocalizationStatus.ARCHIVED.name) }
    }

    /** Localiza las coordenadas mostradas de los puntos GNSS no editados con [params] (RF-17); devuelve cuántos. */
    private suspend fun rewritePoints(projectId: String, params: LocalizationParams, localizationId: String): Int {
        var changed = 0
        for (row in dao.gnssPoints(projectId)) {
            val e = row.measuredEasting ?: continue
            val n = row.measuredNorthing ?: continue
            val (le, ln) = LocalizationApplier.horizontal(params, e, n)
            val lh = row.measuredHeight?.let { LocalizationApplier.height(params, e, n, it) }
            dao.setLocalized(row.id, le, ln, lh, localizationId)
            changed++
        }
        return changed
    }

    /** El identificador del borrador del proyecto, creado con el SC y las alturas del proyecto si no existe; null si el proyecto no existe. */
    private suspend fun ensureDraft(projectId: String): String? {
        dao.withStatusNow(projectId, LocalizationStatus.DRAFT.name)?.let { return it.id }
        val project = database.projects().byId(projectId)?.toProject() ?: return null
        val id = UUID.randomUUID().toString()
        val active = dao.withStatusNow(projectId, LocalizationStatus.ACTIVE.name)?.takeIf { it.origin == LocalizationOrigin.PAIRS.name }
        if (active != null) {
            // RF-16: editar con una localización activa parte de la activa y no la toca hasta aplicar.
            dao.insert(
                active.copy(id = id, status = LocalizationStatus.DRAFT.name, appliedAtMs = null, params = null, rmsH = null, rmsV = null),
            )
            dao.insertPairs(copyPairs(dao.pairsNow(active.id), id))
            return id
        }
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

/**
 * La localización activa del proyecto (S039/RF-14, RF-17). [info] la sigue en vivo; [current] es la lectura instantánea que usa una medida al
 * empezar: la conserva hasta el final aunque el usuario la quite a mitad.
 */
class ActiveLocalization(private val repository: LocalizationRepository) {
    fun info(projectId: String): Flow<ActiveLocalizationInfo?> = repository.active(projectId).map { it.toInfo() }

    suspend fun current(projectId: String): ActiveLocalizationInfo? = repository.active(projectId).first().toInfo()

    /** La localización activa del proyecto que dé [projectId] en cada momento, siempre al día. */
    fun track(projectId: Flow<String?>, scope: CoroutineScope): StateFlow<ActiveLocalizationInfo?> =
        projectId.flatMapLatest { id -> if (id == null) flowOf(null) else info(id) }.stateIn(scope, SharingStarted.Eagerly, null)

    private fun Localization?.toInfo(): ActiveLocalizationInfo? = this?.params?.let { ActiveLocalizationInfo(id, it) }

    companion object {
        /** Sin localizaciones: para los tests y las vistas previas que no las usan. */
        val NONE = ActiveLocalization(NoLocalizations)
    }
}

private object NoLocalizations : LocalizationRepository {
    override fun draft(projectId: String): Flow<Localization?> = flowOf(null)

    override fun active(projectId: String): Flow<Localization?> = flowOf(null)

    override fun history(projectId: String): Flow<List<Localization>> = flowOf(emptyList())

    override suspend fun addPair(projectId: String, pair: LocalizationPair) = false

    override suspend fun removePair(projectId: String, pairId: String) = Unit

    override suspend fun setComponents(projectId: String, pairId: String, useHorizontal: Boolean, useVertical: Boolean) = Unit

    override suspend fun confirmPair(projectId: String, pairId: String) = Unit

    override suspend fun setFixScale(projectId: String, fixScale: Boolean) = Unit

    override suspend fun setLimits(projectId: String, residualLimitM: Double, scaleLimit: Double) = Unit

    override suspend fun discardDraft(projectId: String) = Unit

    override suspend fun startDraft(projectId: String) = Unit

    override suspend fun affectedPoints(projectId: String) = 0

    override suspend fun apply(projectId: String, nowMs: Long): ApplyResult = ApplyResult.NotFound

    override suspend fun remove(projectId: String) = 0

    override suspend fun reactivate(projectId: String, localizationId: String, nowMs: Long): ApplyResult = ApplyResult.NotFound

    override suspend fun applyParams(projectId: String, name: String, params: LocalizationParams, nowMs: Long): ApplyResult =
        ApplyResult.NotFound
}
