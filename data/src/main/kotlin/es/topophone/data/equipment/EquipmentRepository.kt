package es.topophone.data.equipment

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import es.topophone.core.model.guide.EquipmentProfile
import es.topophone.data.db.TopoPhoneDatabase
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

@Dao
interface EquipmentProfileDao {
    @Query("SELECT * FROM equipment_profiles WHERE receiver_key = :receiverKey")
    fun observe(receiverKey: String): Flow<EquipmentProfileEntity?>

    @Query("SELECT * FROM equipment_profiles WHERE receiver_key = :receiverKey")
    suspend fun byKey(receiverKey: String): EquipmentProfileEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: EquipmentProfileEntity)

    @Query("DELETE FROM equipment_profiles WHERE receiver_key = :receiverKey")
    suspend fun delete(receiverKey: String)
}

/**
 * Los datos de cada receptor que escribe el usuario y la dirección de su interfaz web (S043/RF-13, RF-18). La clave es la del
 * receptor (S021). El perfil y la dirección se guardan por separado: cambiar uno no borra el otro.
 */
interface EquipmentRepository {
    /** El perfil de un receptor, o null si no hay ninguno guardado; se actualiza con cada cambio. */
    fun profile(receiverKey: String): Flow<EquipmentProfile?>

    /** Guarda el perfil (sin espacios en los extremos). Un perfil sin ningún dato borra el guardado. */
    suspend fun save(receiverKey: String, profile: EquipmentProfile)

    /** La dirección web guardada de un receptor, o null; se actualiza con cada cambio. */
    fun webAddress(receiverKey: String): Flow<String?>

    /** Guarda la dirección web (null o en blanco la borra). */
    suspend fun saveWebAddress(receiverKey: String, address: String?)
}

class RoomEquipmentRepository(database: TopoPhoneDatabase) : EquipmentRepository {
    private val dao = database.equipmentProfiles()

    override fun profile(receiverKey: String): Flow<EquipmentProfile?> = dao.observe(receiverKey).map { row ->
        row?.let { EquipmentProfile(it.model, it.serial, it.firmware) }?.takeUnless { it.isBlank }
    }

    override suspend fun save(receiverKey: String, profile: EquipmentProfile) {
        val clean = profile.trimmed()
        val current = dao.byKey(receiverKey)
        when {
            clean.isBlank && current?.webAddress == null -> dao.delete(receiverKey)
            else -> dao.upsert(EquipmentProfileEntity(receiverKey, clean.model, clean.serial, clean.firmware, current?.webAddress))
        }
    }

    override fun webAddress(receiverKey: String): Flow<String?> = dao.observe(receiverKey).map { it?.webAddress }

    override suspend fun saveWebAddress(receiverKey: String, address: String?) {
        val clean = address?.trim()?.takeIf { it.isNotEmpty() }
        val current = dao.byKey(receiverKey)
        val profile = current?.let { EquipmentProfile(it.model, it.serial, it.firmware) } ?: EquipmentProfile.EMPTY
        when {
            clean == null && profile.isBlank -> if (current != null) dao.delete(receiverKey)
            else -> dao.upsert(EquipmentProfileEntity(receiverKey, profile.model, profile.serial, profile.firmware, clean))
        }
    }
}
