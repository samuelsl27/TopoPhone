package es.topophone.app.experimental

import es.topophone.core.model.guide.EquipmentProfile
import es.topophone.core.model.receiver.WifiReceiver
import es.topophone.core.model.receiver.WifiReceiverInput
import es.topophone.data.equipment.EquipmentRepository
import es.topophone.data.receiver.WifiReceiverRepository
import es.topophone.receiver.experimental.LiveConnection
import es.topophone.receiver.experimental.LiveListener
import es.topophone.receiver.experimental.LiveTransport
import es.topophone.receiver.experimental.LogsObserver
import es.topophone.receiver.experimental.ReachConfigView
import es.topophone.receiver.experimental.ReachDeviceSettings
import es.topophone.receiver.experimental.ReachEndpoint
import es.topophone.receiver.experimental.ReachIdentity
import es.topophone.receiver.experimental.ReachInfoReading
import es.topophone.receiver.experimental.ReachLiveState
import es.topophone.receiver.experimental.ReachPart
import es.topophone.receiver.experimental.WriteResult
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import org.junit.Test

/** S044/RF-5, RF-11, RF-16, RF-25: la sesión del módulo: dirección según la fuente, lectura, directo y cierre al desactivar. */
@OptIn(ExperimentalCoroutinesApi::class)
class ExperimentalSessionTest {

    private class FakeConnection(val listener: LiveListener) : LiveConnection {
        var closed = false

        override fun send(text: String) = Unit

        override fun close() {
            closed = true
        }
    }

    private class FakeTransport : LiveTransport {
        val connections = mutableListOf<FakeConnection>()

        override fun connect(listener: LiveListener): LiveConnection = FakeConnection(listener).also { connections += it }
    }

    private class FakeGateway : ReachGateway {
        val infoCalls = mutableListOf<ReachEndpoint>()
        val transports = mutableListOf<Pair<ReachEndpoint, FakeTransport>>()
        val reading = ReachInfoReading(
            ReachPart.Ok(ReachIdentity("Reach RS3", "SINTETICO0001", "v0.0")),
            ReachPart.Ok(ReachDeviceSettings("rover", 1.8)),
        )

        override suspend fun readInfo(endpoint: ReachEndpoint): ReachInfoReading {
            infoCalls += endpoint
            return reading
        }

        override suspend fun readConfig(endpoint: ReachEndpoint): ReachPart<ReachConfigView> =
            ReachPart.Ok(ReachConfigView(emptyList(), emptyList()))

        override suspend fun setAntennaHeight(endpoint: ReachEndpoint, valueM: Double): WriteResult<Double> = WriteResult.Confirmed(valueM)

        override suspend fun setLogs(endpoint: ReachEndpoint, started: Boolean, live: LogsObserver): WriteResult<Boolean> =
            WriteResult.Confirmed(started)

        override fun transport(endpoint: ReachEndpoint): LiveTransport = FakeTransport().also { transports += endpoint to it }
    }

    private class FakeEquipment : EquipmentRepository {
        val addresses = MutableStateFlow<Map<String, String>>(emptyMap())
        val writes = mutableListOf<String>()

        override fun profile(receiverKey: String): Flow<EquipmentProfile?> = emptyFlow()

        override suspend fun save(receiverKey: String, profile: EquipmentProfile) {
            writes += "profile:$receiverKey"
        }

        override fun webAddress(receiverKey: String): Flow<String?> = addresses.map { it[receiverKey] }

        override suspend fun saveWebAddress(receiverKey: String, address: String?) {
            writes += "address:$receiverKey"
            addresses.value = if (address == null) addresses.value - receiverKey else addresses.value + (receiverKey to address)
        }
    }

    private class FakeWifi(vararg initial: WifiReceiver) : WifiReceiverRepository {
        val all = MutableStateFlow(initial.toList())

        override fun receivers(): Flow<List<WifiReceiver>> = all

        override fun observe(id: String): Flow<WifiReceiver?> = all.map { list -> list.firstOrNull { it.id == id } }

        override suspend fun byId(id: String): WifiReceiver? = all.value.firstOrNull { it.id == id }

        override suspend fun add(input: WifiReceiverInput): String = error("no se usa")

        override suspend fun update(id: String, input: WifiReceiverInput): Boolean = error("no se usa")

        override suspend fun delete(id: String): Boolean = error("no se usa")
    }

