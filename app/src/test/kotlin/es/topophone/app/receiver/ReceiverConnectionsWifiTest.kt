package es.topophone.app.receiver

import es.topophone.app.ui.receiver.FakeBluetooth
import es.topophone.app.ui.receiver.FakePreferences
import es.topophone.app.ui.receiver.nmea
import es.topophone.core.model.receiver.ReceiverKey
import es.topophone.core.model.receiver.WifiReceiver
import es.topophone.core.model.receiver.WifiReceiverInput
import es.topophone.core.ntrip.NtripConnector
import es.topophone.core.ntrip.NtripFailure
import es.topophone.data.receiver.SavedReceiver
import es.topophone.data.receiver.WifiReceiverRepository
import es.topophone.receiver.platform.NetworkChange
import es.topophone.receiver.platform.NetworkRouter
import es.topophone.receiver.platform.RoutedNetwork
import es.topophone.receiver.platform.WifiUnavailableException
import es.topophone.receiver.session.ConnectionState
import es.topophone.receiver.session.ReceiverSession
import es.topophone.receiver.session.ReceiverSnapshot
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import javax.net.SocketFactory
import kotlin.concurrent.thread
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Test

/** Conectar con un receptor Wi-Fi por su clave `wifi:<id>`: reabrir, conexión automática, último receptor y receptor borrado (S021/RF-7, RF-15, RF-17). */
class ReceiverConnectionsWifiTest {

    /** Un receptor Wi-Fi de mentira: un servidor en localhost que manda una GGA al aceptar. */
    private class LocalReceiver(private val gga: String) : AutoCloseable {
        private val server = ServerSocket(0, 10, InetAddress.getLoopbackAddress())
        private val clients = CopyOnWriteArrayList<Socket>()
        val accepted = AtomicInteger()
        val port: Int get() = server.localPort

        init {
            thread(isDaemon = true) {
                while (true) {
                    val client = try {
                        server.accept()
                    } catch (_: Exception) {
                        break
                    }
                    accepted.incrementAndGet()
                    clients += client
                    runCatching {
                        client.getOutputStream().apply {
                            write(gga.toByteArray())
                            flush()
                        }
                    }
                }
            }
        }

        fun dropClients() {
            clients.forEach { runCatching { it.close() } }
            clients.clear()
        }

        override fun close() {
            runCatching { server.close() }
            dropClients()
        }
    }

    private class FakeWifiReceivers : WifiReceiverRepository {
        val list = MutableStateFlow<List<WifiReceiver>>(emptyList())
        private var next = 0

        override fun receivers(): Flow<List<WifiReceiver>> = list

        override fun observe(id: String): Flow<WifiReceiver?> = list.map { rows -> rows.firstOrNull { it.id == id } }

        override suspend fun byId(id: String): WifiReceiver? = list.value.firstOrNull { it.id == id }

        override suspend fun add(input: WifiReceiverInput): String {
            val id = "r${++next}"
            list.value += WifiReceiver(id, input.name, input.host, input.positionPort, input.correctionsPort)
            return id
        }

        override suspend fun update(id: String, input: WifiReceiverInput): Boolean {
            if (list.value.none { it.id == id }) return false
            list.value = list.value.map {
                if (it.id == id) WifiReceiver(id, input.name, input.host, input.positionPort, input.correctionsPort) else it
            }
            return true
        }

        override suspend fun delete(id: String): Boolean {
            val had = list.value.any { it.id == id }
            list.value = list.value.filterNot { it.id == id }
            return had
        }
    }

    /** El Wi-Fi del móvil: se puede apagar y encender; los sockets salen por la fábrica de siempre. */
    private class FakeRouter : NetworkRouter {
        @Volatile var wifiOn = true
        val wifiRequests = AtomicInteger()
        override val changes: Flow<NetworkChange> = MutableSharedFlow<NetworkChange>().asSharedFlow()
        override val internetAvailable: StateFlow<Boolean> = MutableStateFlow(false)

        override suspend fun wifiNetwork(): RoutedNetwork {
            wifiRequests.incrementAndGet()
            if (!wifiOn) throw WifiUnavailableException()
            return RoutedNetwork("wifi", SocketFactory.getDefault())
        }

        override fun internetNetwork(): RoutedNetwork? = null

        override fun release() = Unit
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val session = ReceiverSession(scope, NtripConnector { _, _, _, _ -> NtripFailure.CONNECTION_LOST }, backoffMs = listOf(50))
    private val bluetooth = FakeBluetooth()
    private val preferences = FakePreferences()
    private val repository = FakeWifiReceivers()
    private val router = FakeRouter()
    private val connections = ReceiverConnections(session, bluetooth, preferences, scope, repository, router)
    private val gga = nmea("GNGGA,101530.00,4025.0080000,N,00342.2228000,W,4,24,0.6,650.123,M,51.234,M,1.0,0001")
    private val servers = mutableListOf<LocalReceiver>()

    @After
    fun tearDown() {
        scope.cancel()
        servers.forEach { it.close() }
    }

    private fun server() = LocalReceiver(gga).also { servers += it }

    private fun receiver(server: LocalReceiver, name: String = "RS3 campo", corrections: Int? = null): String = runBlocking {
        repository.add(WifiReceiverInput(name, "127.0.0.1", server.port, corrections))
    }

    private fun await(timeoutMs: Long = 5_000, predicate: (ReceiverSnapshot) -> Boolean) =
        runBlocking { withTimeout(timeoutMs) { session.state.first(predicate) } }

