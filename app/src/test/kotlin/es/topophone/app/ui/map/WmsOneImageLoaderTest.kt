package es.topophone.app.ui.map

import es.topophone.core.formats.wms.WmsRequests
import es.topophone.core.model.wms.WmsAddress
import es.topophone.core.model.wms.WmsAddressResult
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.io.IOException
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import okhttp3.Dispatcher
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Test

/** Las capas en «una imagen»: al detenerse el mapa, una sola petición por capa de la zona visible, que se cancela y no se guarda (S036/RF-16, RF-17, RF-29, RF-31, RF-32). */
class WmsOneImageLoaderTest {

    private val area = VisibleArea(south = 39.0, west = -0.5, north = 39.1, east = -0.4, widthPx = 1000, heightPx = 2000)
    private val address = (WmsAddress.parse("https://idecan1.grafcan.es/ServicioWMS/OrtoUrb") as WmsAddressResult.Valid).address

    private fun image(id: String, layer: String = id, version: String = "1.1.1") =
        WmsPlanLayer(id, 1f, WmsLayerSource.Image(address, version, layer, "EPSG:3857", "image/jpeg"), "© $id")

    private fun tiles(id: String) = WmsPlanLayer(id, 1f, WmsLayerSource.Tiles("https://$id.es/wms?BBOX={bbox-epsg-3857}"), "© $id")

    /** El hilo principal a mano: lo que se pide con retraso espera a [runDelayed] y lo que llega de la red a [drain]. */
    private class ManualScheduler : WmsScheduler {
        val delayed = mutableListOf<Pair<Long, Runnable>>()
        private val posted = ConcurrentLinkedQueue<Runnable>()

        override fun postDelayed(delayMs: Long, task: Runnable) {
            delayed += delayMs to task
        }

        override fun post(task: Runnable) {
            posted += task
        }

        override fun cancel(task: Runnable) {
            delayed.removeAll { it.second === task }
            posted.remove(task)
        }

        fun runDelayed() {
            val tasks = delayed.toList()
            delayed.clear()
            tasks.forEach { it.second.run() }
        }

        fun drain() {
            while (true) (posted.poll() ?: return).run()
        }
    }

    /** Un ejecutor que hace el trabajo en el mismo hilo que lo pide, para que las respuestas del cliente falso lleguen en orden. */
    private class DirectExecutor : AbstractExecutorService() {
        override fun execute(command: Runnable) = command.run()

        override fun shutdown() = Unit

        override fun shutdownNow(): MutableList<Runnable> = mutableListOf()

        override fun isShutdown() = false

        override fun isTerminated() = false

        override fun awaitTermination(timeout: Long, unit: TimeUnit) = true
    }

    private fun answer(chain: Interceptor.Chain, code: Int, body: String): Response = Response.Builder()
        .request(chain.request())
        .protocol(Protocol.HTTP_1_1)
        .code(code)
        .message("respuesta")
        .body(body.toResponseBody("image/jpeg".toMediaType()))
        .build()

    private class Setup(
        val requests: MutableList<Request>,
        val scheduler: ManualScheduler,
        val delivered: MutableList<WmsLoadedImage<String>>,
    )

    private fun loader(
        code: Int = 200,
        body: String = "imagen",
        decode: (String) -> String? = {
            it
        },
    ): Pair<WmsOneImageLoader<String>, Setup> {
        val requests = mutableListOf<Request>()
        val scheduler = ManualScheduler()
        val delivered = mutableListOf<WmsLoadedImage<String>>()
        val client = OkHttpClient.Builder()
            .dispatcher(Dispatcher(DirectExecutor()))
            .addInterceptor { chain ->
                requests += chain.request()
                answer(chain, code, body)
            }
            .build()
        val setup = Setup(requests, scheduler, delivered)
        return WmsOneImageLoader(client, scheduler, { input -> decode(input.readBytes().decodeToString()) }, { delivered += it }) to setup
    }

    @Test
    fun `S036 RF-17 pide una imagen por capa tras 500 ms de mapa quieto y no antes`() {
        val (loader, setup) = loader()

        loader.request(area, listOf(image("a")))

        setup.requests.size shouldBe 0
        setup.scheduler.delayed.map { it.first } shouldBe listOf(500L)
        setup.scheduler.runDelayed()
        setup.requests.size shouldBe 1
    }

    @Test
    fun `S036 RF-17 el cuadro es la zona visible en EPSG 3857 y el lado mayor no pasa de 2048`() {
        val (loader, setup) = loader()

        loader.request(area.copy(widthPx = 4096, heightPx = 2048), listOf(image("a", layer = "OU")))
        setup.scheduler.runDelayed()

        val expected = WmsRequests.image(address, "1.1.1", "OU", "EPSG:3857", "image/jpeg", 39.0, -0.5, 39.1, -0.4, 4096, 2048)
        setup.requests.single().url.toString() shouldBe expected
        expected shouldContain "WIDTH=2048&HEIGHT=1024"
        expected shouldContain "SRS=EPSG:3857"
        expected shouldContain "REQUEST=GetMap"
    }

