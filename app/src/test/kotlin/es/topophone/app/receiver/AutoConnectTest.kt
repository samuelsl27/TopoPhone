package es.topophone.app.receiver

import es.topophone.app.ui.receiver.FakeBluetooth
import es.topophone.app.ui.receiver.FakePreferences
import es.topophone.app.ui.receiver.RS3_DEVICE
import es.topophone.app.ui.receiver.nmea
import es.topophone.core.ntrip.NtripConnector
import es.topophone.core.ntrip.NtripFailure
import es.topophone.data.receiver.SavedReceiver
import es.topophone.receiver.session.ConnectionState
import es.topophone.receiver.session.ReceiverSession
import es.topophone.receiver.session.ReceiverSnapshot
import es.topophone.receiver.transport.ReceiverTransport
import es.topophone.receiver.transport.StreamTransport
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Test

/** Conexión con reconexión, último receptor y conexión automática al abrir la app (S004/RF-22, RF-24 a RF-26). */
class AutoConnectTest {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val session = ReceiverSession(scope, NtripConnector { _, _, _, _ -> NtripFailure.CONNECTION_LOST }, backoffMs = listOf(50))
    private val bluetooth = FakeBluetooth()
    private val preferences = FakePreferences()
    private val connections = ReceiverConnections(session, bluetooth, preferences, scope)
    private val toApp = PipedOutputStream()
    private val gga = nmea("GNGGA,101530.00,4025.0080000,N,00342.2228000,W,4,24,0.6,650.123,M,51.234,M,1.0,0001")

    init {
        bluetooth.transports[RS3_DEVICE.address] =
            StreamTransport(RS3_DEVICE.name, PipedInputStream(toApp, 64 * 1024), ByteArrayOutputStream())
    }

    @After
    fun tearDown() = scope.cancel()

    private fun await(predicate: (ReceiverSnapshot) -> Boolean) = runBlocking { withTimeout(5_000) { session.state.first(predicate) } }

    private fun saved() {
        preferences.lastReceiverFlow.value = SavedReceiver(RS3_DEVICE.address, RS3_DEVICE.name)
    }

    @Test
    fun `S004 RF-25 al abrir la app se conecta solo con el ultimo receptor`() {
        saved()

        runBlocking { connections.autoConnect() } shouldBe true
        toApp.write(gga.toByteArray())
        toApp.flush()

        await { it.connection is ConnectionState.Receiving }
    }

    @Test
    fun `S004 RF-25 sin ultimo receptor, con el Bluetooth apagado, sin permiso o sin emparejar no se conecta`() {
        runBlocking { connections.autoConnect() } shouldBe false

        saved()
        bluetooth.enabled = false
        runBlocking { ReceiverConnections(session, bluetooth, preferences, scope).autoConnect() } shouldBe false

        bluetooth.enabled = true
        bluetooth.permission = false
        runBlocking { ReceiverConnections(session, bluetooth, preferences, scope).autoConnect() } shouldBe false

        bluetooth.permission = true
        preferences.lastReceiverFlow.value = SavedReceiver("66:77:88:99:AA:BB", "otro")
        runBlocking { ReceiverConnections(session, bluetooth, preferences, scope).autoConnect() } shouldBe false

        session.state.value.connection shouldBe ConnectionState.Disconnected
    }

    @Test
    fun `S004 RF-25 la conexion automatica se intenta una sola vez por arranque y no si ya hay conexion`() {
        saved()
        runBlocking { connections.autoConnect() } shouldBe true
        runBlocking { connections.autoConnect() } shouldBe false

        val other = ReceiverConnections(session, bluetooth, preferences, scope)
        runBlocking { other.autoConnect() } shouldBe false // la sesión ya está conectando
    }

    @Test
    fun `S004 RF-24 al recibir datos se guarda el receptor como ultimo usado`() {
        connections.connect(RS3_DEVICE.address)
        toApp.write(gga.toByteArray())
        toApp.flush()

        runBlocking { withTimeout(5_000) { preferences.lastReceiverFlow.first { it != null } } } shouldBe
            SavedReceiver(RS3_DEVICE.address, RS3_DEVICE.name)
    }

    @Test
    fun `S004 RF-26 si la conexion automatica falla se indica y no se reintenta`() {
        saved()
        val opens = AtomicInteger()
        bluetooth.transports[RS3_DEVICE.address] = object : ReceiverTransport {
            override val description = RS3_DEVICE.name
            override fun open() {
                opens.incrementAndGet()
                throw IOException("receptor apagado")
            }
            override val input = PipedInputStream()
            override val output = ByteArrayOutputStream()
            override fun close() = Unit
        }

        runBlocking { connections.autoConnect() }

        await { it.connection is ConnectionState.Failed }.connection.shouldBeInstanceOf<ConnectionState.Failed>()
        Thread.sleep(300)
        opens.get() shouldBe 1
    }

    @Test
    fun `S004 RF-22 al reconectar con el Bluetooth apagado se indica`() {
        connections.connect(RS3_DEVICE.address)
        toApp.write(gga.toByteArray())
        toApp.flush()
        await { it.connection is ConnectionState.Receiving }

        bluetooth.enabled = false
        toApp.close()

        await { (it.connection as? ConnectionState.Reconnecting)?.bluetoothOff == true }
    }
}
