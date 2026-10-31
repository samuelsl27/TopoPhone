package es.topophone.app.ui.points

import androidx.lifecycle.SavedStateHandle
import es.topophone.app.ui.collector.PhotoError
import es.topophone.app.ui.collector.PhotoSink
import es.topophone.core.model.log.AppLog
import es.topophone.core.model.log.LogCategory
import es.topophone.core.model.point.PhotoOrigin
import es.topophone.core.model.point.PhotoPolicy
import es.topophone.data.photo.AddPhotosResult
import es.topophone.data.photo.PhotoFailure
import es.topophone.data.photo.PhotoRepository
import es.topophone.data.photo.PhotoStore
import es.topophone.data.photo.StageResult
import es.topophone.data.photo.StagedPhoto
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Si se están añadiendo fotos desde la ficha y qué ha ido mal (S017b/RF-13 a RF-15). */
data class PhotoAddUi(val adding: Boolean = false, val error: PhotoError? = null)

/**
 * Añade fotos al punto de la ficha, de la cámara o de la galería (S017b/RF-12): hasta las 10 del punto, con aviso de las que se quedan
 * fuera, y sin tocar el punto si una foto no se puede leer o guardar (RF-15). El destino de la cámara va al `SavedStateHandle` por si
 * Android cierra la app con la cámara abierta (plan, «Cámara y galería»).
 */
class PointPhotoAdder(
    private val scope: CoroutineScope,
    private val repository: PhotoRepository,
    private val store: PhotoStore,
    private val handle: SavedStateHandle = SavedStateHandle(),
    private val log: AppLog = AppLog.NONE,
) : PhotoSink {
    private var pointId: String? = null
    private val inner = MutableStateFlow(PhotoAddUi())
    val ui: StateFlow<PhotoAddUi> = inner

    fun start(pointId: String) {
        this.pointId = pointId
    }

    fun dismissError() {
        inner.update { it.copy(error = null) }
    }

    override fun beginCapture(): String? {
        val point = pointId ?: return null
        if (repository === PhotoRepository.NONE || inner.value.adding) return null
        val file = store.newCaptureFile()
        handle[CAPTURE_FILE] = file.path
        handle[CAPTURE_POINT] = point
        return file.path
    }

    override fun finishCapture(taken: Boolean) {
        val path = handle.get<String>(CAPTURE_FILE) ?: return
        val point = handle.get<String>(CAPTURE_POINT)
        handle.remove<String>(CAPTURE_FILE)
        handle.remove<String>(CAPTURE_POINT)
        if (!taken || point == null) {
            File(path).delete() // RF-16
            return
        }
        add(listOf(path), PhotoOrigin.CAMERA, point, deleteSources = true)
    }

    override fun addFromGallery(uris: List<String>) {
        val point = pointId ?: return
        if (uris.isEmpty()) return // RF-16
        add(uris, PhotoOrigin.GALLERY, point, deleteSources = false)
    }

    private fun add(sources: List<String>, origin: PhotoOrigin, point: String, deleteSources: Boolean) {
        inner.value = PhotoAddUi(adding = true)
        scope.launch {
            var error: PhotoError? = null
            try {
                val fit = PhotoPolicy.fit(repository.photos(point).first().size, sources.size)
                error = when {
                    fit.accepted == 0 -> PhotoError.Full // RF-14
                    fit.rejected > 0 -> PhotoError.NotAllFit(fit.rejected) // RF-13
                    else -> null
                }
                val staged = mutableListOf<StagedPhoto>()
                for (source in sources.take(fit.accepted)) {
                    when (val result = withContext(Dispatchers.IO) { store.stage(source, origin) }) {
                        is StageResult.Staged -> staged += result.photo
                        is StageResult.Failed -> {
                            log.error(LogCategory.POINTS, "No se pudo guardar una foto: ${result.reason}")
                            error = PhotoError.Failed(result.reason) // RF-15
                        }
                    }
                }
                if (deleteSources) sources.forEach { File(it).delete() }
                if (staged.isNotEmpty()) {
                    when (val added = withContext(Dispatchers.IO) { repository.addPhotos(point, staged) }) {
                        is AddPhotosResult.Added -> if (added.rejected > 0 && error == null) error = PhotoError.NotAllFit(added.rejected)
                        AddPhotosResult.PointNotFound -> staged.forEach { store.discard(it.id) }
                        AddPhotosResult.FileError -> {
                            staged.forEach { store.discard(it.id) }
                            error = PhotoError.Failed(PhotoFailure.WRITE_FAILED)
                        }
                    }
                }
            } finally {
                inner.value = PhotoAddUi(adding = false, error = error)
            }
        }
    }

    private companion object {
        const val CAPTURE_FILE = "ficha_photo_capture_file"
        const val CAPTURE_POINT = "ficha_photo_capture_point"
    }
}
