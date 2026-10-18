package es.topophone.app.ui.map

import io.kotest.matchers.shouldBe
import java.io.IOException
import java.net.UnknownHostException
import okhttp3.Interceptor
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Test

/** Mapa sin conexión: caché y aviso (S009/RF-13, RF-15). */
class NetworkFailureMonitorTest {

    private val monitor = NetworkFailureMonitor()
    private var network: Interceptor = Interceptor { chain -> ok(chain.request()) }
    private val client = MapHttpClient.create(versionName = "1.0.0", monitor = monitor).newBuilder()
        .addInterceptor { chain -> network.intercept(chain) }
        .build()

    private fun ok(request: Request) =
        Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK").body("".toResponseBody()).build()

    private fun fetch() {
        runCatching { client.newCall(Request.Builder().url("https://www.ign.es/wmts/pnoa-ma").build()).execute().close() }
    }

    @Test
    fun `S009 RF-15 si falla la conexion el mapa queda sin conexion hasta que llega una respuesta`() {
        network = Interceptor { throw UnknownHostException("www.ign.es") }
        fetch()
        monitor.offline.value shouldBe true

        network = Interceptor { chain -> ok(chain.request()) }
        fetch()
        monitor.offline.value shouldBe false
    }

    @Test
    fun `S009 RF-15 cancelar una peticion, como la del Catastro al mover el mapa, no es estar sin conexion`() {
        network = Interceptor { throw IOException("Canceled") }

        fetch()

        monitor.offline.value shouldBe false
    }

    @Test
    fun `S009 RF-13 la cache del mapa base guarda hasta 200 MB`() {
        MapOptions.AMBIENT_CACHE_BYTES shouldBe 200L * 1024 * 1024
    }
}
