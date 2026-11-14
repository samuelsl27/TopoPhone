package es.topophone.app.ui.offline

import es.topophone.core.model.offline.DownloadableSource
import es.topophone.core.model.offline.SourceKind
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Test

/** El cliente HTTP del mapa con las zonas sin conexión: los estilos generados y la caducidad larga de lo que se descarga (S037/RF-22, RF-23). */
class ZoneHttpTest {

    private val pnoa = DownloadableSource(SourceKind.PNOA, "Ortofoto PNOA")

    /** El «servidor»: contesta con [code] y las cabeceras de caducidad de los servicios reales, y cuenta las peticiones que le llegan. */
    private class Server(var code: Int = 200) : Interceptor {
        var requests = 0

        override fun intercept(chain: Interceptor.Chain): Response {
            requests++
            return Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(code)
                .message("test")
                .header("Cache-Control", "max-age=86400")
                .header("Expires", "Sun, 15 Nov 2026 11:24:15 GMT")
                .header("ETag", "\"abc\"")
                .body("tesela".toResponseBody())
                .build()
        }
    }

    private fun client(zone: ZoneHttp, server: Server) =
        OkHttpClient.Builder().addInterceptor(zone.interceptor).addInterceptor(server).build()

    private fun get(client: OkHttpClient, url: String): Response = client.newCall(Request.Builder().url(url).build()).execute()

    @Test
    fun `S037 RF-22 el estilo de una direccion nuestra se contesta sin llamar a la red`() {
        val server = Server()
        val response = get(client(ZoneHttp(), server), RegionStyles.styleUrl(pnoa, null))

        response.code shouldBe 200
        response.body!!.string() shouldContain "\"version\":8"
        server.requests shouldBe 0
    }

    @Test
    fun `S037 RF-22 una direccion nuestra que no se entiende da un 404 y tampoco llama a la red`() {
        val server = Server()

        get(client(ZoneHttp(), server), "https://topophone.invalid/offline/style?size=7").code shouldBe 404
        server.requests shouldBe 0
    }

    @Test
    fun `S037 RF-23 sin descarga en marcha las respuestas se dejan como las da el servidor`() {
        val response = get(client(ZoneHttp(), Server()), "https://tiles.example.org/1/2/3.png")

        response.header("Cache-Control") shouldBe "max-age=86400"
        response.header("Expires") shouldBe "Sun, 15 Nov 2026 11:24:15 GMT"
    }

    @Test
    fun `S037 RF-23 durante una descarga las respuestas correctas caducan al cabo de un ano y sin Expires`() {
        val zone = ZoneHttp().apply { setLongCache(true) }

        val response = get(client(zone, Server()), "https://tiles.example.org/1/2/3.png")

        response.header("Cache-Control") shouldBe "max-age=31536000"
        response.header("Expires") shouldBe null
        response.header("ETag") shouldBe "\"abc\""
        response.body!!.string() shouldBe "tesela"
    }

    @Test
    fun `S037 RF-23 un 304 de la revalidacion tambien renueva la caducidad`() {
        val zone = ZoneHttp().apply { setLongCache(true) }

        get(client(zone, Server(code = 304)), "https://tiles.example.org/1/2/3.png").header("Cache-Control") shouldBe "max-age=31536000"
    }

    @Test
    fun `S037 RF-23 un error del servidor no se guarda un ano`() {
        val zone = ZoneHttp().apply { setLongCache(true) }

        listOf(404, 500, 503).forEach { code ->
            get(client(zone, Server(code = code)), "https://tiles.example.org/1/2/3.png").header("Cache-Control") shouldBe "max-age=86400"
        }
    }

    @Test
    fun `S037 RF-23 al acabar la descarga las respuestas vuelven a como las da el servidor`() {
        val zone = ZoneHttp()
        val client = client(zone, Server())

        zone.setLongCache(true)
        get(client, "https://tiles.example.org/1.png").header("Cache-Control") shouldBe "max-age=31536000"
        zone.setLongCache(false)

        get(client, "https://tiles.example.org/2.png").header("Cache-Control") shouldBe "max-age=86400"
    }
}