    @Test
    fun `S036 RF-17 una peticion nueva antes de los 500 ms deja solo la ultima`() {
        val (loader, setup) = loader()

        loader.request(area, listOf(image("a")))
        loader.request(area.copy(north = 39.2), listOf(image("a")))
        setup.scheduler.runDelayed()

        val expected = WmsRequests.image(address, "1.1.1", "a", "EPSG:3857", "image/jpeg", 39.0, -0.5, 39.2, -0.4, 1000, 2000)
        setup.requests.map { it.url.toString() } shouldBe listOf(expected)
    }

    @Test
    fun `S036 RF-17 cada capa en una imagen se pide por separado y llega con la zona con que se pidio`() {
        val (loader, setup) = loader()

        loader.request(area, listOf(image("a"), tiles("t"), image("b")))
        setup.scheduler.runDelayed()
        setup.scheduler.drain()

        setup.requests.size shouldBe 2
        setup.delivered shouldBe listOf(WmsLoadedImage("a", area, "imagen"), WmsLoadedImage("b", area, "imagen"))
    }

    @Test
    fun `S036 RF-29 una capa por teselas no se pide aqui y sin capas en una imagen no se programa nada`() {
        val (loader, setup) = loader()

        loader.request(area, listOf(tiles("t")))
        loader.request(area, emptyList())

        setup.scheduler.delayed shouldBe emptyList()
        setup.requests.size shouldBe 0
    }

    @Test
    fun `S036 RF-17 con retraso cero la peticion sale en el acto`() {
        val (loader, setup) = loader()

        loader.request(area, listOf(image("a")), delayMs = 0)

        setup.requests.size shouldBe 1
    }

    @Test
    fun `S036 RF-32 la peticion lleva la orden de no guardar`() {
        val (loader, setup) = loader()

        loader.request(area, listOf(image("a")), delayMs = 0)

        setup.requests.single().header("Cache-Control") shouldBe "no-store"
    }

    @Test
    fun `S036 RF-31 una respuesta con error o que no es una imagen no se entrega ni rompe`() {
        val (failing, failingSetup) = loader(code = 503, body = "error")
        failing.request(area, listOf(image("a")), delayMs = 0)
        failingSetup.scheduler.drain()
        failingSetup.delivered shouldBe emptyList()

        val (notImage, notImageSetup) = loader(decode = { null })
        notImage.request(area, listOf(image("a")), delayMs = 0)
        notImageSetup.scheduler.drain()
        notImageSetup.delivered shouldBe emptyList()
    }

    @Test
    fun `S036 RF-17 cancelar quita lo programado y lo que llega despues no se entrega`() {
        val (loader, setup) = loader()

        loader.request(area, listOf(image("a")))
        loader.cancel()
        setup.scheduler.runDelayed()

        setup.requests.size shouldBe 0
        setup.delivered shouldBe emptyList()
    }

    @Test
    fun `S036 RF-17 una respuesta de una peticion que ya no es la ultima no se entrega`() {
        val (loader, setup) = loader()

        loader.request(area, listOf(image("a")), delayMs = 0)
        loader.request(area.copy(north = 39.2), listOf(image("a")), delayMs = 0)
        setup.scheduler.drain()

        setup.requests.size shouldBe 2
        setup.delivered.map { it.area.north } shouldBe listOf(39.2)
    }

    @Test
    fun `S036 RF-31 cancelar una peticion en vuelo no cuenta como fallo de conexion ni entrega nada`() {
        val monitor = NetworkFailureMonitor()
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val scheduler = ManualScheduler()
        val delivered = mutableListOf<WmsLoadedImage<String>>()
        val client = OkHttpClient.Builder()
            .addInterceptor(monitor.interceptor)
            .addInterceptor { chain ->
                started.countDown()
                release.await(5, TimeUnit.SECONDS)
                if (chain.call().isCanceled()) throw IOException("Canceled")
                answer(chain, 200, "imagen")
            }
            .build()
        val loader = WmsOneImageLoader<String>(client, scheduler, { it.readBytes().decodeToString() }, { delivered += it })

        loader.request(area, listOf(image("a")), delayMs = 0)
        started.await(5, TimeUnit.SECONDS) shouldBe true
        loader.cancel()
        release.countDown()
        client.dispatcher.executorService.shutdown()
        client.dispatcher.executorService.awaitTermination(5, TimeUnit.SECONDS) shouldBe true
        scheduler.drain()

        monitor.offline.value shouldBe false
        delivered shouldBe emptyList()
    }

    @Test
    fun `S036 RF-31 una peticion que no llega al servidor si cuenta como fallo de conexion`() {
        val monitor = NetworkFailureMonitor()
        val scheduler = ManualScheduler()
        val client = OkHttpClient.Builder()
            .dispatcher(Dispatcher(DirectExecutor()))
            .addInterceptor(monitor.interceptor)
            .addInterceptor { throw java.net.UnknownHostException("sin red") }
            .build()
        val loader = WmsOneImageLoader<String>(client, scheduler, { it.readBytes().decodeToString() }, { })

        loader.request(area, listOf(image("a")), delayMs = 0)

        monitor.offline.value shouldBe true
    }
}
