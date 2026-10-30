package es.topophone.data.baseshift

import androidx.room.withTransaction
import es.topophone.core.model.point.BaseShift
import es.topophone.core.model.point.BaseShiftVector
import es.topophone.core.model.point.GeodeticPosition
import es.topophone.core.model.project.CorrectionsDatum
import es.topophone.data.db.TopoPhoneDatabase
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/** Qué pasó al activar un desplazamiento (S020/RF-12, RF-16). */
enum class ActivateResult {
    ACTIVATED,
    NOT_FOUND,

    /** Se calculó con otro datum de correcciones que el que tiene ahora el proyecto: ya no vale (RF-16). */
    DATUM_MISMATCH,
}

/**
 * Los desplazamientos de base de un proyecto (S020/RF-12). Cada proyecto tiene uno activo o ninguno; activar uno desactiva el anterior en
 * la misma transacción. Desactivar y borrar no tocan ningún punto: cada uno guardó su propio vector (RF-9, constitución nº 1).
 */
interface BaseShiftRepository {
    /** Del más reciente al más antiguo. */
    fun shifts(projectId: String): Flow<List<BaseShift>>

    fun active(projectId: String): Flow<BaseShift?>

    /** Guarda [shift] y, con [activate], lo deja como el activo del proyecto, todo en una transacción (RNF-T6). */
    suspend fun add(shift: BaseShift, activate: Boolean)

    suspend fun activate(id: String): ActivateResult

    suspend fun deactivate(projectId: String)

    suspend fun delete(id: String)

    /** El usuario ya vio que un cambio de datum desactivó este desplazamiento (RF-16). */
    suspend fun acknowledgeDatumNotice(id: String)
}

class RoomBaseShiftRepository(private val database: TopoPhoneDatabase) : BaseShiftRepository {
    private val dao = database.baseShifts()

    override fun shifts(projectId: String): Flow<List<BaseShift>> = dao.all(projectId).map { rows -> rows.map { it.toModel() } }

    override fun active(projectId: String): Flow<BaseShift?> = dao.active(projectId).map { it?.toModel() }

    override suspend fun add(shift: BaseShift, activate: Boolean) = database.withTransaction {
        if (activate) dao.deactivateAll(shift.projectId)
        dao.insert(shift.toEntity().copy(active = activate))
    }

    override suspend fun activate(id: String): ActivateResult = database.withTransaction {
        val entity = dao.byId(id) ?: return@withTransaction ActivateResult.NOT_FOUND
        val datum = database.projects().byId(entity.projectId)?.correctionsDatum ?: return@withTransaction ActivateResult.NOT_FOUND
        if (entity.correctionsDatum != datum) return@withTransaction ActivateResult.DATUM_MISMATCH
        dao.deactivateAll(entity.projectId)
        dao.activate(id)
        dao.clearDatumNotice(id)
        ActivateResult.ACTIVATED
    }

    override suspend fun deactivate(projectId: String) {
        dao.deactivateAll(projectId)
    }

    override suspend fun delete(id: String) = dao.delete(id)

    override suspend fun acknowledgeDatumNotice(id: String) = dao.clearDatumNotice(id)
}

/**
 * El desplazamiento activo del proyecto (S020/RF-7, RF-9, RF-11). [vector] lo sigue en vivo; [current] es la lectura instantánea que usa una
 * medida al empezar: la conserva hasta el final aunque el usuario lo desactive a mitad.
 */
class ActiveBaseShift(private val repository: BaseShiftRepository) {
    fun shift(projectId: String): Flow<BaseShift?> = repository.active(projectId)

    fun vector(projectId: String): Flow<BaseShiftVector?> = repository.active(projectId).map { it?.vector }

    suspend fun current(projectId: String): BaseShift? = repository.active(projectId).first()
}

internal fun BaseShiftEntity.toModel() = BaseShift(
    id = id,
    projectId = projectId,
    createdAtMs = createdAtMs,
    fromReplay = fromReplay,
    knownName = knownName,
    knownPointId = knownPointId,
    knownEasting = knownEasting,
    knownNorthing = knownNorthing,
    knownHeightM = knownHeight,
    measured = GeodeticPosition(measuredLat, measuredLon, measuredH),
    epochsUsed = epochsUsed,
    vector = BaseShiftVector(dx, dy, dz),
    horizontalOnly = horizontalOnly,
    correctionsDatum = CorrectionsDatum.entries.firstOrNull { it.name == correctionsDatum } ?: CorrectionsDatum.ETRS89,
    active = active,
    base = if (baseLat != null && baseLon != null) GeodeticPosition(baseLat, baseLon, baseH) else null,
    datumNotice = datumNotice,
)

internal fun BaseShift.toEntity() = BaseShiftEntity(
    id = id,
    projectId = projectId,
    createdAtMs = createdAtMs,
    fromReplay = fromReplay,
    knownName = knownName,
    knownPointId = knownPointId,
    knownEasting = knownEasting,
    knownNorthing = knownNorthing,
    knownHeight = knownHeightM,
    measuredLat = measured.latitudeDeg,
    measuredLon = measured.longitudeDeg,
    measuredH = measured.ellipsoidalHeightM ?: 0.0,
    epochsUsed = epochsUsed,
    dx = vector.dx,
    dy = vector.dy,
    dz = vector.dz,
    horizontalOnly = horizontalOnly,
    correctionsDatum = correctionsDatum.name,
    active = active,
    baseLat = base?.latitudeDeg,
    baseLon = base?.longitudeDeg,
    baseH = base?.ellipsoidalHeightM,
    datumNotice = datumNotice,
)
