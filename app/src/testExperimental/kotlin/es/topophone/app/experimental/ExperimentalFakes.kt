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
import es.topophone.receiver.experimental.ReachPart
import es.topophone.receiver.experimental.WriteResult
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.map

/** Dobles de prueba compartidos por los tests del flavor experimental (S044). */
class FakeLiveConnection(val listener: LiveListener) : LiveConnection {
    var closed = false
    val sent = mutableListOf<String>()

    override fun send(text: String) {
        sent += text
    }

    override fun close() {
        closed = true
    }
}

class FakeLiveTransport : LiveTransport {
    val connections = mutableListOf<FakeLiveConnection>()

    override fun connect(listener: LiveListener): LiveConnection = FakeLiveConnection(listener).also { connections += it }
}

/** Una pasarela con respuestas fijas y el registro de lo que se le pidió. */
class FakeReachGateway : ReachGateway {
    val infoCalls = mutableListOf<ReachEndpoint>()
    val transports = mutableListOf<Pair<ReachEndpoint, FakeLiveTransport>>()
    var reading = ReachInfoReading(
        ReachPart.Ok(ReachIdentity("Reach RS3", "SINTETICO0001", "v0.0")),
        ReachPart.Ok(ReachDeviceSettings("rover", 1.8)),
    )
    var config: ReachPart<ReachConfigView> = ReachPart.Ok(ReachConfigView(emptyList(), emptyList()))
    val configCalls = mutableListOf<ReachEndpoint>()
    var antennaResult: (Double) -> WriteResult<Double> = { WriteResult.Confirmed(it) }
    var logsResult: (Boolean) -> WriteResult<Boolean> = { WriteResult.Confirmed(it) }
    val antennaWrites = mutableListOf<Double>()
    val logWrites = mutableListOf<Boolean>()

    override suspend fun readInfo(endpoint: ReachEndpoint): ReachInfoReading {
        infoCalls += endpoint
        return reading
    }

    override suspend fun readConfig(endpoint: ReachEndpoint): ReachPart<ReachConfigView> {
        configCalls += endpoint
        return config
    }

    override suspend fun setAntennaHeight(endpoint: ReachEndpoint, valueM: Double): WriteResult<Double> {
        antennaWrites += valueM
        return antennaResult(valueM)
    }

    override suspend fun setLogs(endpoint: ReachEndpoint, started: Boolean, live: LogsObserver): WriteResult<Boolean> {
        logWrites += started
        return logsResult(started)
    }

    override fun transport(endpoint: ReachEndpoint): LiveTransport = FakeLiveTransport().also { transports += endpoint to it }
}

class FakeEquipmentRepository : EquipmentRepository {
    val addresses = MutableStateFlow<Map<String, String>>(emptyMap())
    val profiles = MutableStateFlow<Map<String, EquipmentProfile>>(emptyMap())

    /** Cada escritura, como `perfil:<clave>` o `direccion:<clave>`. */
    val writes = mutableListOf<String>()

    override fun profile(receiverKey: String): Flow<EquipmentProfile?> = if (profiles.value.isEmpty()) {
        emptyFlow()
    } else {
        profiles.map {
            it[receiverKey]
        }
    }

    override suspend fun save(receiverKey: String, profile: EquipmentProfile) {
        writes += "perfil:$receiverKey"
        profiles.value = profiles.value + (receiverKey to profile)
    }

    override fun webAddress(receiverKey: String): Flow<String?> = addresses.map { it[receiverKey] }

    override suspend fun saveWebAddress(receiverKey: String, address: String?) {
        writes += "direccion:$receiverKey"
        addresses.value = if (address == null) addresses.value - receiverKey else addresses.value + (receiverKey to address)
    }
}

class FakeWifiRepository(vararg initial: WifiReceiver) : WifiReceiverRepository {
    val all = MutableStateFlow(initial.toList())

    override fun receivers(): Flow<List<WifiReceiver>> = all

    override fun observe(id: String): Flow<WifiReceiver?> = all.map { list -> list.firstOrNull { it.id == id } }

    override suspend fun byId(id: String): WifiReceiver? = all.value.firstOrNull { it.id == id }

    override suspend fun add(input: WifiReceiverInput): String = error("no se usa")

    override suspend fun update(id: String, input: WifiReceiverInput): Boolean = error("no se usa")

    override suspend fun delete(id: String): Boolean = error("no se usa")
}

/** Preferencias reales del módulo sobre un fichero temporal, ya activadas o no; el que las crea las cierra con [close]. */
class TestPreferences(folder: java.io.File, active: Boolean) : java.io.Closeable {
    private val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)
    val preferences = ExperimentalPreferences(
        es.topophone.data.settings.SettingsDataStore.create(scope) { java.io.File(folder, "e.preferences_pb") },
    )

    init {
        if (active) kotlinx.coroutines.runBlocking { preferences.accept(1L) }
    }

    override fun close() = scope.cancel()
}

/** Una sesión que no hace nada (módulo apagado, sin receptor), para los tests de ranuras que no la usan. */
fun idleSession(scope: kotlinx.coroutines.CoroutineScope) = ExperimentalSession(
    active = kotlinx.coroutines.flow.flowOf(false),
    key = kotlinx.coroutines.flow.flowOf(null),
    wifi = FakeWifiRepository(),
    equipment = FakeEquipmentRepository(),
    gateway = FakeReachGateway(),
    scope = scope,
)
