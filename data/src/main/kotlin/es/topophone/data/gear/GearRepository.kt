package es.topophone.data.gear

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.withTransaction
import es.topophone.core.model.point.PoleKind
import es.topophone.core.model.project.ProjectNames
import es.topophone.core.survey.ActiveGear
import es.topophone.core.survey.AntennaCatalog
import es.topophone.core.survey.AntennaProfile
import es.topophone.core.survey.GearProblemField
import es.topophone.core.survey.GearResolver
import es.topophone.core.survey.GearValidation
import es.topophone.core.survey.PoleCheck
import es.topophone.core.survey.ProfileCheck
import es.topophone.core.survey.SavedPole
import es.topophone.data.db.TopoPhoneDatabase
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** Resultado de una operación sobre los perfiles de antena y los jalones guardados (S046, plan A4). */
sealed interface GearResult {
    data class Saved(val id: String) : GearResult

    data object Deleted : GearResult

    /** Lo escrito no vale (S046/RF-4, RF-10). */
    data class Invalid(val field: GearProblemField) : GearResult

    /** El perfil lo usan estos receptores y no se borra (S046/RF-6). */
    data class InUse(val receivers: List<String>) : GearResult

    /** Es el último jalón y no se borra (S046/RF-11). */
    data object LastPole : GearResult

    /** Es el jalón activo de estos receptores: hay que elegir antes otro (S046/RF-12). */
    data class ActiveElsewhere(val receivers: List<String>) : GearResult

    /** El perfil, el jalón o el receptor que se nombra no existe. */
    data object NotFound : GearResult
}

@Dao
interface GearDao {
    @Query("SELECT * FROM antenna_profiles ORDER BY created_ms, id")
    fun profiles(): Flow<List<AntennaProfileEntity>>

    @Query("SELECT * FROM antenna_profiles ORDER BY created_ms, id")
    suspend fun profilesNow(): List<AntennaProfileEntity>

    @Query("SELECT * FROM antenna_profiles WHERE id = :id")
    suspend fun profile(id: String): AntennaProfileEntity?

    @Query("SELECT * FROM saved_poles ORDER BY created_ms, id")
    fun poles(): Flow<List<SavedPoleEntity>>

    @Query("SELECT * FROM saved_poles ORDER BY created_ms, id")
    suspend fun polesNow(): List<SavedPoleEntity>

    @Query("SELECT * FROM saved_poles WHERE id = :id")
    suspend fun pole(id: String): SavedPoleEntity?

    @Query("SELECT * FROM receiver_gear WHERE receiver_key = :key")
    fun gear(key: String): Flow<ReceiverGearEntity?>

    @Query("SELECT * FROM receiver_gear WHERE receiver_key = :key")
    suspend fun gearNow(key: String): ReceiverGearEntity?

