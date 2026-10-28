package es.topophone.data.surface

import es.topophone.core.model.project.HeightSystem
import es.topophone.core.model.project.ProjectCrs
import es.topophone.core.model.surface.RasterInfo
import es.topophone.core.model.surface.RasterTile
import es.topophone.core.model.surface.Surface
import es.topophone.core.model.surface.SurfaceHeight
import es.topophone.core.model.surface.SurfaceHeightSource
import es.topophone.core.survey.RasterElevation
import es.topophone.core.survey.RasterTileSource
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/** Lo que un ráster necesita saber del proyecto para dar su cota: el sistema de coordenadas y el de alturas (S045/RF-9, RF-10). */
data class RasterProjectContext(val crs: ProjectCrs, val heightSystem: HeightSystem)

/**
 * Un ráster activo en memoria (S045, plan A4). Al crearlo no se lee ninguna celda: los bloques se leen a demanda con [loadTile] y se
 * guardan en una caché de [cacheSize] bloques, cada uno de 64 kB (float32) o 128 kB (float64).
 *
 * [heightAt] no bloquea ni espera a la base de datos (se llama con cada época, a 10 Hz): si falta un bloque de los que necesita
 * responde [SurfaceHeight.Loading], lo pide en segundo plano y pide también los vecinos por si la punta se mueve hacia ellos. Nunca
 * presenta una posición sin cargar como [SurfaceHeight.Outside]. Usa [RasterElevation] para la geometría, el cambio de sistema y la
 * altura (RF-9, RF-10, RF-21 a RF-23).
 */
class ActiveRaster(
    val surface: Surface,
    context: RasterProjectContext,
    private val loadTile: suspend (tileColumn: Int, tileRow: Int) -> RasterTile?,
    ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val cacheSize: Int = CACHE_TILES,
) : SurfaceHeightSource {
    private val info: RasterInfo = requireNotNull(surface.raster) { "La superficie no es un ráster" }
    private val tileColumns = info.tileColumns
    private val tileRows = info.tileRows
    private val scope = CoroutineScope(SupervisorJob() + ioDispatcher)

    // Como en ActiveSurface: la lectura de la caché no espera a nadie, y solo guardar un bloque usa el cerrojo.
    private class Entry(val tile: RasterTile) {
        @Volatile var lastUse = 0L
    }

    private val writeLock = Any()
    private val cache = ConcurrentHashMap<Int, Entry>()
    private val loading: MutableSet<Int> = ConcurrentHashMap.newKeySet()
    private val clock = AtomicLong()
    private val closed = AtomicBoolean(false)

    // Lo que [heightAt] pide a quien lanza las lecturas: una señal sin espera, para que la consulta no pague el coste de lanzar una
    // corrutina (RNF-T13: menos de 16 ms).
    private sealed interface Request {
        data class Load(val index: Int) : Request

        data class Neighbours(val index: Int) : Request
    }

    private val requests = Channel<Request>(Channel.UNLIMITED)

    @Volatile private var lastTile = -1

    private val elevation =
        RasterElevation(context.crs, context.heightSystem, surface, RasterTileSource { column, row -> tile(column, row) })

    init {
        scope.launch { for (request in requests) handle(request) }
    }

    /** Cuántos bloques hay en memoria ahora. */
    val cachedTiles: Int get() = cache.size

    override fun heightAt(easting: Double, northing: Double): SurfaceHeight {
        firstTileOfQuery = -1
        val result = elevation.heightAt(easting, northing)
        val current = firstTileOfQuery
        if (current >= 0 && current != lastTile) {
            lastTile = current
            requests.trySend(Request.Neighbours(current))
        }
        return result
    }

    /** El primer bloque que pidió la consulta en curso: el de la celda de la punta; no se usa fuera de [heightAt]. */
    private var firstTileOfQuery = -1

    /** El bloque en memoria, o null si falta (y entonces lo pide); es el origen de bloques de [RasterElevation]. */
    private fun tile(column: Int, row: Int): RasterTile? {
        val index = row * tileColumns + column
        if (firstTileOfQuery < 0) firstTileOfQuery = index
        val entry = cache[index]
        if (entry != null) {
            entry.lastUse = clock.incrementAndGet()
            return entry.tile
        }
        requests.trySend(Request.Load(index))
        return null
    }

    private fun handle(request: Request) {
        when (request) {
            is Request.Load -> launchLoad(request.index)
            is Request.Neighbours -> {
                // Sin pasar de la mitad de la caché de una vez: lo ya cargado no se expulsa por precargar.
                var budget = cacheSize / 2
                val column = request.index % tileColumns
                val row = request.index / tileColumns
                for (dr in -1..1) {
                    for (dc in -1..1) {
                        val c = column + dc
                        val r = row + dr
                        if (budget == 0 || c !in 0 until tileColumns || r !in 0 until tileRows) continue
                        if (launchLoad(r * tileColumns + c)) budget--
                    }
                }
            }
        }
    }

    /** Guarda un bloque y, si la caché pasa de su tamaño, expulsa los usados hace más. */
    private fun store(index: Int, tile: RasterTile) = synchronized(writeLock) {
        cache[index] = Entry(tile).also { it.lastUse = clock.incrementAndGet() }
        while (cache.size > cacheSize) {
            val eldest = cache.entries.minByOrNull { it.value.lastUse } ?: break
            cache.remove(eldest.key)
        }
    }

    /** Lanza la lectura del bloque [index] si no está en memoria ni se está leyendo; devuelve si la lanzó. */
    private fun launchLoad(index: Int): Boolean {
        if (closed.get()) return false
        if (cache.containsKey(index) || !loading.add(index)) return false
        scope.launch {
            try {
                val tile = loadTile(index % tileColumns, index / tileColumns)
                // Un bloque que no está en la base de datos no se reintenta en cada época: queda como vacío (sin cota).
                store(index, tile ?: EMPTY_TILE)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
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
        /** Bloques en memoria: de 64 kB cada uno (128 kB en float64), unos pocos MB. */
        const val CACHE_TILES = 64

        private val EMPTY_TILE = RasterTile.ofFloats(FloatArray(RasterTile.CELLS) { Float.NaN })
    }
}
