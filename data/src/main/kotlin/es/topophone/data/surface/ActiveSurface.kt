package es.topophone.data.surface

import es.topophone.core.model.surface.Surface
import es.topophone.core.model.surface.SurfaceGuide
import es.topophone.core.model.surface.SurfaceHeight
import es.topophone.core.model.surface.SurfaceHeightSource
import es.topophone.core.model.surface.SurfaceTile
import es.topophone.core.survey.TinDirectory
import es.topophone.core.survey.TinElevation
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.launch

/**
 * Una superficie activa en memoria (S042, plan A4). Al crearla solo se tienen las celdas de sus teselas, unos pocos kB aunque la
 * superficie tenga un millón de triángulos; los bloques se leen a demanda con [loadTile] y se guardan en una caché de [cacheSize].
 *
 * [heightAt] no bloquea ni espera a la base de datos (se llama con cada época, a 10 Hz): si la tesela que contiene la posición no está
 * en memoria responde [SurfaceHeight.Loading], la pide en segundo plano y pide también las vecinas por si la punta se mueve hacia ellas.
 * Nunca presenta una posición sin cargar como [SurfaceHeight.Outside].
 */
class ActiveSurface(
    val surface: Surface,
    private val cells: List<SurfaceCell>,
    private val loadTile: suspend (Long) -> SurfaceTile?,
    ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val cacheSize: Int = CACHE_TILES,
    private val preloadRadiusM: Double = PRELOAD_RADIUS_M,
) : SurfaceHeightSource {
    private val directory = TinDirectory(cells.map { it.region })
    private val scope = CoroutineScope(SupervisorJob() + ioDispatcher)
    private val lock = Any()
    private val cache = object : LinkedHashMap<Int, SurfaceTile>(cacheSize + 1, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, SurfaceTile>?): Boolean = size > cacheSize
    }
    private val loading = HashSet<Int>()
    private val closed = AtomicBoolean(false)

    /** Cuántas teselas hay en memoria ahora. */
    val cachedTiles: Int get() = synchronized(lock) { cache.size }

    override fun heightAt(easting: Double, northing: Double): SurfaceHeight {
        val candidates = directory.candidates(easting, northing)
        if (candidates.isEmpty()) return SurfaceHeight.Outside
        var missing = false
        for (index in candidates) {
            val tile = cached(index)
            if (tile == null) {
                missing = true
                continue
            }
            TinElevation.at(tile, easting, northing)?.let {
                preload(easting, northing)
                return SurfaceHeight.Value(it)
            }
        }
        if (!missing) {
            preload(easting, northing)
            return SurfaceHeight.Outside
        }
        candidates.forEach(::request)
        preload(easting, northing)
        return SurfaceHeight.Loading
    }

    private fun cached(index: Int): SurfaceTile? = synchronized(lock) { cache[index] }

    /** Pide en segundo plano las teselas vecinas que aún no están, sin pasar de la mitad de la caché de una vez. */
    private fun preload(easting: Double, northing: Double) {
        var budget = cacheSize / 2
        for (index in directory.near(easting, northing, preloadRadiusM)) {
            if (budget == 0) return
            if (request(index)) budget--
        }
    }

    /** Lanza la lectura de la tesela [index] si no está en memoria ni se está leyendo; devuelve si la lanzó. */
    private fun request(index: Int): Boolean {
        if (closed.get()) return false
        synchronized(lock) {
            if (cache.containsKey(index) || !loading.add(index)) return false
        }
        scope.launch {
            try {
                val tile = loadTile(cells[index].tileId)
                if (tile != null) synchronized(lock) { cache[index] = tile }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Una tesela ilegible no se reintenta en cada época: queda como vacía (sin cota) en lugar de cargando para siempre.
                synchronized(lock) { cache[index] = EMPTY_TILE }
            } finally {
                synchronized(lock) { loading.remove(index) }
            }
        }
        return true
    }

    /** Libera la caché y cancela las lecturas en curso. */
    fun close() {
        if (!closed.compareAndSet(false, true)) return
        scope.cancel()
        synchronized(lock) {
            cache.clear()
            loading.clear()
        }
    }

    val isClosed: Boolean get() = closed.get()

    companion object {
        /** Teselas en memoria: de 4.000 triángulos cada una son unos 100 kB, así que son unos pocos MB. */
        const val CACHE_TILES = 16

        /** Se piden las teselas que quedan a esta distancia de la punta, por si se mueve hacia ellas. */
        const val PRELOAD_RADIUS_M = 100.0

        private val EMPTY_TILE = SurfaceTile(DoubleArray(0), DoubleArray(0), DoubleArray(0), IntArray(0))
    }
}

/** Quien da la superficie activa de un proyecto con su desplazamiento vertical; los replanteos y el recolector la piden por aquí (S042). */
fun interface SurfaceGuides {
    /** La superficie activa del proyecto lista para el replanteo, o null si no hay (S042/RF-24 a RF-26). */
    fun guide(projectId: String): Flow<SurfaceGuide?>

    companion object {
        /** Sin superficies: para los tests y las vistas previas. */
        val NONE = SurfaceGuides { flowOf(null) }
    }
}

/**
 * La superficie activa de cada proyecto, siempre al día (S042/RF-16, RF-18, RF-19, RF-27): la que marca el proyecto con su desplazamiento
 * vertical, o nada. Al cambiar de superficie, desactivarla o borrarla cierra la anterior y vacía su caché.
 */
class ActiveSurfaceProvider(
    private val repository: SurfaceRepository,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val cacheSize: Int = ActiveSurface.CACHE_TILES,
) : SurfaceGuides {
    /** La superficie activa del proyecto con sus cotas a demanda; null si no hay ninguna. Las teselas no se leen hasta que se piden. */
    fun active(projectId: String): Flow<ActiveSurface?> =
        combine(repository.activeSurfaceId(projectId), repository.surfaces(projectId)) { id, list -> list.firstOrNull { it.id == id } }
            .distinctUntilChanged()
            .transformLatest { surface ->
                if (surface == null) {
                    emit(null)
                    return@transformLatest
                }
                val active = ActiveSurface(surface, repository.cells(surface.id), repository::tile, ioDispatcher, cacheSize)
                try {
                    emit(active)
                    awaitCancellation()
                } finally {
                    active.close()
                }
            }

    /** La superficie activa con el desplazamiento vertical del proyecto, lista para el replanteo (S042/RF-24 a RF-26). */
    override fun guide(projectId: String): Flow<SurfaceGuide?> =
        combine(active(projectId), repository.verticalOffset(projectId)) { active, offset ->
            active?.let { SurfaceGuide(it.surface, it, offset) }
        }
}
