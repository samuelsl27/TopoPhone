package es.topophone.data.crs

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.withTransaction
import es.topophone.data.db.TopoPhoneDatabase
import kotlinx.coroutines.flow.Flow

/** Un SC de la biblioteca marcado como favorito (S034/RF-7): se guarda por su código EPSG, aunque una actualización de la biblioteca lo quite. */
@Entity(tableName = "favorite_crs")
data class FavoriteCrsEntity(@PrimaryKey val epsg: Int, @ColumnInfo(name = "added_at") val addedAt: Long)

/** Un SC elegido en un proyecto, para ofrecerlo antes de buscar (S034/RF-8). */
@Entity(tableName = "recent_crs")
data class RecentCrsEntity(@PrimaryKey val epsg: Int, @ColumnInfo(name = "used_at") val usedAt: Long)

@Dao
interface CrsShortcutDao {
    @Query("SELECT epsg FROM favorite_crs ORDER BY added_at DESC, epsg")
    fun favorites(): Flow<List<Int>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun addFavorite(entity: FavoriteCrsEntity)

    @Query("DELETE FROM favorite_crs WHERE epsg = :epsg")
    suspend fun removeFavorite(epsg: Int)

    @Query("SELECT epsg FROM recent_crs ORDER BY used_at DESC, epsg LIMIT $RECENT_LIMIT")
    fun recents(): Flow<List<Int>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun addRecent(entity: RecentCrsEntity)

    @Query("DELETE FROM recent_crs WHERE epsg NOT IN (SELECT epsg FROM recent_crs ORDER BY used_at DESC, epsg LIMIT $RECENT_LIMIT)")
    suspend fun trimRecents()
}

/** Cuántos SC recientes se guardan (S034/RF-8). */
const val RECENT_LIMIT = 10

/** Los SC favoritos y los últimos elegidos de la biblioteca (S034/RF-6, RF-7, RF-8), sin nube y guardados en el móvil. */
interface CrsShortcutRepository {
    /** Los favoritos, del último marcado al primero; se actualiza con cada cambio. */
    fun favorites(): Flow<List<Int>>

    /** Los últimos [RECENT_LIMIT] SC elegidos, del más reciente al más antiguo y sin repetir. */
    fun recents(): Flow<List<Int>>

    suspend fun setFavorite(epsg: Int, favorite: Boolean)

    /** Anota que se eligió [epsg] en un proyecto. */
    suspend fun markUsed(epsg: Int)
}

class RoomCrsShortcutRepository(private val database: TopoPhoneDatabase, private val clock: () -> Long = System::currentTimeMillis) :
    CrsShortcutRepository {
    private val dao = database.crsShortcuts()

    override fun favorites(): Flow<List<Int>> = dao.favorites()

    override fun recents(): Flow<List<Int>> = dao.recents()

    override suspend fun setFavorite(epsg: Int, favorite: Boolean) {
        if (favorite) dao.addFavorite(FavoriteCrsEntity(epsg, clock())) else dao.removeFavorite(epsg)
    }

    override suspend fun markUsed(epsg: Int) = database.withTransaction {
        dao.addRecent(RecentCrsEntity(epsg, clock()))
        dao.trimRecents()
    }
}
