package es.topophone.app.ui.map

import android.os.Handler
import es.topophone.core.formats.wms.WmsRequests
import java.io.IOException
import java.io.InputStream
import okhttp3.CacheControl
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/** Quién ejecuta algo en el hilo principal, ahora o más tarde: el `Handler` del mapa y, en los tests, un doble. */
interface WmsScheduler {
    fun post(task: Runnable)

    fun postDelayed(delayMs: Long, task: Runnable)

    fun cancel(task: Runnable)
}

/** [WmsScheduler] sobre el `Handler` del hilo principal. */
internal class HandlerWmsScheduler(private val handler: Handler) : WmsScheduler {
    override fun post(task: Runnable) {
        handler.post(task)
    }

    override fun postDelayed(delayMs: Long, task: Runnable) {
        handler.postDelayed(task, delayMs)
    }

    override fun cancel(task: Runnable) = handler.removeCallbacks(task)
}

/** La zona visible del mapa, en grados, con el tamaño en píxeles de la vista que la muestra. */
data class VisibleArea(val south: Double, val west: Double, val north: Double, val east: Double, val widthPx: Int, val heightPx: Int)

/** La imagen de la capa [layerId] ya decodificada, con la [area] con que se pidió, que es la que cubren sus cuatro esquinas. */
data class WmsLoadedImage<T : Any>(val layerId: String, val area: VisibleArea, val image: T)

/**
 * Las capas WMS en «una imagen»: al detenerse el mapa se pide **una** imagen `GetMap` por capa con la zona visible en EPSG:3857, sin
 * teselas ni descargas masivas, como el Catastro de S009 (S036/RF-17, RF-29, A4 del plan). Una petición nueva anula la anterior, que se
 * cancela y cuyo resultado, si llega, no se entrega. No se guarda nada en la caché (RF-32).
 *
 * Los fallos de conexión los cuenta el interceptor del cliente del mapa ([NetworkFailureMonitor]) y una petición cancelada no es un fallo
 * de conexión, así que aquí un fallo no hace nada más: la capa se queda como estaba y el aviso de falta de conexión lo da el mapa
 * (RF-31). Solo se usa desde el hilo principal; [decode] corre en el hilo de la respuesta y [deliver] en el principal.
 */
class WmsOneImageLoader<T : Any>(
    private val client: OkHttpClient,
    private val scheduler: WmsScheduler,
    private val decode: (InputStream) -> T?,
    private val deliver: (WmsLoadedImage<T>) -> Unit,
) {
    private var generation = 0
    private var pending: Runnable? = null
    private val calls = mutableListOf<Call>()

    /**
     * Pide las capas en «una imagen» de [layers] para [area] cuando pasen [delayMs] sin otra petición; las capas por teselas no se
     * piden aquí. Sin capas en «una imagen» solo anula lo anterior.
     */
    fun request(area: VisibleArea, layers: List<WmsPlanLayer>, delayMs: Long = IDLE_DELAY_MS) {
        cancel()
        val images = layers.mapNotNull { layer -> (layer.source as? WmsLayerSource.Image)?.let { layer.id to it } }
        if (images.isEmpty() || area.widthPx <= 0 || area.heightPx <= 0) return
        val mine = generation
        val start = Runnable {
            pending = null
            images.forEach { (id, source) -> start(mine, id, source, area) }
        }
        pending = start
        if (delayMs <= 0) start.run() else scheduler.postDelayed(delayMs, start)
    }

    /** Anula lo programado y cancela lo que está en vuelo; nada de ello se entregará. */
    fun cancel() {
        generation++
        pending?.let(scheduler::cancel)
        pending = null
        calls.forEach(Call::cancel)
        calls.clear()
    }

    private fun start(mine: Int, layerId: String, source: WmsLayerSource.Image, area: VisibleArea) {
        val url = WmsRequests.image(
            source.address,
            source.version,
            source.layerName,
            source.code,
            source.format,
            area.south,
            area.west,
            area.north,
            area.east,
            area.widthPx,
            area.heightPx,
        )
        val request = try {
            Request.Builder().url(url).cacheControl(CacheControl.Builder().noStore().build()).build()
        } catch (_: IllegalArgumentException) {
            return // Una dirección que OkHttp no admite no se puede pedir.
        }
        val call = client.newCall(request)
        calls += call
        call.enqueue(
            object : Callback {
                override fun onFailure(call: Call, e: IOException) = Unit

                override fun onResponse(call: Call, response: Response) {
                    val image = response.use { if (it.isSuccessful) it.body?.byteStream()?.let(decode) else null } ?: return
                    scheduler.post { if (generation == mine) deliver(WmsLoadedImage(layerId, area, image)) }
                }
            },
        )
    }

    companion object {
        /** Espera tras detenerse el mapa antes de pedir las imágenes, como el Catastro. */
        const val IDLE_DELAY_MS = 500L
    }
}
