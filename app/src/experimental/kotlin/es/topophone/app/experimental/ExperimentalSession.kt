package es.topophone.app.experimental

import es.topophone.app.receiver.CurrentEquipment
import es.topophone.core.model.receiver.ReceiverKey
import es.topophone.data.equipment.EquipmentRepository
import es.topophone.data.receiver.WifiReceiverRepository
import es.topophone.receiver.experimental.LogsObserver
import es.topophone.receiver.experimental.ReachEndpoint
import es.topophone.receiver.experimental.ReachInfoReading
import es.topophone.receiver.experimental.ReachLiveState
import es.topophone.receiver.experimental.ReachLiveStatus
import es.topophone.receiver.experimental.WriteFailure
import es.topophone.receiver.experimental.WriteResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** A qué receptor habla el módulo y si puede (S044/RF-11; plan D9). */
sealed interface ReachSource {
    /** El módulo está apagado. */
    data object Inactive : ReachSource

    /** El móvil, el receptor virtual, una reproducción o ningún receptor: no hay a quién preguntar (S043/RF-23). */
    data object NotApplicable : ReachSource

    /** Hay receptor pero no una IP privada con la que llegar a él: la pantalla la pide. */
    data class NeedsAddress(val key: String) : ReachSource

    data class Ready(val key: String, val endpoint: ReachEndpoint) : ReachSource
}

sealed interface ReachInfoState {
    data object Idle : ReachInfoState

    data object Loading : ReachInfoState

    data class Loaded(val reading: ReachInfoReading) : ReachInfoState
}

/**
 * La sesión del módulo experimental (S044/RF-5, RF-11, RF-16, RF-25; plan D7, D9): saca la dirección del receptor en uso según su fuente
 * (Wi-Fi, Bluetooth con la IP de Datos del receptor, o ninguna), lee su información, abre su estado en directo y lo cierra todo al
 * desactivar el módulo. Solo **lee** la clave del receptor en uso; no conoce `ReceiverSession` y el directo vive en su propio ámbito, así
 * que perderlo no afecta a la conexión del receptor. Lo único que guarda es la IP que pide la pantalla (`saveAddress`).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ExperimentalSession(
    active: Flow<Boolean>,
    key: Flow<String?>,
    private val wifi: WifiReceiverRepository,
    private val equipment: EquipmentRepository,
    private val gateway: ReachGateway,
    scope: CoroutineScope,
) {
    val source: StateFlow<ReachSource> = active.distinctUntilChanged()
        .flatMapLatest { on -> if (on) key.distinctUntilChanged().flatMapLatest(::sourceFor) else flowOf(ReachSource.Inactive) }
        .distinctUntilChanged()
        .stateIn(scope, SharingStarted.Eagerly, ReachSource.Inactive)

    private val refreshTick = MutableStateFlow(0)

    /** La información básica del receptor en uso: se lee al tener dirección y otra vez con [refresh]. */
    val info: StateFlow<ReachInfoState> = combine(source, refreshTick) { current, _ -> current }
        .flatMapLatest<ReachSource, ReachInfoState> { current ->
            if (current is ReachSource.Ready) {
                flow {
                    emit(ReachInfoState.Loading)
                    emit(ReachInfoState.Loaded(gateway.readInfo(current.endpoint)))
                }
            } else {
                flowOf(ReachInfoState.Idle)
            }
        }
        .stateIn(scope, SharingStarted.Eagerly, ReachInfoState.Idle)

    private val liveUsers = MutableStateFlow(0)
    private val liveStatus = MutableStateFlow<ReachLiveStatus?>(null)

    /** El estado en directo (batería y logs); vacío si no hay directo. */
    val live: StateFlow<ReachLiveState> = liveStatus
        .flatMapLatest { status -> status?.state ?: flowOf(ReachLiveState()) }
        .stateIn(scope, SharingStarted.Eagerly, ReachLiveState())

    /** Lo que necesita el escritor de logs del directo abierto, o `null` si no hay (S044/RF-20). */
    val logsObserver: LogsObserver? get() = liveStatus.value

    init {
        scope.launch {
            combine(source, liveUsers) { current, users -> (current as? ReachSource.Ready)?.endpoint?.takeIf { users > 0 } }
                .distinctUntilChanged()
                .collectLatest { endpoint ->
                    if (endpoint != null) {
                        val status = ReachLiveStatus(gateway.transport(endpoint), this)
                        liveStatus.value = status
                        status.start()
                        try {
                            awaitCancellation()
                        } finally {
                            status.close()
                            liveStatus.value = null
                        }
                    }
                }
        }
    }

    /**
     * Inicia o para los logs de [endpoint] (S044/RF-20, RF-22) y comprueba el estado resultante por el directo abierto; sin directo no
     * envía nada. No lo reintenta nunca (RF-21).
     */
    suspend fun setLogs(endpoint: ReachEndpoint, started: Boolean): WriteResult<Boolean> {
        val live = logsObserver ?: return WriteResult.Failed(WriteFailure.NO_LIVE)
        return gateway.setLogs(endpoint, started, live)
    }

    /** Envía la altura de antena (la única escritura de ajustes del módulo, S044/RF-18, RF-22); no la reintenta nunca (RF-21). */
    suspend fun setAntennaHeight(endpoint: ReachEndpoint, valueM: Double) = gateway.setAntennaHeight(endpoint, valueM)

    /** Lee los ajustes de posicionamiento y de salida de [endpoint] sin escribir nada (S044/RF-14, RF-15). */
    suspend fun readConfig(endpoint: ReachEndpoint) = gateway.readConfig(endpoint)

    /** Vuelve a leer la información (S044/RF-11). */
    fun refresh() = refreshTick.update { it + 1 }

    /**
     * Una pantalla pide el directo: se abre mientras haya al menos una que lo pida y haya dirección. Cada [startLive] se empareja con su
     * [stopLive], así dos pantallas (cabecera y panel) comparten una sola conexión y la última que sale la cierra (S044/RF-16, RF-17).
     */
    fun startLive() = liveUsers.update { it + 1 }

    fun stopLive() = liveUsers.update { (it - 1).coerceAtLeast(0) }

    /**
     * Guarda la IP que el usuario escribió para el receptor en uso, en la dirección web de Datos del receptor (S043/RF-13): la misma que
     * usa la vista web. `false` si no hay receptor o la dirección no es una IP privada (S044/RF-23).
     */
    suspend fun saveAddress(text: String): Boolean {
        val key = when (val current = source.value) {
            is ReachSource.NeedsAddress -> current.key
            is ReachSource.Ready -> current.key
            else -> return false
        }
        if (ReachEndpoint.parse(text) == null) return false
        equipment.saveWebAddress(key, text.trim())
        return true
    }

    private fun sourceFor(key: String?): Flow<ReachSource> {
        if (key == null || !CurrentEquipment.appliesTo(key)) return flowOf(ReachSource.NotApplicable)
        val saved = equipment.webAddress(key)
        val wifiId = ReceiverKey.wifiId(key)
        return if (wifiId != null) {
            combine(wifi.observe(wifiId), saved) { receiver, address -> resolve(key, receiver?.host, address) }
        } else {
            saved.map { resolve(key, null, it) }
        }
    }

    /** La IP del receptor Wi-Fi si lo es; si no, la guardada; si ninguna vale, hay que pedirla. */
    private fun resolve(key: String, host: String?, saved: String?): ReachSource {
        val endpoint = host?.let(ReachEndpoint::parse) ?: saved?.let(ReachEndpoint::parse)
        return if (endpoint != null) ReachSource.Ready(key, endpoint) else ReachSource.NeedsAddress(key)
    }
}