    private val scope = TestScope()
    private val active = MutableStateFlow(true)
    private val key = MutableStateFlow<String?>(null)
    private val gateway = FakeGateway()
    private val equipment = FakeEquipment()
    private val wifi = FakeWifi(
        WifiReceiver("a", "RS3 de la oficina", "192.168.42.1", 9001, null),
        WifiReceiver("b", "RS3 por nombre", "reach.local", 9001, null),
    )

    private val session = ExperimentalSession(active, key, wifi, equipment, gateway, scope.backgroundScope)

    private fun settle() = scope.runCurrent()

    private fun endpoint(ip: String) = ReachEndpoint.parse(ip)!!

    @Test
    fun `S044 RF-11 con el modulo apagado la sesion no hace nada`() {
        active.value = false
        key.value = "wifi:a"

        session.startLive()
        settle()

        session.source.value shouldBe ReachSource.Inactive
        session.info.value shouldBe ReachInfoState.Idle
        gateway.infoCalls shouldBe emptyList()
        gateway.transports shouldBe emptyList()
    }

    @Test
    fun `S044 RF-11 un receptor Wi-Fi con IP privada usa su direccion`() {
        key.value = "wifi:a"
        settle()

        session.source.value shouldBe ReachSource.Ready("wifi:a", endpoint("192.168.42.1"))
    }

    @Test
    fun `S044 RF-11 un receptor Wi-Fi con nombre pide la IP y con una guardada la usa`() {
        key.value = "wifi:b"
        settle()
        session.source.value shouldBe ReachSource.NeedsAddress("wifi:b")

        equipment.addresses.value = mapOf("wifi:b" to "192.168.1.50")
        settle()

        session.source.value shouldBe ReachSource.Ready("wifi:b", endpoint("192.168.1.50"))
    }

    @Test
    fun `S044 RF-11 un receptor Bluetooth usa la IP guardada de Datos del receptor y sin ella la pide`() {
        key.value = "AA:BB:CC:DD:EE:FF"
        settle()
        session.source.value shouldBe ReachSource.NeedsAddress("AA:BB:CC:DD:EE:FF")

        equipment.addresses.value = mapOf("AA:BB:CC:DD:EE:FF" to "10.0.0.7")
        settle()

        session.source.value shouldBe ReachSource.Ready("AA:BB:CC:DD:EE:FF", endpoint("10.0.0.7"))
    }

    @Test
    fun `S044 RF-23 una direccion guardada que no es privada no vale`() {
        key.value = "AA:BB:CC:DD:EE:FF"
        equipment.addresses.value = mapOf("AA:BB:CC:DD:EE:FF" to "8.8.8.8")
        settle()

        session.source.value shouldBe ReachSource.NeedsAddress("AA:BB:CC:DD:EE:FF")
    }

    @Test
    fun `S044 RF-11 el movil, el virtual, una reproduccion y sin receptor no aplican`() {
        listOf("phone", "virtual", "replay", null).forEach {
            key.value = it
            settle()
            session.source.value shouldBe ReachSource.NotApplicable
        }
        gateway.infoCalls shouldBe emptyList()
    }

    @Test
    fun `S044 RF-11 con una direccion lista se lee la informacion y se puede releer`() {
        key.value = "wifi:a"
        settle()

        session.info.value shouldBe ReachInfoState.Loaded(gateway.reading)
        gateway.infoCalls shouldBe listOf(endpoint("192.168.42.1"))

        session.refresh()
        settle()
        gateway.infoCalls.size shouldBe 2
    }

    @Test
    fun `S044 RF-11 al cambiar de receptor se vuelve a leer del nuevo`() {
        key.value = "wifi:a"
        settle()
        key.value = "AA:BB:CC:DD:EE:FF"
        equipment.addresses.value = mapOf("AA:BB:CC:DD:EE:FF" to "10.0.0.7")
        settle()

        gateway.infoCalls shouldBe listOf(endpoint("192.168.42.1"), endpoint("10.0.0.7"))
    }

    @Test
    fun `S044 RF-11 guardar una IP privada la deja en Datos del receptor y deja lista la fuente`() {
        key.value = "wifi:b"
        settle()

        val saved = suspendResult { session.saveAddress("192.168.1.99") }

        saved shouldBe true
        equipment.addresses.value shouldBe mapOf("wifi:b" to "192.168.1.99")
        session.source.value shouldBe ReachSource.Ready("wifi:b", endpoint("192.168.1.99"))
    }

