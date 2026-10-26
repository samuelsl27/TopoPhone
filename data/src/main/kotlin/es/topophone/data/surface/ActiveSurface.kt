package es.topophone.data.surface

import es.topophone.core.model.surface.Surface
import es.topophone.core.model.surface.SurfaceGuide
import es.topophone.core.model.surface.SurfaceHeight
import es.topophone.core.model.surface.SurfaceHeightSource
import es.topophone.core.model.surface.SurfaceTile
import es.topophone.core.survey.TinDirectory
import es.topophone.core.survey.TinElevation
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
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

    // La lectura de la caché no espera a nadie: [heightAt] se llama con cada época y no puede quedarse esperando a un hilo de carga que
    // tiene el cerrojo (en Windows, un cuanto del planificador son ~16 ms). Solo escribir (guardar una tesela y expulsar la más antigua) lo usa.
    private class Entry(val tile: SurfaceTile) {
        @Volatile var lastUse = 0L
    }

    private val writeLock = Any()
    private val cache = ConcurrentHashMap<Int, Entry>()
    private val loading: MutableSet<Int> = ConcurrentHashMap.newKeySet()
    private val clock = AtomicLong()
    private val closed = AtomicBoolean(false)

    // Lo que [heightAt] pide a quien lanza las lecturas: solo una señal sin espera. Lanzar una corrutina al pool de E/S cuesta del orden de
    // milisegundos, y a la consulta no le corresponde pagarlo (RNF-T13: menos de 16 ms).
    private sealed interface Request {
        data class Load(val index: Int) : Request

        data object Neighbours : Request
    }

    private val requests = Channel<Request>(Channel.UNLIMITED)

    @Volatile private var tipEasting = 0.0

    @Volatile private var tipNorthing = 0.0

    @Volatile private var lastCell = -1

    init {
        scope.launch { for (request in requests) handle(request) }
    }

    /** Cuántas teselas hay en memoria ahora. */
    val cachedTiles: Int get() = cache.size

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
                announce(easting, northing, candidates, urgent = false)
                return SurfaceHeight.Value(it)
            }
        }
        if (!missing) {
            announce(easting, northing, candidates, urgent = false)
            return SurfaceHeight.Outside
        }
        for (index in candidates) if (!cache.containsKey(index)) requests.trySend(Request.Load(index))
        announce(easting, northing, candidates, urgent = true)
        return SurfaceHeight.Loading
    }

    private fun cached(index: Int): SurfaceTile? = cache[index]?.also { it.lastUse = clock.incrementAndGet() }?.tile

    /** Avisa de por dónde va la punta para que se pidan las teselas vecinas, solo si cambió de celda o falta alguna tesela. */
    private fun announce(easting: Double, northing: Double, candidates: IntArray, urgent: Boolean) {
        tipEasting = easting
        tipNorthing = northing
        if (urgent || candidates[0] != lastCell) {
            lastCell = candidates[0]
            requests.trySend(Request.Neighbours)
        }
    }

    private fun handle(request: Request) {
        when (request) {
            is Request.Load -> launchLoad(request.index)
            Request.Neighbours -> {
                // Sin pasar de la mitad de la caché de una vez: lo ya cargado no se expulsa por precargar.
                var budget = cacheSize / 2
                for (index in directory.near(tipEasting, tipNorthing, preloadRadiusM)) {
                    if (budget == 0) break
                    if (launchLoad(index)) budget--
                }
            }
        }
    }

    /** Guarda una tesela y, si la caché pasa de su tamaño, expulsa las usadas hace más. */
    private fun store(index: Int, tile: SurfaceTile) = synchronized(writeLock) {
        cache[index] = Entry(tile).also { it.lastUse = clock.incrementAndGet() }
        while (cache.size > cacheSize) {
            val eldest = cache.entries.minByOrNull { it.value.lastUse } ?: break
            cache.remove(eldest.key)
        }
    }

    /** Lanza la lectura de la tesela [index] si no está en memoria ni se está leyendo; devuelve si la lanzó. */
    private fun launchLoad(index: Int): Boolean {
        if (closed.get()) return false
        if (cache.containsKey(index) || !loading.add(index)) return false
        scope.launch {
            try {
                val tile = loadTile(cells[index].tileId)
                if (tile != null) store(index, tile)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Una tesela ilegible no se reintenta en cada época: queda como vacía (sin cota) en lugar de cargando para siempre.
                store(index, EMPTY_TILE)
            } finally {
                loading.remove(index)
            }
        }
        return true
    }

    /** Libera la caché y cancela las lecturas en curso. */
    fun close() {
        if (!closed.compareAndSet(false, true)) return
        requests.close()
        scope.cancel()
        cache.clear()
        loading.clear()
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
