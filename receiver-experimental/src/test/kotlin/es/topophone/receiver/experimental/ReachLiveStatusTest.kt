package es.topophone.receiver.experimental

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import org.junit.Test

/** S044/RF-13, RF-16, RF-17: el estado en directo con un transporte falso y el reloj virtual; plan D7, A4. */
@OptIn(ExperimentalCoroutinesApi::class)
class ReachLiveStatusTest {

    private class FakeConnection(val listener: LiveListener) : LiveConnection {
        val sent = mutableListOf<String>()
        var closed = false

        override fun send(text: String) {
            sent += text
        }

        override fun close() {
            closed = true
        }

        fun receive(vararg frames: String) = frames.forEach(listener::onFrame)
    }

    private class FakeTransport : LiveTransport {
        val connections = mutableListOf<FakeConnection>()

        override fun connect(listener: LiveListener): LiveConnection = FakeConnection(listener).also { connections += it }
    }

    private val open = ReachFixtures.text("engineio-open.txt")
    private val battery = ReachFixtures.text("battery-status.txt")
    private val logsOn = ReachFixtures.text("active-logs.txt")
    private val logsOff = ReachFixtures.text("active-logs-stopped.txt")

    private class Rig {
        val scope = TestScope()
        val transport = FakeTransport()
        val status = ReachLiveStatus(transport, scope.backgroundScope)

        fun start() {
            status.start()
            scope.runCurrent()
        }

        fun advance(ms: Long) {
            scope.advanceTimeBy(ms)
            scope.runCurrent()
        }

        fun connectAndOpen(index: Int = transport.connections.size - 1) {
            transport.connections[index].receive(ReachFixtures.text("engineio-open.txt"), "40")
            scope.runCurrent()
        }
    }

    @Test
    fun `S044 RF-16 al abrir y conectar al espacio de nombres el estado pasa a conectado`() {
        val rig = Rig()
        rig.start()

        rig.status.state.value shouldBe ReachLiveState()
        rig.transport.connections.size shouldBe 1
        rig.transport.connections[0].receive(open)
        rig.scope.runCurrent()
        rig.status.state.value.connected shouldBe false

        rig.transport.connections[0].receive("40")
        rig.scope.runCurrent()
        rig.status.state.value.connected shouldBe true
    }

    @Test
    fun `S044 RF-13 la bateria y los logs salen de los eventos broadcast`() {
        val rig = Rig()
        rig.start()
        rig.connectAndOpen()

        rig.transport.connections[0].receive(battery, logsOn)
        rig.scope.runCurrent()

        rig.status.state.value shouldBe ReachLiveState(connected = true, batteryPercent = 87, charger = "discharging", logsWriting = true)

        rig.transport.connections[0].receive(logsOff)
        rig.scope.runCurrent()
        rig.status.state.value.logsWriting shouldBe false
    }

    @Test
    fun `S044 RF-13 cada evento de logs tambien se emite aparte para comprobar una escritura`() {
        val rig = Rig()
        rig.start()
        rig.connectAndOpen()
        val seen = mutableListOf<Boolean>()
        val job = rig.scope.backgroundScope.launch { rig.status.logEvents.collect { seen += it } }
        rig.scope.runCurrent()

        rig.transport.connections[0].receive(logsOn, logsOff)
        rig.scope.runCurrent()

        seen shouldBe listOf(true, false)
        job.cancel()
    }

    @Test
    fun `S044 RF-16 los eventos desconocidos o con otra forma se ignoran sin tocar el estado`() {
        val rig = Rig()
        rig.start()
        rig.connectAndOpen()
        rig.transport.connections[0].receive(battery)
        rig.scope.runCurrent()
        val before = rig.status.state.value

        rig.transport.connections[0].receive(
            """42["broadcast",{"name":"gps_satellites","payload":{"n":12}}]""",
            """42["otro",{}]""",
            """42["broadcast",{"name":"battery_status","payload":{"state_of_charge":"mucha"}}]""",
            """42["broadcast",{"name":"active_logs","payload":{"raw":5}}]""",
            "zzz",
            "",
            "6",
        )
        rig.scope.runCurrent()

        rig.status.state.value shouldBe before
        rig.transport.connections.size shouldBe 1
    }

