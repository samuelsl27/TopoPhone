package es.topophone.receiver.experimental

import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.serialization.json.JsonObject
import org.junit.Test

/** S044/RF-13, RF-16: los paquetes del directo (Engine.IO v3 con Socket.IO v2 encima); plan D7, A4. */
class EngineIoPacketTest {

    @Test
    fun `S044 RF-16 el paquete de apertura trae el intervalo y el tiempo limite del ping`() {
        val packet = EngineIoPacket.parse(ReachFixtures.text("engineio-open.txt"))

        packet shouldBe EngineIoPacket.Open(pingIntervalMs = 25_000, pingTimeoutMs = 60_000)
    }

    @Test
    fun `S044 RF-16 una apertura sin intervalos usa los valores por defecto`() {
        EngineIoPacket.parse("""0{"sid":"x"}""") shouldBe EngineIoPacket.Open(25_000, 60_000)
    }

    @Test
    fun `S044 RF-16 una apertura que no es JSON es desconocida`() {
        EngineIoPacket.parse("0no-json") shouldBe EngineIoPacket.Unknown("0no-json")
    }

    @Test
    fun `S044 RF-16 los paquetes de control se reconocen`() {
        EngineIoPacket.parse("1") shouldBe EngineIoPacket.Close
        EngineIoPacket.parse("2") shouldBe EngineIoPacket.Ping
        EngineIoPacket.parse("3") shouldBe EngineIoPacket.Pong
        EngineIoPacket.parse("6") shouldBe EngineIoPacket.Noop
        EngineIoPacket.PING shouldBe "2"
        EngineIoPacket.PONG shouldBe "3"
    }

    @Test
    fun `S044 RF-16 un mensaje lleva su cuerpo sin el tipo de paquete`() {
        EngineIoPacket.parse("40") shouldBe EngineIoPacket.Message("0")
        EngineIoPacket.parse("""42["a",1]""") shouldBe EngineIoPacket.Message("""2["a",1]""")
    }

    @Test
    fun `S044 RF-16 un paquete vacio o de tipo desconocido no falla`() {
        EngineIoPacket.parse("") shouldBe EngineIoPacket.Unknown("")
        EngineIoPacket.parse("9lo que sea") shouldBe EngineIoPacket.Unknown("9lo que sea")
        EngineIoPacket.parse("zzz") shouldBe EngineIoPacket.Unknown("zzz")
    }

    @Test
    fun `S044 RF-16 la conexion al espacio de nombres es el mensaje 0`() {
        SocketIoMessage.isConnect("0") shouldBe true
        SocketIoMessage.isConnect("2[]") shouldBe false
        SocketIoMessage.isConnect("") shouldBe false
    }

    @Test
    fun `S044 RF-13 un evento broadcast trae su nombre y su carga`() {
        val message = EngineIoPacket.parse(ReachFixtures.text("battery-status.txt")).shouldBeInstanceOf<EngineIoPacket.Message>()

        val event = SocketIoMessage.broadcast(message.body)!!

        event.name shouldBe "battery_status"
        event.payload.shouldBeInstanceOf<JsonObject>().size shouldBe 2
    }

    @Test
    fun `S044 RF-13 el evento de logs tambien se lee`() {
        val message = EngineIoPacket.parse(ReachFixtures.text("active-logs.txt")).shouldBeInstanceOf<EngineIoPacket.Message>()

        SocketIoMessage.broadcast(message.body)!!.name shouldBe "active_logs"
    }

    @Test
    fun `S044 RF-13 un evento con numero de acuse se lee igual`() {
        SocketIoMessage.broadcast("""27["broadcast",{"name":"x","payload":{}}]""")!!.name shouldBe "x"
    }

    @Test
    fun `S044 RF-13 los eventos que no son broadcast o con otra forma se ignoran sin error`() {
        listOf(
            """2["otro",{"name":"battery_status","payload":{}}]""",
            """2["broadcast"]""",
            """2["broadcast",5]""",
            """2["broadcast",{"payload":{}}]""",
            """2["broadcast",{"name":7,"payload":{}}]""",
            """2not-json""",
            """2[""",
            "3",
            "",
        ).forEach { SocketIoMessage.broadcast(it) shouldBe null }
    }

    @Test
    fun `S044 RF-16 el cuerpo de una peticion de apertura lleva la version del protocolo y el transporte`() {
        val url = ReachEndpoint("192.168.42.1", 80).url("/socket.io/").newBuilder()
            .addQueryParameter("EIO", "3")
            .addQueryParameter("transport", "websocket")
            .build()

        url.encodedQuery shouldBe "EIO=3&transport=websocket"
    }
}
