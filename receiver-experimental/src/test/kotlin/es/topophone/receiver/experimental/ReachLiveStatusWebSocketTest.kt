package es.topophone.receiver.experimental

import io.kotest.matchers.shouldBe
import javax.net.SocketFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test

/** S044/RF-13, RF-16: el directo de verdad, por un WebSocket de OkHttp contra un servidor falso con las tramas de `fixtures/reach`. */
class ReachLiveStatusWebSocketTest {

    private lateinit var server: MockWebServer
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Before
    fun start() {
        server = MockWebServer().also { it.start() }
    }

    @After
    fun stop() {
        scope.cancel()
        // Con el WebSocket aun abierto, shutdown() puede no ver la cola vacia a tiempo en el CI: el test ya comprobo lo suyo.
        runCatching { server.shutdown() }
    }

    @Test
    fun `S044 RF-16 conecta por WebSocket con la ruta de Engine IO y lee bateria y logs`() = runBlocking<Unit> {
        server.enqueue(
            MockResponse().withWebSocketUpgrade(
                object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        listOf("engineio-open.txt", "battery-status.txt", "active-logs.txt").forEachIndexed { index, name ->
                            webSocket.send(ReachFixtures.text(name))
                            if (index == 0) webSocket.send("40")
                        }
                    }
                },
            ),
        )
        val status = ReachLiveStatus(OkHttpLiveTransport(ReachEndpoint(server.hostName, server.port), SocketFactory.getDefault()), scope)

        status.start()
        val state = withTimeout(5_000) { status.state.first { it.connected && it.batteryPercent != null && it.logsWriting != null } }

        state shouldBe ReachLiveState(connected = true, batteryPercent = 87, charger = "discharging", logsWriting = true)
        server.takeRequest().path shouldBe "/socket.io/?EIO=3&transport=websocket"
        status.close()
    }

    @Test
    fun `S044 RF-16 cerrar el directo vacia el estado`() = runBlocking<Unit> {
        server.enqueue(
            MockResponse().withWebSocketUpgrade(
                object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        webSocket.send(ReachFixtures.text("engineio-open.txt"))
                        webSocket.send("40")
                        webSocket.send(ReachFixtures.text("battery-status.txt"))
                    }
                },
            ),
        )
        val status = ReachLiveStatus(OkHttpLiveTransport(ReachEndpoint(server.hostName, server.port), SocketFactory.getDefault()), scope)

        status.start()
        withTimeout(5_000) { status.state.first { it.connected } }
        status.close()

        status.state.value shouldBe ReachLiveState()
    }
}
