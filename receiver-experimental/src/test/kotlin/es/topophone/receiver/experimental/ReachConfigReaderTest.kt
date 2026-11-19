package es.topophone.receiver.experimental

import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import javax.net.SocketFactory
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test

/** S044/RF-14 (ajustes de posicionamiento y de salida en solo lectura), RF-15 (forma incompatible) y RNF-T11 (claves ocultas); plan D6. */
class ReachConfigReaderTest {

    private lateinit var server: MockWebServer

    @Before
    fun start() {
        server = MockWebServer().also { it.start() }
    }

    @After
    fun stop() {
        server.shutdown()
    }

    private fun read(body: String? = ReachFixtures.text("configuration.json")): ReachPart<ReachConfigView> = runBlocking {
        if (body != null) server.enqueue(MockResponse().setBody(body))
        ReachConfigReader(ReachHttpClient(ReachEndpoint(server.hostName, server.port), SocketFactory.getDefault())).read()
    }

    private fun ReachPart<ReachConfigView>.view(): ReachConfigView = shouldBeInstanceOf<ReachPart.Ok<ReachConfigView>>().value

    private fun List<ReachConfigEntry>.asMap(): Map<String, String> = associate { it.path to it.value }

    @Test
    fun `S044 RF-14 los ajustes de posicionamiento se aplanan a pares ruta y valor`() {
        val positioning = read().view().positioning.asMap()

        positioning["positioning_settings.positioning_mode"] shouldBe "kinematic"
        positioning["positioning_settings.elevation_mask_angle"] shouldBe "15"
        positioning["positioning_settings.gnss_settings.update_rate"] shouldBe "5"
        positioning["positioning_settings.gnss_settings.positioning_systems.gps"] shouldBe "true"
        positioning["positioning_settings.gnss_settings.positioning_systems.beidou"] shouldBe "false"
    }

    @Test
    fun `S044 RF-14 las secciones de salida se incluyen y las demas no`() {
        val view = read().view()
        val output = view.output.asMap()

        output["position_output.output1.type"] shouldBe "bluetooth"
        output["position_output.output1.format"] shouldBe "nmea"
        output["position_output.output1.sentences"] shouldBe "GGA, GST"
        output["position_output.output2.port"] shouldBe "9001"
        (view.positioning + view.output).map { it.path }.none { it.startsWith("wifi") || it.startsWith("correction_input") } shouldBe true
    }

    @Test
    fun `S044 RNF-T11 el valor de una clave sensible sale oculto y nunca se guarda`() {
        val view = read().view()
        val token = view.output.single { it.path == "position_output.output2.token" }

        token.hidden shouldBe true
        token.value shouldBe ""
        view.toString().contains("token-de-mentira") shouldBe false
    }

    @Test
    fun `S044 RNF-T11 una clave sensible oculta tambien todo lo que cuelga de ella`() {
        val body = """{"configuration":{"positioning_settings":{"my_password":{"inner":"visible-no"},"mask":15}}}"""

        val positioning = read(body).view().positioning

        positioning.single { it.path == "positioning_settings.my_password.inner" }.hidden shouldBe true
        positioning.single { it.path == "positioning_settings.mask" }.hidden shouldBe false
        positioning.map { it.value } shouldNotContain "visible-no"
    }

    @Test
    fun `S044 RNF-T11 cada palabra sensible oculta el valor sin distinguir mayusculas`() {
        val settings = """"A_PASS":"1","the_Secret":"2","apiToken":"3","api_key":"4","wifi_PSK":"5","ok":"6""""
        val body = """{"configuration":{"positioning_settings":{$settings}}}"""

        val values = read(body).view().positioning.filter { !it.hidden }.map { it.value }

        values shouldBe listOf("6")
    }

    @Test
    fun `S044 RF-14 sin secciones de salida la lista de salida esta vacia y es compatible`() {
        val body = """{"configuration":{"positioning_settings":{"mask":15}}}"""

        val view = read(body).view()

        view.output shouldBe emptyList()
        view.positioning.asMap() shouldBe mapOf("positioning_settings.mask" to "15")
    }

    @Test
    fun `S044 RF-14 una lista de valores simples se junta y un valor nulo sale vacio`() {
        val body = """{"configuration":{"positioning_settings":{"systems":["gps","galileo"],"nada":null}}}"""

        val positioning = read(body).view().positioning.asMap()

        positioning["positioning_settings.systems"] shouldBe "gps, galileo"
        positioning["positioning_settings.nada"] shouldBe ""
    }

    @Test
    fun `S044 RF-15 una forma incompatible marca la parte de ajustes`() {
        val result = read(ReachFixtures.text("configuration-incompatible.json"))

        result shouldBe ReachPart.Incompatible(ReachPartId.SETTINGS, "falta configuration.positioning_settings")
    }

    @Test
    fun `S044 RF-15 sin respuesta es incompatible`() {
        read(body = null) shouldBe ReachPart.Incompatible(ReachPartId.SETTINGS, "sin respuesta")
    }

    @Test
    fun `S044 RF-15 una respuesta que no es JSON es incompatible`() {
        read("no es json") shouldBe ReachPart.Incompatible(ReachPartId.SETTINGS, "la respuesta no es JSON")
    }

    @Test
    fun `S044 RF-14 el recuento de ajustes cuenta posicionamiento y salida`() {
        val view = read().view()

        view.count shouldBe view.positioning.size + view.output.size
        (view.count > 10) shouldBe true
        view.positioning.map { it.path } shouldContain "positioning_settings.snr_mask"
    }

    @Test
    fun `S044 RF-14 la lectura solo hace un GET`() {
        read()

        server.takeRequest().method shouldBe "GET"
        server.requestCount shouldBe 1
    }
}