    @Test
    fun `S044 RF-23 una IP publica o un nombre no se guardan`() {
        key.value = "wifi:b"
        settle()

        listOf("8.8.8.8", "reach.local", "").forEach {
            suspendResult { session.saveAddress(it) } shouldBe false
        }

        equipment.writes shouldBe emptyList()
    }

    @Test
    fun `S044 RF-16 el directo se abre para la direccion actual y sus tramas llegan al estado`() {
        key.value = "wifi:a"
        settle()
        session.startLive()
        settle()

        gateway.transports.map { it.first } shouldBe listOf(endpoint("192.168.42.1"))
        val connection = gateway.transports.single().second.connections.single()
        connection.listener.onFrame(LIVE_OPEN)
        connection.listener.onFrame("40")
        connection.listener.onFrame(LIVE_BATTERY)
        settle()

        session.live.value shouldBe ReachLiveState(connected = true, batteryPercent = 87, charger = "discharging", logsWriting = null)
    }

    @Test
    fun `S044 RF-16 parar el directo lo cierra y arrancarlo dos veces no abre dos`() {
        key.value = "wifi:a"
        settle()
        session.startLive()
        session.startLive()
        settle()

        gateway.transports.size shouldBe 1

        session.stopLive()
        settle()
        gateway.transports.single().second.connections.single().closed shouldBe true
        session.live.value shouldBe ReachLiveState()
    }

    @Test
    fun `S044 RF-16 si cambia la direccion el directo se reabre contra la nueva`() {
        key.value = "wifi:a"
        settle()
        session.startLive()
        settle()

        key.value = "AA:BB:CC:DD:EE:FF"
        equipment.addresses.value = mapOf("AA:BB:CC:DD:EE:FF" to "10.0.0.7")
        settle()

        gateway.transports.map { it.first } shouldBe listOf(endpoint("192.168.42.1"), endpoint("10.0.0.7"))
        gateway.transports[0].second.connections.single().closed shouldBe true
    }

    @Test
    fun `S044 RF-16 perder el directo no afecta ni a la direccion ni a la informacion`() {
        key.value = "wifi:a"
        settle()
        session.startLive()
        settle()
        val before = session.source.value to session.info.value

        gateway.transports.single().second.connections.single().listener.onEnded()
        settle()

        session.source.value to session.info.value shouldBe before
        gateway.infoCalls.size shouldBe 1
    }

    @Test
    fun `S044 RF-5 desactivar cierra el directo, borra la informacion y no vuelve a llamar al receptor`() {
        key.value = "wifi:a"
        settle()
        session.startLive()
        settle()
        val connection = gateway.transports.single().second.connections.single()

        active.value = false
        settle()
        scope.advanceTimeBy(60_000)
        settle()

        connection.closed shouldBe true
        session.source.value shouldBe ReachSource.Inactive
        session.info.value shouldBe ReachInfoState.Idle
        session.live.value shouldBe ReachLiveState()
        gateway.infoCalls.size shouldBe 1
        gateway.transports.size shouldBe 1
    }

    @Test
    fun `S044 RF-5 volver a activar lee de nuevo`() {
        key.value = "wifi:a"
        settle()
        active.value = false
        settle()

        active.value = true
        settle()

        session.source.value shouldBe ReachSource.Ready("wifi:a", endpoint("192.168.42.1"))
        gateway.infoCalls.size shouldBe 2
    }

    @Test
    fun `S044 RF-25 leer y abrir el directo no escribe nada en el equipo del proyecto`() {
        key.value = "wifi:a"
        settle()
        session.startLive()
        session.refresh()
        settle()

        equipment.writes shouldBe emptyList()
    }

    /** Ejecuta una función suspendida hasta el final y devuelve su resultado. */
    private fun <T> suspendResult(block: suspend () -> T): T {
        var result: T? = null
        scope.backgroundScope.launch { result = block() }
        scope.runCurrent()
        @Suppress("UNCHECKED_CAST")
        return result as T
    }

    private companion object {
        const val LIVE_OPEN = """0{"sid":"x","pingInterval":25000,"pingTimeout":60000}"""
        const val LIVE_BATTERY =
            """42["broadcast",{"name":"battery_status","payload":{"state_of_charge":87,"charger_status":"discharging"}}]"""
    }
}
