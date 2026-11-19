package es.topophone.receiver.experimental

import io.kotest.matchers.shouldBe
import java.util.concurrent.TimeUnit
import javax.net.SocketFactory
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test

/** S044/RF-23 (solo la red del receptor, solo direcciones privadas) y RF-15 (sin respuesta); plan D5. */
class ReachHttpClientTest {

    private lateinit var server: MockWebServer

    @Before
    fun start() {
        server = MockWebServer().also { it.start() }
    }

    @After
    fun stop() {
        server.shutdown()
    }

    private fun client(timeoutMs: Long = ReachHttpClient.CALL_TIMEOUT_MS, factory: SocketFactory = SocketFactory.getDefault()) =
        ReachHttpClient(ReachEndpoint(server.hostName, server.port), factory, timeoutMs)

    @Test
    fun `S044 RF-11 una lectura devuelve el cuerpo de la respuesta`() = runBlocking<Unit> {
        server.enqueue(MockResponse().setBody(ReachFixtures.text("info.json")))

        val result = client().get("/info")

        result shouldBe ReachResult.Ok(ReachFixtures.text("info.json"))
        server.takeRequest().path shouldBe "/info"
    }

    @Test
    fun `S044 RF-18 una escritura envia el cuerpo JSON tal cual`() = runBlocking<Unit> {
        server.enqueue(MockResponse().setBody("{}"))

        client().post("/configuration/device", """{"antenna_height":1.8}""") shouldBe ReachResult.Ok("{}")

        val request = server.takeRequest()
        request.method shouldBe "POST"
        request.path shouldBe "/configuration/device"
        request.body.readUtf8() shouldBe """{"antenna_height":1.8}"""
        request.getHeader("Content-Type")!!.startsWith("application/json") shouldBe true
    }

    @Test
    fun `S044 RF-23 una redireccion no se sigue ni siquiera a la misma direccion`() = runBlocking<Unit> {
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", server.url("/otra").toString()))
        server.enqueue(MockResponse().setBody("no debe leerse"))

        client().get("/info") shouldBe ReachResult.Failed(ReachFailure.REDIRECT)

        server.requestCount shouldBe 1
    }

    @Test
    fun `S044 RF-23 una peticion a otro host se rechaza antes de abrir ninguna conexion`() = runBlocking<Unit> {
        val other = ReachEndpoint("192.168.77.9", 80)

        val result = client().getUrl(other.url("/info"))

        result shouldBe ReachResult.Failed(ReachFailure.WRONG_HOST)
        server.requestCount shouldBe 0
    }

    @Test
    fun `S044 RF-15 un error HTTP no es una lectura`() = runBlocking<Unit> {
        server.enqueue(MockResponse().setResponseCode(500).setBody("secreto del servidor"))

        client().get("/info") shouldBe ReachResult.Failed(ReachFailure.HTTP_ERROR)
    }

    @Test
    fun `S044 RF-15 sin respuesta en el tiempo limite es un fallo y no cuelga`() = runBlocking<Unit> {
        server.enqueue(MockResponse().setBody("{}").setHeadersDelay(2, TimeUnit.SECONDS))

        val started = System.nanoTime()
        val result = client(timeoutMs = 200).get("/info")

        result shouldBe ReachResult.Failed(ReachFailure.NO_RESPONSE)
        ((System.nanoTime() - started) / 1_000_000 < 1_500) shouldBe true
    }

    @Test
    fun `S044 RF-15 un servidor apagado es un fallo`() = runBlocking<Unit> {
        val client = client()
        server.shutdown()

        client.get("/info") shouldBe ReachResult.Failed(ReachFailure.NO_RESPONSE)
    }

    @Test
    fun `S044 RF-11 el tiempo limite por defecto es de 3 segundos`() {
        ReachHttpClient.CALL_TIMEOUT_MS shouldBe 3_000L
    }

    @Test
    fun `S044 RF-23 solo se aceptan IP privadas y el puerto es el 80`() {
        listOf("8.8.8.8", "reach.local", "127.0.0.1", "", "10.0.0").forEach { ReachEndpoint.parse(it) shouldBe null }

        val endpoint = ReachEndpoint.parse("192.168.42.1")!!
        endpoint.host shouldBe "192.168.42.1"
        endpoint.port shouldBe 80
        ReachEndpoint.parse("192.168.42.1:8080")!!.port shouldBe 80
    }

    @Test
    fun `S044 RF-23 la URL se construye con el host y el puerto del receptor`() {
        val url = ReachEndpoint("192.168.42.1", 80).url("/configuration/logging/logs")

        url.host shouldBe "192.168.42.1"
        url.encodedPath shouldBe "/configuration/logging/logs"
        url.scheme shouldBe "http"
    }
}
