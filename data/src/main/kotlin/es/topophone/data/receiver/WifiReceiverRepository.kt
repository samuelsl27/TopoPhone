package es.topophone.data.receiver

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import es.topophone.core.model.receiver.WifiReceiver
import es.topophone.core.model.receiver.WifiReceiverInput
import es.topophone.data.db.TopoPhoneDatabase
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

@Dao
interface WifiReceiverDao {
    @Query("SELECT * FROM wifi_receivers ORDER BY created_at, id")
    fun observeAll(): Flow<List<WifiReceiverEntity>>

    @Query("SELECT * FROM wifi_receivers WHERE id = :id")
    fun observe(id: String): Flow<WifiReceiverEntity?>

    @Query("SELECT * FROM wifi_receivers WHERE id = :id")
    suspend fun byId(id: String): WifiReceiverEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(entity: WifiReceiverEntity)

    @Query(
        "UPDATE wifi_receivers SET name = :name, host = :host, position_port = :positionPort, corrections_port = :correctionsPort " +
            "WHERE id = :id",
    )
    suspend fun update(id: String, name: String, host: String, positionPort: Int, correctionsPort: Int?): Int

    @Query("DELETE FROM wifi_receivers WHERE id = :id")
    suspend fun delete(id: String): Int
}

/** Los receptores Wi-Fi guardados (S021/RF-4, RF-5). El nombre y los puertos se guardan tal cual llegan, ya validados. */
interface WifiReceiverRepository {
    /** Todos, por orden de alta; se actualiza con cada cambio. */
    fun receivers(): Flow<List<WifiReceiver>>

    /** Uno, o null si no existe (o se borró); se actualiza con cada cambio. */
    fun observe(id: String): Flow<WifiReceiver?>

    suspend fun byId(id: String): WifiReceiver?

    /** Guarda un receptor nuevo y devuelve su identidad, que ya no cambia (S021/RF-5). */
    suspend fun add(input: WifiReceiverInput): String

    /** Cambia nombre, dirección y puertos conservando la identidad y el orden; false si el receptor no existe. */
    suspend fun update(id: String, input: WifiReceiverInput): Boolean

    /** Borra el receptor; false si no existía. */
    suspend fun delete(id: String): Boolean
}

class RoomWifiReceiverRepository(
    database: TopoPhoneDatabase,
    private val clock: () -> Long = System::currentTimeMillis,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) : WifiReceiverRepository {
    private val dao = database.wifiReceivers()

    override fun receivers(): Flow<List<WifiReceiver>> = dao.observeAll().map { rows -> rows.map { it.toModel() } }

    override fun observe(id: String): Flow<WifiReceiver?> = dao.observe(id).map { it?.toModel() }

    override suspend fun byId(id: String): WifiReceiver? = dao.byId(id)?.toModel()

    override suspend fun add(input: WifiReceiverInput): String {
        val id = newId()
        dao.insert(WifiReceiverEntity(id, input.name, input.host, input.positionPort, input.correctionsPort, clock()))
        return id
    }

    override suspend fun update(id: String, input: WifiReceiverInput): Boolean =
        dao.update(id, input.name, input.host, input.positionPort, input.correctionsPort) > 0

    override suspend fun delete(id: String): Boolean = dao.delete(id) > 0

    private fun WifiReceiverEntity.toModel() = WifiReceiver(id, name, host, positionPort, correctionsPort)
}
