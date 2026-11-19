package es.topophone.receiver.experimental

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Before
import org.junit.Test

/** S044/RF-18, RF-19, RF-21, RF-22: enviar la altura de antena (leer, modificar el objeto completo, escribir, releer); plan D10, A2. */
class ReachWriterAntennaTest {

    private class RecordingTrail : ReachTrail {
        val writes = mutableListOf<List<String?>>()

        override fun configRead(settingCount: Int) = Unit

        override fun write(operation: String, before: String?, sent: String, after: String?, result: String) {
            writes += listOf(operation, before, sent, after, result)
        }
    }

    private lateinit var receiver: FakeReachServer
    private val trail = RecordingTrail()

    @Before
    fun start() {
        receiver = FakeReachServer().start()
    }

    @After
    fun stop() {
        receiver.stop()
    }

    private fun write(value: Double, timeoutMs: Long = ReachHttpClient.CALL_TIMEOUT_MS): WriteResult<Double> =
        runBlocking { ReachWriter(receiver.client(timeoutMs), trail).setAntennaHeight(value) }

    @Test
    fun `S044 RF-19 lee, escribe, vuelve a leer y confirma lo que el receptor dice tener`() {
        write(1.5) shouldBe WriteResult.Confirmed(1.5)

        receiver.requests shouldBe listOf("GET /configuration", "POST /configuration/device", "GET /configuration")
        trail.writes shouldBe listOf(listOf("altura de antena", "1.8", "1.5", "1.5", "confirmado"))
    }

    @Test
    fun `S044 RF-22 el objeto enviado es el dispositivo completo y solo cambia la altura de antena`() {
        val extra = JsonObject(mapOf("modo" to JsonPrimitive("x")))
        receiver.configuration = withDevice(JsonObject(receiver.device + ("ajuste_desconocido" to extra)))
        val before = receiver.device

        write(0.0)

        val sent = Json.parseToJsonElement(receiver.deviceBodies.single()) as JsonObject
        sent shouldBe JsonObject(before + ("antenna_height" to JsonPrimitive(0.0)))
        sent["night_mode"] shouldBe before["night_mode"]
        sent["role"] shouldBe before["role"]
        sent["ajuste_desconocido"] shouldBe extra
        sent.keys shouldBe before.keys
    }

    @Test
    fun `S044 RF-22 nada mas que el dispositivo se envia ni se toca de la configuracion`() {
        val before = receiver.configuration

        write(1.1)

        receiver.requests.count { it.startsWith("POST") } shouldBe 1
        receiver.requests.filter { it.startsWith("POST") } shouldBe listOf("POST /configuration/device")
        (receiver.configuration["configuration"] as JsonObject).filterKeys { it != "device" } shouldBe
            (before["configuration"] as JsonObject).filterKeys { it != "device" }
    }

    @Test
    fun `S044 RF-21 si el receptor guarda otro valor se informa del que quedo y no se reintenta`() {
        receiver.storedAntennaOverride = 1.6

        write(1.5) shouldBe WriteResult.NotConfirmed(read = 1.6, requestFailed = false)

        receiver.deviceBodies.size shouldBe 1
        trail.writes.single()[4] shouldBe "no coincide"
        trail.writes.single()[3] shouldBe "1.6"
    }

    @Test
    fun `S044 RF-19 una diferencia de medio milimetro o menos se da por coincidente`() {
        receiver.storedAntennaOverride = 1.5004

        write(1.5) shouldBe WriteResult.Confirmed(1.5004)
    }

    @Test
    fun `S044 RF-21 si el receptor no aplica la escritura queda el valor anterior y se informa`() {
        receiver.applyDeviceWrites = false

        write(1.5) shouldBe WriteResult.NotConfirmed(read = 1.8, requestFailed = false)
    }

    @Test
    fun `S044 RF-21 si el POST falla se relee y se informa del valor que quedo`() {
        receiver.deviceWriteStatus = 500

        write(1.5) shouldBe WriteResult.NotConfirmed(read = 1.8, requestFailed = true)

        receiver.deviceBodies.size shouldBe 1
        trail.writes.single()[4] shouldBe "fallo del envio"
    }

    @Test
    fun `S044 RF-21 un receptor que se apaga a mitad deja el valor sin conocer`() {
        receiver.configurationReadsLeft = 1

        write(1.5, timeoutMs = 500) shouldBe WriteResult.NotConfirmed(read = null, requestFailed = false)

        trail.writes.single()[3] shouldBe null
        trail.writes.single()[4] shouldBe "sin relectura"
    }

    @Test
    fun `S044 RF-15 sin respuesta o con una forma incompatible no se escribe nada`() {
        receiver.configurationReadsLeft = 0
        write(1.5) shouldBe WriteResult.Failed(WriteFailure.NO_RESPONSE)

        receiver.configurationReadsLeft = Int.MAX_VALUE
        receiver.configuration = Json.parseToJsonElement(ReachFixtures.text("configuration-incompatible.json")) as JsonObject
        write(1.5) shouldBe WriteResult.Failed(WriteFailure.INCOMPATIBLE)

        receiver.requests.none { it.startsWith("POST") } shouldBe true
    }

    @Test
    fun `S044 RF-18 un valor que no es un numero finito no se envia`() {
        write(Double.NaN) shouldBe WriteResult.Failed(WriteFailure.INVALID_VALUE)
        write(Double.POSITIVE_INFINITY) shouldBe WriteResult.Failed(WriteFailure.INVALID_VALUE)

        receiver.requests shouldBe emptyList()
    }

    private fun withDevice(device: JsonObject): JsonObject {
        val configuration = receiver.configuration["configuration"] as JsonObject
        return JsonObject(receiver.configuration + ("configuration" to JsonObject(configuration + ("device" to device))))
    }
}
