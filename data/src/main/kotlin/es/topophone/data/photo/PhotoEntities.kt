package es.topophone.data.photo

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import es.topophone.data.point.PointEntity
import kotlinx.coroutines.flow.Flow

/**
 * Una foto de un punto (S017b). El contenido está en `filesDir/photos/<id>.jpg`, no en la base de datos (plan: una foto de 1 MB
 * reventaría la ventana del cursor). Se borra con el punto; el fichero lo recoge el barrido (plan A4). [origin] es `CAMERA` o
 * `GALLERY`; las fechas, UTC en ms.
 */
@Entity(
    tableName = "point_photos",
    foreignKeys = [
        ForeignKey(entity = PointEntity::class, parentColumns = ["id"], childColumns = ["point_id"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index("point_id")],
)
data class PointPhotoEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "point_id") val pointId: String,
    val name: String,
    val origin: String,
    @ColumnInfo(name = "taken_at") val takenAt: Long,
    @ColumnInfo(name = "added_at") val addedAt: Long,
    @ColumnInfo(name = "size_bytes") val sizeBytes: Long,
    val width: Int,
    val height: Int,
)

/**
 * Un suceso de foto de un punto ya guardado (S017b/RF-23): `ADDED` o `DELETED`, con el nombre y la fecha de la foto. Aparte de
 * `point_history` porque no es una versión del punto y no lo marca como editado (S011/RF-12). Se borra con el punto.
 */
@Entity(
    tableName = "photo_events",
    foreignKeys = [
        ForeignKey(entity = PointEntity::class, parentColumns = ["id"], childColumns = ["point_id"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index("point_id")],
)
data class PhotoEventEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "point_id") val pointId: String,
    val at: Long,
    val action: String,
    @ColumnInfo(name = "photo_name") val photoName: String,
    @ColumnInfo(name = "photo_taken_at") val photoTakenAt: Long,
)

@Dao
interface PhotoDao {
    @Insert
    suspend fun insert(photo: PointPhotoEntity)

    @Insert
    suspend fun insertEvent(event: PhotoEventEntity)

    @Query("SELECT * FROM point_photos WHERE id = :id")
    suspend fun byId(id: String): PointPhotoEntity?

    /** Las fotos de un punto en el orden en que se añadieron; a igual fecha, por id (plan: modelo de datos). */
    @Query("SELECT * FROM point_photos WHERE point_id = :pointId ORDER BY added_at, id")
    fun photos(pointId: String): Flow<List<PointPhotoEntity>>

    @Query("SELECT * FROM point_photos WHERE point_id = :pointId ORDER BY added_at, id")
    suspend fun photosNow(pointId: String): List<PointPhotoEntity>

    @Query("SELECT COUNT(*) FROM point_photos WHERE point_id = :pointId")
    suspend fun count(pointId: String): Int

    @Query("DELETE FROM point_photos WHERE id = :id")
    suspend fun delete(id: String)

    @Query("SELECT * FROM photo_events WHERE point_id = :pointId ORDER BY at DESC, rowid DESC")
    fun events(pointId: String): Flow<List<PhotoEventEntity>>

    /** Los ids de todas las fotos con fila; el barrido borra los ficheros que no estén aquí (plan A4). */
    @Query("SELECT id FROM point_photos")
    suspend fun allIds(): List<String>
}