    @Test
    fun `S021 RF-7 conectar con la clave wifi abre el TCP, dice Conectando y pasa a Recibiendo`() {
        val server = server()
        val id = receiver(server)

        connections.connect(ReceiverKey.wifi(id))

        await { it.connection is ConnectionState.Connecting || it.connection is ConnectionState.Receiving }
        val receiving = await { it.connection is ConnectionState.Receiving }.connection as ConnectionState.Receiving
        receiving.description shouldBe "RS3 campo (127.0.0.1:${server.port})"
        connections.currentAddress.value shouldBe ReceiverKey.wifi(id)
        router.wifiRequests.get() shouldBe 1
    }

    @Test
    fun `S021 RF-17 al recibir se recuerda el receptor Wi-Fi como el ultimo con su clave`() {
        val server = server()
        val id = receiver(server)

        connections.connect(ReceiverKey.wifi(id))
        await { it.connection is ConnectionState.Receiving }

        runBlocking { withTimeout(5_000) { preferences.lastReceiverFlow.first { it != null } } } shouldBe
            SavedReceiver(ReceiverKey.wifi(id), "RS3 campo (127.0.0.1:${server.port})")
    }

    @Test
    fun `S021 RF-15 un corte de la conexion TCP se reconecta solo`() {
        val server = server()
        val id = receiver(server)
        connections.connect(ReceiverKey.wifi(id))
        await { it.connection is ConnectionState.Receiving }

        server.dropClients()

        await { it.connection is ConnectionState.Reconnecting }
        await { it.connection is ConnectionState.Receiving }
        server.accepted.get() shouldBe 2
    }

    @Test
    fun `S021 RF-15 con el Wi-Fi apagado se sigue esperando y al encenderlo vuelve a recibir`() {
        val server = server()
        val id = receiver(server)
        connections.connect(ReceiverKey.wifi(id))
        await { it.connection is ConnectionState.Receiving }

        router.wifiOn = false
        server.dropClients()
        await { ((it.connection as? ConnectionState.Reconnecting)?.attempt ?: 0) >= 1 }
        router.wifiOn = true

        await { it.connection is ConnectionState.Receiving }
    }

    @Test
    fun `S021 RF-14 conectar con el Wi-Fi apagado falla sin colgar la app`() {
        val server = server()
        val id = receiver(server)
        router.wifiOn = false

        connections.connect(ReceiverKey.wifi(id))

        await { it.connection is ConnectionState.Failed }
        server.accepted.get() shouldBe 0
    }

    @Test
    fun `S021 RF-15 al reabrir se usan la direccion y el puerto editados entre medias`() {
        val first = server()
        val second = server()
        val id = receiver(first)
        connections.connect(ReceiverKey.wifi(id))
        await { it.connection is ConnectionState.Receiving }

        runBlocking { repository.update(id, WifiReceiverInput("RS3 campo", "127.0.0.1", second.port, null)) }
        first.dropClients()

        await { it.connection is ConnectionState.Reconnecting }
        await { it.connection is ConnectionState.Receiving }
        second.accepted.get() shouldBe 1
    }

    @Test
    fun `S021 RF-17 la conexion automatica abre el ultimo receptor Wi-Fi sin pedir nada del Bluetooth`() {
        val server = server()
        val id = receiver(server)
        preferences.lastReceiverFlow.value = SavedReceiver(ReceiverKey.wifi(id), "RS3 campo")
        bluetooth.enabled = false
        bluetooth.permission = false

        runBlocking { connections.autoConnect() } shouldBe true

        await { it.connection is ConnectionState.Receiving }
    }

    @Test
    fun `S021 RF-17 si el ultimo receptor Wi-Fi se borro no se conecta`() {
        val server = server()
        val id = receiver(server)
        preferences.lastReceiverFlow.value = SavedReceiver(ReceiverKey.wifi(id), "RS3 campo")
        runBlocking { repository.delete(id) }

        runBlocking { connections.autoConnect() } shouldBe false

        session.state.value.connection shouldBe ConnectionState.Disconnected
        server.accepted.get() shouldBe 0
    }

    @Test
    fun `S021 RF-4 borrar el receptor Wi-Fi conectado cierra la conexion`() {
        val server = server()
        val id = receiver(server)
        connections.connect(ReceiverKey.wifi(id))
        await { it.connection is ConnectionState.Receiving }

        runBlocking { repository.delete(id) }

        await { it.connection is ConnectionState.Disconnected }
    }

    @Test
    fun `S021 RF-17 editar el receptor Wi-Fi conectado no cierra la conexion`() {
        val server = server()
        val id = receiver(server)
        connections.connect(ReceiverKey.wifi(id))
        await { it.connection is ConnectionState.Receiving }

        runBlocking { repository.update(id, WifiReceiverInput("Otro nombre", "127.0.0.1", server.port, null)) }
        Thread.sleep(300)

        session.state.value.connection.shouldBeInstanceOf<ConnectionState.Receiving>()
        connections.currentAddress.value shouldBe ReceiverKey.wifi(id)
    }

    @Test
    fun `S021 RF-17 una clave Wi-Fi sin repositorio no conecta ni rompe`() {
        val plain = ReceiverConnections(session, bluetooth, preferences, scope)

        plain.connect(ReceiverKey.wifi("nada"))
        Thread.sleep(200)

        session.state.value.connection shouldBe ConnectionState.Disconnected
        plain.currentAddress.value shouldBe ReceiverKey.wifi("nada")
        preferences.lastReceiverFlow.value.shouldBeNull()
    }
}
