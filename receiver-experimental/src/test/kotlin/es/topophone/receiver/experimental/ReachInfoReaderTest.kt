package es.topophone.receiver.experimental

import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import javax.net.SocketFactory
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Before
import org.junit.Test

/** S044/RF-11 (qué se lee) y RF-15 (compatibilidad por partes, sin escrituras); plan D6, A1. */
class ReachInfoReaderTest {

    private lateinit var server: MockWebServer
    private val routes = mutableMapOf<String, MockResponse>()

    @Before
    fun start() {
        server = MockWebServer().also {
            it.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = routes[request.path] ?: MockResponse().setResponseCode(404)
            }
            it.start()
        }
        routes["/info"] = MockResponse().setBody(ReachFixtures.text("info.json"))
        routes["/configuration"] = MockResponse().setBody(ReachFixtures.text("configuration.json"))
    }

    @After
    fun stop() {
        server.shutdown()
    }

    private fun reader(timeoutMs: Long = ReachHttpClient.CALL_TIMEOUT_MS) =
        ReachInfoReader(ReachHttpClient(ReachEndpoint(server.hostName, server.port), SocketFactory.getDefault(), timeoutMs))

    @Test
    fun `S044 RF-11 con la forma esperada se lee modelo, serie, firmware, rol y altura de antena`() = runBlocking<Unit> {
        val reading = reader().read()

        reading.info shouldBe ReachInfo(
            model = "Reach RS3",
            serial = "SINTETICO0001",
            firmware = "v0.0-sintetico",
            role = "rover",
            antennaHeightM = 1.8,
        )
        reading.identity.shouldBeInstanceOf<ReachPart.Ok<ReachIdentity>>()
        reading.device.shouldBeInstanceOf<ReachPart.Ok<ReachDeviceSettings>>()
    }

    @Test
    fun `S044 RF-15 la lectura solo hace peticiones GET`() = runBlocking<Unit> {
        reader().read()

        server.takeRequest().method shouldBe "GET"
        server.takeRequest().method shouldBe "GET"
        server.requestCount shouldBe 2
    }

    @Test
    fun `S044 RF-15 el rol es opcional y su ausencia no hace incompatible`() = runBlocking<Unit> {
        routes["/configuration"] = MockResponse().setBody("""{"configuration":{"device":{"antenna_height":0.0}}}""")

        val reading = reader().read()

        reading.info shouldBe ReachInfo("Reach RS3", "SINTETICO0001", "v0.0-sintetico", role = null, antennaHeightM = 0.0)
    }

    @Test
    fun `S044 RF-15 un campo ausente o de otro tipo marca solo la parte afectada`() = runBlocking<Unit> {
        routes["/info"] = MockResponse().setBody(ReachFixtures.text("info-incompatible.json"))

        val reading = reader().read()

        val identity = reading.identity.shouldBeInstanceOf<ReachPart.Incompatible>()
        identity.part shouldBe ReachPartId.IDENTITY
        identity.reason shouldBe "falta device.serial_number"
        reading.device.shouldBeInstanceOf<ReachPart.Ok<ReachDeviceSettings>>()
        reading.info shouldBe null
    }

    @Test
    fun `S044 RF-15 la altura de antena que no es un numero hace incompatible los ajustes del dispositivo`() = runBlocking<Unit> {
        routes["/configuration"] = MockResponse().setBody(ReachFixtures.text("configuration-incompatible.json"))

        val reading = reader().read()

        reading.identity.shouldBeInstanceOf<ReachPart.Ok<ReachIdentity>>()
        val device = reading.device.shouldBeInstanceOf<ReachPart.Incompatible>()
        device.part shouldBe ReachPartId.DEVICE
        device.reason shouldBe "configuration.device.antenna_height no es un numero"
        reading.info shouldBe null
    }

    @Test
    fun `S044 RF-15 el firmware que no es texto no vale`() = runBlocking<Unit> {
        routes["/info"] = MockResponse().setBody("""{"device":{"model":"Reach RS3","serial_number":"X","app_version":33}}""")

        reader().read().identity shouldBe ReachPart.Incompatible(ReachPartId.IDENTITY, "device.app_version no es un texto")
    }

    @Test
    fun `S044 RF-15 una respuesta que no es JSON es incompatible`() = runBlocking<Unit> {
        routes["/info"] = MockResponse().setBody("<html>otra cosa</html>")

        reader().read().identity shouldBe ReachPart.Incompatible(ReachPartId.IDENTITY, "la respuesta no es JSON")
    }

    @Test
    fun `S044 RF-15 un JSON con otra forma raiz es incompatible`() = runBlocking<Unit> {
        routes["/info"] = MockResponse().setBody("[1,2,3]")

        reader().read().identity shouldBe ReachPart.Incompatible(ReachPartId.IDENTITY, "falta device")
    }

    @Test
    fun `S044 RF-15 sin respuesta una parte es incompatible y la otra sigue`() = runBlocking<Unit> {
        routes.remove("/info")

        val reading = reader().read()

        reading.identity shouldBe ReachPart.Incompatible(ReachPartId.IDENTITY, "sin respuesta")
        reading.device.shouldBeInstanceOf<ReachPart.Ok<ReachDeviceSettings>>()
        reading.info shouldBe null
    }

    @Test
    fun `S044 RF-15 el motivo de incompatibilidad nunca lleva valores del receptor`() = runBlocking<Unit> {
        routes["/configuration"] = MockResponse().setBody("""{"configuration":{"device":{"antenna_height":"clave-de-mentira"}}}""")

        val device = reader().read().device.shouldBeInstanceOf<ReachPart.Incompatible>()

        device.reason.contains("clave-de-mentira") shouldBe false
    }
}
