package es.topophone.data.photo

import androidx.room.withTransaction
import es.topophone.core.model.log.AppLog
import es.topophone.core.model.log.LogCategory
import es.topophone.core.model.point.PhotoAction
import es.topophone.core.model.point.PhotoEvent
import es.topophone.core.model.point.PhotoNames
import es.topophone.core.model.point.PhotoOrigin
import es.topophone.core.model.point.PhotoPolicy
import es.topophone.core.model.point.Point
import es.topophone.core.model.point.PointPhoto
import es.topophone.data.db.TopoPhoneDatabase
import es.topophone.data.point.PointRepository
import es.topophone.data.point.PointSaveResult
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Resultado de guardar un punto con las fotos de su medida (S017b plan A3). */
sealed interface PointWithPhotosResult {
    data object Saved : PointWithPhotosResult

    /** Ya hay un punto con ese nombre: no se guardó nada y las fotos siguen en espera. */
    data object DuplicateName : PointWithPhotosResult

    /** No se pudieron mover los ficheros: no se guardó nada y las fotos siguen en espera. */
    data object FileError : PointWithPhotosResult
}

/** Resultado de añadir fotos a un punto ya guardado (S017b/RF-12, RF-13, RF-14). */
sealed interface AddPhotosResult {
    /** [added] se guardaron; [rejected] no cabían en las 10 del punto y se descartaron. */
    data class Added(val added: List<PointPhoto>, val rejected: Int) : AddPhotosResult

    data object PointNotFound : AddPhotosResult

    data object FileError : AddPhotosResult
}

/**
 * Las fotos de los puntos (S017b). Las filas están en la base de datos y los ficheros en `photos/` ([PhotoStore]). Nada de esto
 * toca la fila del punto ni su historial: añadir o borrar una foto no marca el punto como editado (S011/RF-12).
 */
interface PhotoRepository {
    /** Guarda [point] con las fotos en espera [photos] en una sola transacción; no anota sucesos (plan A3). */
    suspend fun createWithPhotos(point: Point, photos: List<StagedPhoto>): PointWithPhotosResult

    /** Añade fotos en espera a un punto ya guardado, hasta las 10 del punto, y anota un suceso por foto (RF-14, RF-23). */
    suspend fun addPhotos(pointId: String, photos: List<StagedPhoto>): AddPhotosResult

    /** Borra una foto de forma definitiva y anota el suceso (RF-22, RF-23); false si ya no existe. */
    suspend fun deletePhoto(photoId: String): Boolean

    /** Las fotos del punto, en el orden en que se añadieron (RF-9). */
    fun photos(pointId: String): Flow<List<PointPhoto>>

    /** Los sucesos de foto del punto, del más reciente al más antiguo (RF-23). */
    fun events(pointId: String): Flow<List<PhotoEvent>>

    /** El fichero de la foto [photoId] si existe. */
    fun file(photoId: String): File?
}

