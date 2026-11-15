package es.topophone.data.config

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import es.topophone.core.model.guide.ConfigListId
import es.topophone.data.db.TopoPhoneDatabase
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

@Dao
interface ConfigStepDao {
    @Query("SELECT step_id FROM config_steps WHERE receiver_key = :receiverKey AND list_id = :listId")
    fun observe(receiverKey: String, listId: String): Flow<List<String>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: ConfigStepEntity)

    @Query("DELETE FROM config_steps WHERE receiver_key = :receiverKey AND list_id = :listId AND step_id = :stepId")
    suspend fun delete(receiverKey: String, listId: String, stepId: String)

    @Query("DELETE FROM config_steps WHERE receiver_key = :receiverKey AND list_id = :listId")
    suspend fun deleteList(receiverKey: String, listId: String)
}

/**
 * El progreso de las listas de configuración de cada receptor (S043/RF-3): qué pasos tiene hechos el usuario. La clave es la del
 * receptor (dirección Bluetooth o `wifi:<id>`, S021): el progreso es de ese receptor y de esa lista, no del proyecto.
 */
interface ConfigProgressRepository {
    /** Los pasos hechos de una lista para un receptor; se actualiza con cada cambio. */
    fun done(receiverKey: String, listId: ConfigListId): Flow<Set<String>>

    /** Marca o desmarca un paso; marcar uno ya marcado no cambia nada. */
    suspend fun setDone(receiverKey: String, listId: ConfigListId, stepId: String, done: Boolean)

    /** Borra todos los pasos hechos de una lista para un receptor (RF-3). */
    suspend fun reset(receiverKey: String, listId: ConfigListId)
}

class RoomConfigProgressRepository(database: TopoPhoneDatabase, private val clock: () -> Long = System::currentTimeMillis) :
    ConfigProgressRepository {
    private val dao = database.configSteps()

    override fun done(receiverKey: String, listId: ConfigListId): Flow<Set<String>> =
        dao.observe(receiverKey, listId.name).map { it.toSet() }

    override suspend fun setDone(receiverKey: String, listId: ConfigListId, stepId: String, done: Boolean) {
        if (done) {
            dao.insert(ConfigStepEntity(receiverKey, listId.name, stepId, clock()))
        } else {
            dao.delete(receiverKey, listId.name, stepId)
        }
    }

    override suspend fun reset(receiverKey: String, listId: ConfigListId) = dao.deleteList(receiverKey, listId.name)
}
