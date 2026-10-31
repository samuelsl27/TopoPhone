package es.topophone.app.ui.collector

import androidx.lifecycle.SavedStateHandle
import es.topophone.core.model.log.AppLog
import es.topophone.core.model.log.LogCategory
import es.topophone.core.model.point.PhotoDestination
import es.topophone.core.model.point.PhotoOrigin
import es.topophone.core.model.point.PhotoPolicy
import es.topophone.data.photo.AddPhotosResult
import es.topophone.data.photo.PhotoFailure
import es.topophone.data.photo.PhotoRepository
import es.topophone.data.photo.PhotoStore
import es.topophone.data.photo.StageResult
import es.topophone.data.photo.StagedPhoto
import es.topophone.data.point.PointRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Qué ha ido mal al añadir fotos desde el recolector (S017b/RF-13 a RF-15). */
sealed interface PhotoError {
    /** La foto no se pudo leer o guardar; el punto queda como estaba (RF-15). */
    data class Failed(val reason: PhotoFailure) : PhotoError

    /** El punto ya tiene 10 fotos (RF-14). */
    data object Full : PhotoError

    /** De las elegidas, [count] no cabían y no se han añadido (RF-13). */
    data class NotAllFit(val count: Int) : PhotoError
}

/**
 * Lo que el recolector muestra de las fotos (S017b/RF-1, RF-3, RF-7, RF-8): a qué punto irán, cuántas tiene ya, si se pueden añadir
 * más y, si el usuario pulsó «Cancelar» con fotos, cuántas se descartarían ([pendingCancel]).
 */
data class PhotoUi(
    val destination: PhotoDestination = PhotoDestination.None,
    /** El nombre del punto de destino, para decirlo (RF-8). */
    val destinationName: String? = null,
    val count: Int = 0,
    val canAdd: Boolean = false,
    val adding: Boolean = false,
    val error: PhotoError? = null,
    val pendingCancel: Int? = null,
)

/** Lo que lanzan «Foto» y «Galería» en el recolector y en la ficha del punto (S017b/RF-1, RF-12). */
interface PhotoSink {
    /** Antes de abrir la cámara: el fichero que escribirá, o null si no se puede añadir. */
    fun beginCapture(): String?

    /** La cámara volvió: con [taken] la foto se añade; sin ella no cambia nada (RF-16). */
    fun finishCapture(taken: Boolean)

    /** Fotos elegidas de la galería: las que quepan, y se dice cuántas se quedan fuera (RF-13, RF-14). */
    fun addFromGallery(uris: List<String>)
}

/** El punto que el recolector guardó el último (RF-5, RF-6). */
private data class SavedRef(val id: String, val name: String)