    @Test
    fun `S044 RF-16 sin trafico durante el intervalo se manda un ping y con su pong sigue conectado`() {
        val rig = Rig()
        rig.start()
        rig.connectAndOpen()

        rig.advance(24_999)
        rig.transport.connections[0].sent shouldBe emptyList()
        rig.advance(1)
        rig.transport.connections[0].sent shouldBe listOf("2")

        rig.transport.connections[0].receive("3")
        rig.scope.runCurrent()
        rig.advance(25_000)

        rig.transport.connections[0].sent shouldBe listOf("2", "2")
        rig.status.state.value.connected shouldBe true
        rig.transport.connections.size shouldBe 1
    }

    @Test
    fun `S044 RF-17 sin pong en el tiempo limite se cierra y se reconecta tras un segundo`() {
        val rig = Rig()
        rig.start()
        rig.connectAndOpen()
        rig.transport.connections[0].receive(battery)
        rig.advance(25_000)

        rig.advance(60_000)

        rig.transport.connections[0].closed shouldBe true
        rig.status.state.value shouldBe ReachLiveState()
        rig.transport.connections.size shouldBe 1
        rig.advance(1_000)
        rig.transport.connections.size shouldBe 2
    }

    @Test
    fun `S044 RF-17 un corte deja el estado vacio y reconecta con espera creciente hasta 30 segundos`() {
        val rig = Rig()
        rig.start()

        val waits = listOf(1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 30_000L, 30_000L)
        waits.forEachIndexed { index, wait ->
            rig.transport.connections[index].listener.onEnded()
            rig.scope.runCurrent()
            rig.status.state.value shouldBe ReachLiveState()

            rig.advance(wait - 1)
            rig.transport.connections.size shouldBe index + 1
            rig.advance(1)
            rig.transport.connections.size shouldBe index + 2
        }
    }

    @Test
    fun `S044 RF-17 tras conectar de nuevo la espera vuelve a un segundo`() {
        val rig = Rig()
        rig.start()
        rig.transport.connections[0].listener.onEnded()
        rig.scope.runCurrent()
        rig.advance(1_000)
        rig.transport.connections[1].listener.onEnded()
        rig.scope.runCurrent()
        rig.advance(2_000)
        rig.connectAndOpen()
        rig.status.state.value.connected shouldBe true

        rig.transport.connections[2].listener.onEnded()
        rig.scope.runCurrent()

        rig.status.state.value shouldBe ReachLiveState()
        rig.advance(1_000)
        rig.transport.connections.size shouldBe 4
    }

    @Test
    fun `S044 RF-17 si no llega la apertura en diez segundos se reintenta`() {
        val rig = Rig()
        rig.start()

        rig.advance(10_000)

        rig.transport.connections[0].closed shouldBe true
        rig.advance(1_000)
        rig.transport.connections.size shouldBe 2
    }

    @Test
    fun `S044 RF-17 una excepcion al conectar se trata como un corte`() {
        val scope = TestScope()
        var calls = 0
        val failing = object : LiveTransport {
            override fun connect(listener: LiveListener): LiveConnection {
                calls++
                error("sin red")
            }
        }
        val status = ReachLiveStatus(failing, scope.backgroundScope)

        status.start()
        scope.runCurrent()
        calls shouldBe 1
        scope.advanceTimeBy(1_000)
        scope.runCurrent()

        calls shouldBe 2
        status.state.value shouldBe ReachLiveState()
    }

    @Test
    fun `S044 RF-16 cerrar para el directo y no vuelve a conectar`() {
        val rig = Rig()
        rig.start()
        rig.connectAndOpen()
        rig.transport.connections[0].receive(battery)

        rig.status.close()
        rig.scope.runCurrent()
        rig.advance(120_000)

        rig.transport.connections[0].closed shouldBe true
        rig.transport.connections.size shouldBe 1
        rig.status.state.value shouldBe ReachLiveState()
    }

    @Test
    fun `S044 RF-16 arrancar dos veces no abre dos conexiones`() {
        val rig = Rig()
        rig.start()
        rig.start()

        rig.transport.connections.size shouldBe 1
    }

    @Test
    fun `S044 RF-16 el ping del servidor se contesta con un pong`() {
        val rig = Rig()
        rig.start()
        rig.connectAndOpen()

        rig.transport.connections[0].receive("2")
        rig.scope.runCurrent()

        rig.transport.connections[0].sent shouldBe listOf("3")
    }

    @Test
    fun `S044 RF-16 el cierre ordenado del servidor es un corte`() {
        val rig = Rig()
        rig.start()
        rig.connectAndOpen()

        rig.transport.connections[0].receive("1")
        rig.scope.runCurrent()

        rig.status.state.value shouldBe ReachLiveState()
        rig.advance(1_000)
        rig.transport.connections.size shouldBe 2
    }
}