class RoomPhotoRepository(
    private val database: TopoPhoneDatabase,
    private val points: PointRepository,
    private val store: PhotoStore,
    private val clock: () -> Long = System::currentTimeMillis,
    private val log: AppLog = AppLog.NONE,
) : PhotoRepository {
    private val dao = database.photos()

    private class Rollback(val result: PointWithPhotosResult) : RuntimeException()

    override suspend fun createWithPhotos(point: Point, photos: List<StagedPhoto>): PointWithPhotosResult {
        if (photos.isEmpty()) {
            return when (points.create(point)) {
                PointSaveResult.Saved -> PointWithPhotosResult.Saved
                else -> PointWithPhotosResult.DuplicateName
            }
        }
        if (!moveToFinal(photos)) return PointWithPhotosResult.FileError
        return try {
            database.withTransaction {
                if (points.create(point) != PointSaveResult.Saved) throw Rollback(PointWithPhotosResult.DuplicateName)
                val now = clock()
                val taken = mutableListOf<String>()
                for (photo in photos) {
                    val name = PhotoNames.of(point.name, photo.takenAtMs, taken)
                    taken += name
                    dao.insert(photo.toEntity(point.id, name, now))
                }
            }
            log.info(LogCategory.POINTS, "Guardado ${point.name} con ${photos.size} fotos")
            PointWithPhotosResult.Saved
        } catch (e: Rollback) {
            moveBack(photos)
            e.result
        } catch (e: Exception) {
            moveBack(photos)
            log.error(LogCategory.POINTS, "No se pudo guardar el punto con sus fotos: ${e.javaClass.simpleName}")
            PointWithPhotosResult.FileError
        }
    }

    override suspend fun addPhotos(pointId: String, photos: List<StagedPhoto>): AddPhotosResult {
        val point = database.points().byId(pointId) ?: return AddPhotosResult.PointNotFound
        return try {
            val added = mutableListOf<PointPhoto>()
            var rejected = 0
            database.withTransaction {
                val existing = dao.photosNow(pointId)
                val fit = PhotoPolicy.fit(existing.size, photos.size)
                rejected = fit.rejected
                val accepted = photos.take(fit.accepted)
                val now = clock()
                val taken = existing.map { it.name }.toMutableList()
                for (photo in accepted) {
                    val name = PhotoNames.of(point.name, photo.takenAtMs, taken)
                    taken += name
                    if (!store.pendingFile(photo.id).renameTo(store.photoFile(photo.id))) throw IOException("No se pudo mover la foto")
                    val entity = photo.toEntity(pointId, name, now)
                    dao.insert(entity)
                    dao.insertEvent(
                        PhotoEventEntity(UUID.randomUUID().toString(), pointId, now, PhotoAction.ADDED.name, name, photo.takenAtMs),
                    )
                    added += entity.toModel()
                }
            }
            photos.drop(photos.size - rejected).forEach { store.discard(it.id) }
            log.info(LogCategory.POINTS, "Fotos añadidas a ${point.name}: ${added.size}, fuera por el límite: $rejected")
            AddPhotosResult.Added(added, rejected)
        } catch (e: Exception) {
            // La transacción se deshizo: los ficheros ya movidos vuelven a espera.
            moveBack(photos)
            log.error(LogCategory.POINTS, "No se pudieron añadir las fotos: ${e.javaClass.simpleName}")
            AddPhotosResult.FileError
        }
    }

    override suspend fun deletePhoto(photoId: String): Boolean {
        val photo = dao.byId(photoId) ?: return false
        database.withTransaction {
            dao.delete(photoId)
            dao.insertEvent(
                PhotoEventEntity(UUID.randomUUID().toString(), photo.pointId, clock(), PhotoAction.DELETED.name, photo.name, photo.takenAt),
            )
        }
        // Si el fichero no se borra, el barrido lo recoge (plan A3).
        store.photoFile(photoId).delete()
        log.info(LogCategory.POINTS, "Foto borrada de un punto")
        return true
    }

    override fun photos(pointId: String): Flow<List<PointPhoto>> = dao.photos(pointId).map { rows -> rows.map { it.toModel() } }

    override fun events(pointId: String): Flow<List<PhotoEvent>> = dao.events(pointId).map { rows -> rows.map { it.toModel() } }

    override fun file(photoId: String): File? = store.photoFile(photoId).takeIf { it.isFile }

    private fun moveToFinal(photos: List<StagedPhoto>): Boolean {
        val moved = mutableListOf<StagedPhoto>()
        for (photo in photos) {
            if (!store.pendingFile(photo.id).renameTo(store.photoFile(photo.id))) {
                moveBack(moved)
                return false
            }
            moved += photo
        }
        return true
    }

    private fun moveBack(photos: List<StagedPhoto>) {
        for (photo in photos) {
            val final = store.photoFile(photo.id)
            if (final.exists()) final.renameTo(store.pendingFile(photo.id))
        }
    }

    private fun StagedPhoto.toEntity(pointId: String, name: String, addedAt: Long) =
        PointPhotoEntity(id, pointId, name, origin.name, takenAtMs, addedAt, sizeBytes, width, height)

    private fun PointPhotoEntity.toModel() = PointPhoto(
        id, pointId, name,
        PhotoOrigin.entries.firstOrNull {
            it.name == origin
        } ?: PhotoOrigin.GALLERY,
        takenAt, addedAt, sizeBytes, width, height,
    )

    private fun PhotoEventEntity.toModel() = PhotoEvent(
        at,
        PhotoAction.entries.firstOrNull { it.name == action } ?: PhotoAction.ADDED,
        photoName,
        photoTakenAt,
    )
}