/**
 * Las fotos del recolector (S017b plan A2 y A3). Las de una medida sin guardar quedan en espera ([PhotoStore]) y se guardan con el
 * punto; las de un punto ya guardado se añaden a él. Se separa del [CollectorViewModel] para no engordarlo: él solo le dice cuándo
 * empieza y acaba cada medida y cuándo guarda un punto.
 *
 * @property busy hay una medida en marcha, en pausa o esperando confirmar (RF-2).
 * @property autoRunning el modo automático está en marcha (RF-6).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CollectorPhotos(
    private val scope: CoroutineScope,
    private val repository: PhotoRepository,
    private val store: PhotoStore,
    private val points: PointRepository,
    busy: Flow<Boolean>,
    autoRunning: Flow<Boolean>,
    private val currentName: () -> String,
    private val handle: SavedStateHandle = SavedStateHandle(),
    private val log: AppLog = AppLog.NONE,
) : PhotoSink {
    private data class Inner(
        val staged: List<StagedPhoto> = emptyList(),
        val saved: SavedRef? = null,
        val adding: Boolean = false,
        val error: PhotoError? = null,
        val pendingCancel: Int? = null,
    )

    /** Las fotos están en marcha; con [PhotoRepository.NONE] (tests y pantallas sin fotos) no hay destino. */
    private val enabled = repository !== PhotoRepository.NONE
    private val inner = MutableStateFlow(Inner())
    private val busyNow = MutableStateFlow(false)
    private val autoNow = MutableStateFlow(false)

    init {
        scope.launch { busy.collect { busyNow.value = it } }
        scope.launch { autoRunning.collect { autoNow.value = it } }
    }

    /** Cuántas fotos tiene el último punto guardado, o null si ya no existe o está en la papelera (RF-5). */
    private val savedCount: Flow<Int?> = inner.map { it.saved?.id }.flatMapLatest { id ->
        if (id == null || !enabled) {
            flowOf(null)
        } else {
            combine(points.point(id), repository.photos(id)) { point, photos ->
                if (point == null || point.deletedAtMs != null) null else photos.size
            }
        }
    }

    val ui: StateFlow<PhotoUi> = combine(inner, busyNow, autoNow, savedCount) { inner, busy, auto, savedCount ->
        val savedExists = inner.saved != null && savedCount != null
        val destination = PhotoDestination.resolve(busy, auto, inner.saved?.id.takeIf { savedExists })
        val count = when (destination) {
            PhotoDestination.Measuring -> inner.staged.size
            is PhotoDestination.Saved -> savedCount ?: 0
            PhotoDestination.None -> 0
        }
        PhotoUi(
            destination = destination,
            destinationName = when (destination) {
                PhotoDestination.Measuring -> currentName()
                is PhotoDestination.Saved -> inner.saved?.name
                PhotoDestination.None -> null
            },
            count = count,
            canAdd = enabled && destination != PhotoDestination.None && count < PhotoPolicy.MAX_PER_POINT && !inner.adding,
            adding = inner.adding,
            error = inner.error,
            pendingCancel = inner.pendingCancel,
        )
    }.stateIn(scope, SharingStarted.Eagerly, PhotoUi())

    /** Las fotos en espera de la medida en curso, para guardarlas con su punto (RF-2). */
    fun staged(): List<StagedPhoto> = inner.value.staged

    /** Empieza un punto: el último guardado deja de ser el destino salvo con el modo automático (RF-5, RF-6). */
    fun measurementStarted(auto: Boolean) {
        inner.update { if (auto) it else it.copy(saved = null, pendingCancel = null, error = null) }
    }

    /** El punto de la medida se guardó con sus fotos en espera: ahora es el último guardado (RF-5). */
    fun saved(pointId: String, name: String) {
        inner.update { it.copy(staged = emptyList(), saved = SavedRef(pointId, name), pendingCancel = null) }
    }

    /** La medida acabó sin guardar su punto (cancelada, fallida, fuera de tolerancia sin confirmar): sus fotos se descartan (RF-4). */
    fun discardStaged() {
        val staged = inner.value.staged
        inner.update { it.copy(staged = emptyList(), pendingCancel = null) }
        if (staged.isNotEmpty()) {
            log.info(LogCategory.POINTS, "Fotos de una medida sin guardar descartadas: ${staged.size}")
            staged.forEach { store.discard(it.id) }
        }
    }

    /** «Cancelar» con fotos en espera pide confirmación (RF-3); true si la pide y la medida no debe cancelarse todavía. */
    fun needsCancelConfirmation(): Boolean {
        val n = inner.value.staged.size
        if (n == 0) return false
        inner.update { it.copy(pendingCancel = n) }
        return true
    }

    /** El usuario prefiere seguir con la medida y sus fotos. */
    fun keepPhotos() {
        inner.update { it.copy(pendingCancel = null) }
    }

    fun dismissError() {
        inner.update { it.copy(error = null) }
    }

    /**
     * Antes de abrir la cámara: guarda a dónde irá la foto y devuelve el fichero que escribirá la cámara del sistema; null si no hay
     * destino. Va al `SavedStateHandle` por si Android cierra la app con la cámara abierta (plan, «Cámara y galería»).
     */
    override fun beginCapture(): String? {
        if (!ui.value.canAdd) return null
        val file = store.newCaptureFile()
        handle[CAPTURE_FILE] = file.path
        handle[CAPTURE_TARGET] = when (val d = ui.value.destination) {
            PhotoDestination.Measuring -> MEASURING
            is PhotoDestination.Saved -> d.pointId
            PhotoDestination.None -> return null
        }
        return file.path
    }

    /** La cámara volvió: con [taken] la foto se añade al destino de antes; sin ella no cambia nada (RF-16). */
    override fun finishCapture(taken: Boolean) {
        val path = handle.get<String>(CAPTURE_FILE) ?: return
        val target = handle.get<String>(CAPTURE_TARGET)
        handle.remove<String>(CAPTURE_FILE)
        handle.remove<String>(CAPTURE_TARGET)
        if (!taken || target == null) {
            java.io.File(path).delete()
            return
        }
        add(listOf(path), PhotoOrigin.CAMERA, target, deleteSources = true)
    }

    /** Fotos elegidas de la galería: se añaden las que quepan y se dice cuántas se quedan fuera (RF-13, RF-14). */
    override fun addFromGallery(uris: List<String>) {
        if (uris.isEmpty()) return // RF-16
        val target = when (val d = ui.value.destination) {
            PhotoDestination.Measuring -> MEASURING
            is PhotoDestination.Saved -> d.pointId
            PhotoDestination.None -> return
        }
        add(uris, PhotoOrigin.GALLERY, target, deleteSources = false)
    }

    private fun add(sources: List<String>, origin: PhotoOrigin, target: String, deleteSources: Boolean) {
        inner.update { it.copy(adding = true, error = null) }
        scope.launch {
            try {
                val existing = if (target == MEASURING) inner.value.staged.size else repository.photos(target).first().size
                val fit = PhotoPolicy.fit(existing, sources.size)
                var error: PhotoError? = when {
                    fit.accepted == 0 -> PhotoError.Full
                    fit.rejected > 0 -> PhotoError.NotAllFit(fit.rejected)
                    else -> null
                }
                val staged = mutableListOf<StagedPhoto>()
                for (source in sources.take(fit.accepted)) {
                    when (val result = withContext(Dispatchers.IO) { store.stage(source, origin) }) {
                        is StageResult.Staged -> staged += result.photo
                        is StageResult.Failed -> {
                            log.error(LogCategory.POINTS, "No se pudo guardar una foto: ${result.reason}")
                            error = PhotoError.Failed(result.reason)
                        }
                    }
                }
                if (deleteSources) sources.forEach { java.io.File(it).delete() }
                if (target == MEASURING) {
                    if (busyNow.value) {
                        inner.update { it.copy(staged = it.staged + staged) }
                    } else {
                        staged.forEach { store.discard(it.id) } // la medida ya no existe (p. ej. Android cerró la app con la cámara)
                    }
                } else if (staged.isNotEmpty()) {
                    when (val added = withContext(Dispatchers.IO) { repository.addPhotos(target, staged) }) {
                        is AddPhotosResult.Added -> if (added.rejected > 0 && error == null) error = PhotoError.NotAllFit(added.rejected)
                        AddPhotosResult.PointNotFound -> staged.forEach { store.discard(it.id) }
                        AddPhotosResult.FileError -> {
                            staged.forEach { store.discard(it.id) }
                            error = PhotoError.Failed(PhotoFailure.WRITE_FAILED)
                        }
                    }
                }
                inner.update { it.copy(error = error) }
            } finally {
                inner.update { it.copy(adding = false) }
            }
        }
    }

    private companion object {
        const val CAPTURE_FILE = "photo_capture_file"
        const val CAPTURE_TARGET = "photo_capture_target"
        const val MEASURING = "measuring"
    }
}