    @Query("SELECT * FROM receiver_gear")
    suspend fun allGear(): List<ReceiverGearEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertProfile(entity: AntennaProfileEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertPole(entity: SavedPoleEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertGear(entity: ReceiverGearEntity)

    @Query("DELETE FROM antenna_profiles WHERE id = :id")
    suspend fun deleteProfile(id: String)

    @Query("DELETE FROM saved_poles WHERE id = :id")
    suspend fun deletePole(id: String)
}

/**
 * Los perfiles de antena propios, los jalones guardados y el equipo de cada receptor (S046). Cada operación va en una sola
 * transacción (RNF-T6).
 */
interface GearRepository {
    /** El catálogo y los perfiles propios, por antigüedad (S046/RF-1, RF-3). */
    fun profiles(): Flow<List<AntennaProfile>>

    /** Los jalones guardados, por antigüedad (S046/RF-9). */
    fun poles(): Flow<List<SavedPole>>

    /** El equipo de un receptor ya resuelto (S046/RF-7, RF-13). */
    fun gear(receiverKey: String): Flow<ActiveGear>

    /** Alta de un perfil propio (`id` null) o su edición (S046/RF-3 a RF-5). */
    suspend fun saveProfile(id: String?, name: String, offsetText: String, radiusText: String): GearResult

    suspend fun deleteProfile(id: String): GearResult

    /** Alta de un jalón (`id` null) o su edición (S046/RF-9, RF-10, RF-16). */
    suspend fun savePole(id: String?, name: String, kind: PoleKind, heightText: String): GearResult

    suspend fun deletePole(id: String): GearResult

    /** El perfil de un receptor: `catalog:<modelo>` o `own:<uuid>` (S046/RF-7). */
    suspend fun setProfile(receiverKey: String, profileRef: String): GearResult

    /** El jalón activo de un receptor (S046/RF-13). */
    suspend fun setActivePole(receiverKey: String, poleId: String): GearResult
}

class RoomGearRepository(
    private val database: TopoPhoneDatabase,
    private val idGenerator: () -> String = { UUID.randomUUID().toString() },
    private val clock: () -> Long = System::currentTimeMillis,
) : GearRepository {

    private val dao = database.gear()

    override fun profiles(): Flow<List<AntennaProfile>> =
        dao.profiles().map { rows -> AntennaCatalog.ALL + rows.map { it.toProfile() } }.distinctUntilChanged()

    override fun poles(): Flow<List<SavedPole>> = dao.poles().map { rows -> rows.map { it.toPole() } }.distinctUntilChanged()

    override fun gear(receiverKey: String): Flow<ActiveGear> =
        combine(dao.profiles(), dao.poles(), dao.gear(receiverKey)) { profiles, poles, row ->
            GearResolver.resolve(row?.profileRef, profiles.map { it.toProfile() }, row?.activePoleId, poles.map { it.toPole() })
        }.distinctUntilChanged()

    override suspend fun saveProfile(id: String?, name: String, offsetText: String, radiusText: String): GearResult =
        database.withTransaction {
            val own = dao.profilesNow()
            val editing = id?.let { key -> own.firstOrNull { it.id == key } ?: return@withTransaction GearResult.NotFound }
            val existing = AntennaCatalog.ALL.map { it.name } + own.filter { it.id != id }.map { it.name }
            when (val check = GearValidation.profile(name, offsetText, radiusText, existing, null)) {
                is ProfileCheck.Invalid -> GearResult.Invalid(check.field)
                is ProfileCheck.Valid -> {
                    val saved = AntennaProfileEntity(
                        id = id ?: idGenerator(),
                        name = check.name,
                        nameKey = ProjectNames.key(check.name),
                        offsetM = check.offsetM,
                        radiusM = check.radiusM,
                        createdMs = editing?.createdMs ?: clock(),
                    )
                    dao.upsertProfile(saved)
                    GearResult.Saved(saved.id)
                }
            }
        }

    override suspend fun deleteProfile(id: String): GearResult = database.withTransaction {
        if (dao.profile(id) == null) return@withTransaction GearResult.NotFound
        val users = dao.allGear().filter { it.profileRef == "own:$id" }.map { it.receiverKey }
        if (users.isNotEmpty()) return@withTransaction GearResult.InUse(users)
        dao.deleteProfile(id)
        GearResult.Deleted
    }

    override suspend fun savePole(id: String?, name: String, kind: PoleKind, heightText: String): GearResult = database.withTransaction {
        val all = dao.polesNow()
        val editing = id?.let { key -> all.firstOrNull { it.id == key } ?: return@withTransaction GearResult.NotFound }
        val existing = all.filter { it.id != id }.map { it.name }
        when (val check = GearValidation.pole(name, kind, heightText, existing, null)) {
            is PoleCheck.Invalid -> GearResult.Invalid(check.field)
            is PoleCheck.Valid -> {
                val saved = SavedPoleEntity(
                    id = id ?: idGenerator(),
                    name = check.name,
                    nameKey = ProjectNames.key(check.name),
                    kind = check.kind.name,
                    enteredM = check.enteredM,
                    createdMs = editing?.createdMs ?: clock(),
                )
                dao.upsertPole(saved)
                GearResult.Saved(saved.id)
            }
        }
    }

    override suspend fun deletePole(id: String): GearResult = database.withTransaction {
        val all = dao.polesNow()
        if (all.none { it.id == id }) return@withTransaction GearResult.NotFound
        if (all.size == 1) return@withTransaction GearResult.LastPole
        val active = dao.allGear().filter { it.activePoleId == id }.map { it.receiverKey }
        if (active.isNotEmpty()) return@withTransaction GearResult.ActiveElsewhere(active)
        dao.deletePole(id)
        GearResult.Deleted
    }

    override suspend fun setProfile(receiverKey: String, profileRef: String): GearResult = database.withTransaction {
        val known = AntennaCatalog.byRef(profileRef) != null ||
            (profileRef.startsWith("own:") && dao.profile(profileRef.removePrefix("own:")) != null)
        if (!known) return@withTransaction GearResult.NotFound
        val row = dao.gearNow(receiverKey) ?: ReceiverGearEntity(receiverKey, null, null)
        dao.upsertGear(row.copy(profileRef = profileRef))
        GearResult.Saved(receiverKey)
    }

    override suspend fun setActivePole(receiverKey: String, poleId: String): GearResult = database.withTransaction {
        if (dao.pole(poleId) == null) return@withTransaction GearResult.NotFound
        val row = dao.gearNow(receiverKey) ?: ReceiverGearEntity(receiverKey, null, null)
        dao.upsertGear(row.copy(activePoleId = poleId))
        GearResult.Saved(receiverKey)
    }

    private fun AntennaProfileEntity.toProfile() = AntennaProfile("own:$id", name, offsetM, radiusM, builtIn = false)

    private fun SavedPoleEntity.toPole() = SavedPole(id, name, PoleKind.valueOf(kind), enteredM)
}
