package es.topophone.app.ui.receiver

import android.content.Intent
import android.content.IntentSender
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.Lazy
import dagger.hilt.android.lifecycle.HiltViewModel
import es.topophone.app.receiver.NotificationPermission
import es.topophone.app.receiver.ReceiverConnections
import es.topophone.app.receiver.ReceiverService
import es.topophone.core.gnss.nmea.ReplaySpeed
import es.topophone.core.model.project.AngularUnit
import es.topophone.core.model.project.ProjectDefaults
import es.topophone.core.survey.GroundPoint
import es.topophone.core.survey.GroundPointCalculator
import es.topophone.core.survey.PoleHeight
import es.topophone.data.project.ProjectPreferences
import es.topophone.data.receiver.ReceiverPreferences
import es.topophone.receiver.platform.Alerts
import es.topophone.receiver.platform.NetworkStatus
import es.topophone.receiver.session.ConnectionState
import es.topophone.receiver.session.ReceiverSession
import es.topophone.receiver.session.ReceiverSnapshot
import es.topophone.receiver.session.ReplayController
import es.topophone.receiver.transport.BluetoothDevices
import es.topophone.receiver.transport.BondedDevice
import es.topophone.receiver.transport.PairingResult
import es.topophone.receiver.transport.ReceiverPairing
import java.math.BigDecimal
import java.text.DecimalFormatSymbols
import javax.inject.Inject
import javax.inject.Named
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Estado del Bluetooth del móvil (S002/RF-1 a RF-4). */
sealed interface BluetoothUi {
    data object Unsupported : BluetoothUi

    data object PermissionNeeded : BluetoothUi

    data object Disabled : BluetoothUi

    data class Ready(val devices: List<BondedDevice>) : BluetoothUi
}

/** Avisos de "Añadir receptor" (S004/RF-4). */
enum class PairingMessage { SEARCH_FAILED, SEARCH_CANCELLED, PAIRING_FAILED }

/** "Añadir receptor": el diálogo del sistema pendiente de abrir y el último aviso (S004/RF-1 a RF-4). */
data class PairingUi(val chooser: IntentSender? = null, val message: PairingMessage? = null)

data class ReceiverUiState(
    val bluetooth: BluetoothUi = BluetoothUi.PermissionNeeded,
    val snapshot: ReceiverSnapshot = ReceiverSnapshot(),
    val lastAddress: String? = null,
    /** Último receptor con el que se conectó, para señalarlo en la lista (S004/RF-24, S005/RF-3). */
    val lastReceiverAddress: String? = null,
    val internetAvailable: Boolean = true,
    val poleHeightText: String = "",
    val poleHeightValid: Boolean = true,
    val groundPoint: GroundPoint? = null,
    val nowMs: Long = 0,
    /** Pedir el permiso de notificaciones, con su explicación (S004/RF-5). */
    val askNotifications: Boolean = false,
    /** Permiso denegado con un receptor conectado: avisar de cómo concederlo (S004/RF-6). */
    val notificationsDenied: Boolean = false,
    /** Hay búsqueda del sistema; si no, "Añadir receptor" abre los ajustes de Bluetooth (S004/RF-1). */
    val pairingSupported: Boolean = false,
    val pairing: PairingUi = PairingUi(),
    /** Formato angular de los proyectos nuevos, para la posición de la pantalla Estado (S015/RF-1). */
    val angularUnit: AngularUnit = ProjectDefaults().angularUnit,
)

/**
 * Sección Receptor de la spec 002: conecta con el receptor, gestiona el NTRIP y calcula el punto
 * del suelo. La sesión es de la app (no del ViewModel): sobrevive al cambiar de sección.
 */
@OptIn(FlowPreview::class)
@HiltViewModel
class ReceiverViewModel @Inject constructor(
    private val session: ReceiverSession,
    private val bluetooth: BluetoothDevices,
    private val preferences: ReceiverPreferences,
    private val calculator: Lazy<GroundPointCalculator>,
    private val network: NetworkStatus,
    private val alerts: Alerts,
    @param:Named(MONOTONIC_CLOCK) private val clock: () -> Long,
    @param:Named(UI_SAMPLE_MS) private val samplePeriodMs: Long,
    private val replay: ReplayController,
    private val connections: ReceiverConnections,
    private val notifications: NotificationPermission = NotificationPermission.NotRequired,
    private val pairing: ReceiverPairing = ReceiverPairing.Unsupported,
    private val projectPreferences: ProjectPreferences = ProjectPreferences.FACTORY,
) : ViewModel() {

    private val bluetoothUi = MutableStateFlow(readBluetooth())
    private val lastAddress = MutableStateFlow<String?>(null)
    private val internet = MutableStateFlow(true)
    private val poleText = MutableStateFlow(formatPoleHeight(PoleHeight.DEFAULT_M))
    private val poleValue = MutableStateFlow(PoleHeight.DEFAULT_M)
    private val notificationsGranted = MutableStateFlow(notifications.isGranted())
    private val notificationsAnsweredNow = MutableStateFlow(false)
    private val pairingSupported = pairing.isSupported
    private val pairingUi = MutableStateFlow(PairingUi())

    private val ticker: Flow<Long> = flow {
        while (true) {
            emit(clock())
            delay(TICK_MS)
        }
    }

    private val snapshots: Flow<ReceiverSnapshot> = if (samplePeriodMs > 0) session.state.sample(samplePeriodMs) else session.state

    private data class PoleState(val text: String, val value: Double)
    private data class Extras(
        val nowMs: Long,
        val addresses: Pair<String?, String?>,
        val notificationsGranted: Boolean,
        val notificationsAsked: Boolean,
        val pairing: PairingUi,
    )

    private val angularUnits: Flow<AngularUnit> = projectPreferences.defaults.map { it.angularUnit }.distinctUntilChanged()

    val uiState: StateFlow<ReceiverUiState> = combine(
        combine(snapshots, angularUnits, ::Pair),
        bluetoothUi,
        internet,
        combine(poleText, poleValue, ::PoleState),
        combine(
            ticker,
            combine(lastAddress, preferences.lastReceiver.map { it?.address }) { last, saved -> last to saved },
            notificationsGranted,
            combine(preferences.notificationsAsked, notificationsAnsweredNow) { saved, now -> saved || now },
            pairingUi,
        ) { now, addresses, granted, asked, pairing -> Extras(now, addresses, granted, asked, pairing) },
    ) { (snapshot, angularUnit), bt, internetAvailable, pole, extras ->
        // Solo con un receptor real: una reproducción no usa el servicio ni la notificación.
        val realReceiver = ReceiverService.needsService(snapshot)
        val missingPermission = notifications.required && !extras.notificationsGranted
        ReceiverUiState(
            bluetooth = bt,
            snapshot = snapshot,
            lastAddress = extras.addresses.first,
            lastReceiverAddress = extras.addresses.second,
            internetAvailable = internetAvailable,
            poleHeightText = pole.text,
            poleHeightValid = PoleHeight.parse(pole.text) != null,
            groundPoint = snapshot.gga?.let { calculator.get().compute(it, pole.value) },
            nowMs = extras.nowMs,
            askNotifications = missingPermission &&
                !extras.notificationsAsked &&
                realReceiver &&
                snapshot.connection is ConnectionState.Receiving, // S004/RF-5
            notificationsDenied = missingPermission && extras.notificationsAsked && realReceiver, // S004/RF-6
            pairingSupported = pairingSupported,
            pairing = extras.pairing,
            angularUnit = angularUnit,
        )
    }.flowOn(Dispatchers.Default).stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
        ReceiverUiState(
            bluetooth = bluetoothUi.value,
            snapshot = session.state.value,
            poleHeightText = poleText.value,
            pairingSupported = pairingSupported,
        ),
    )

    init {
        // El geoide se descodifica la primera vez que se usa: fuera del hilo principal y antes de la
        // primera GGA, para no bloquear la interfaz ni retrasar las primeras coordenadas (RNF-T13).
        viewModelScope.launch(Dispatchers.Default) { calculator.get() }
        viewModelScope.launch {
            val pole = preferences.poleHeightM.first()
            poleValue.value = pole
            poleText.value = formatPoleHeight(pole)
        }
        viewModelScope.launch {
            // S002/RF-9
            // S002/RF-9, S004/RF-13, S004/RF-20: vibra al empezar a reconectar y al dar la conexión por perdida, una vez cada vez.
            session.state.map { alertFor(it.connection) }.distinctUntilChanged().collect { if (it != null) alerts.connectionLost() }
        }
        viewModelScope.launch {
            session.state.map { it.ntrip }.distinctUntilChanged().collect { state ->
                if (state.unreachable()) internet.value = network.hasInternet() // S002/RF-23
            }
        }
    }

    /** Se llama al volver a la pantalla y tras pedir el permiso o activar el Bluetooth (S002/RF-1 a RF-4). */
    fun refreshBluetooth() {
        bluetoothUi.value = readBluetooth()
    }

    /** Se llama al volver a la pantalla y tras responder al permiso (S004/RF-6). */
    fun refreshNotifications() {
        notificationsGranted.value = notifications.isGranted()
    }

    /** El permiso de notificaciones se pide una sola vez, se acepte o no (S004/RF-5). */
    fun notificationsAnswered() {
        notificationsAnsweredNow.value = true
        viewModelScope.launch { preferences.markNotificationsAsked() }
    }

    /** S004/RF-1: busca el receptor con el diálogo del sistema. */
    fun addReceiver() {
        pairingUi.value = PairingUi()
        viewModelScope.launch {
            val chooser = pairing.search()
            pairingUi.value = if (chooser != null) PairingUi(chooser = chooser) else PairingUi(message = PairingMessage.SEARCH_FAILED)
        }
    }

    /** La pantalla ya ha abierto el diálogo: no se vuelve a abrir al recomponer. */
    fun chooserOpened() {
        pairingUi.update { it.copy(chooser = null) }
    }

    /** Resultado del diálogo: se empareja si hace falta y se conecta (S004/RF-2, RF-3); si no, se avisa (RF-4). */
    fun receiverChosen(resultCode: Int, data: Intent?) {
        viewModelScope.launch {
            when (val result = pairing.complete(resultCode, data)) {
                is PairingResult.Paired -> {
                    pairingUi.value = PairingUi()
                    refreshBluetooth()
                    connect(result.device.address)
                }
                PairingResult.SearchCancelled -> pairingUi.value = PairingUi(message = PairingMessage.SEARCH_CANCELLED)
                PairingResult.PairingFailed -> pairingUi.value = PairingUi(message = PairingMessage.PAIRING_FAILED)
            }
        }
    }

    fun connect(address: String) {
        lastAddress.value = address
        connections.connect(address) // S002/RF-5, con reconexión (S004/RF-13)
    }

    fun reconnect() {
        lastAddress.value?.let(::connect) // S002/RF-9
    }

    fun disconnect() = session.disconnect() // S002/RF-10

    fun retryNow() = session.retryNow() // S004/RF-16

    fun setReplaySpeed(speed: ReplaySpeed) = session.setReplaySpeed(speed) // S003/RF-37

    fun setReplayLoop(loop: Boolean) = session.setReplayLoop(loop) // S003/RF-39

    fun stopReplay() = session.disconnect() // S003/RF-41

    fun replayAgain() {
        replay.playAgain() // S003/RF-40
    }

    fun updatePoleHeight(text: String) {
        poleText.value = text
        PoleHeight.parse(text)?.let { value ->
            poleValue.value = value
            viewModelScope.launch { preferences.savePoleHeight(value) } // S002/RF-30
        }
    }

    private fun readBluetooth(): BluetoothUi = when {
        !bluetooth.isSupported -> BluetoothUi.Unsupported
        !bluetooth.hasConnectPermission() -> BluetoothUi.PermissionNeeded
        !bluetooth.isEnabled -> BluetoothUi.Disabled
        else -> BluetoothUi.Ready(bluetooth.bondedDevices())
    }

    private fun formatPoleHeight(value: Double): String {
        val plain = BigDecimal(value.toString()).setScale(3).toPlainString()
        return plain.replace('.', DecimalFormatSymbols.getInstance().decimalSeparator)
    }

    private fun alertFor(connection: ConnectionState): String? = when (connection) {
        is ConnectionState.Reconnecting -> "reconnecting"
        is ConnectionState.Lost -> "lost"
        else -> null
    }

    companion object {
        const val MONOTONIC_CLOCK = "monotonicClock"
        const val UI_SAMPLE_MS = "receiverUiSampleMs"
        private const val TICK_MS = 1_000L
        private const val STOP_TIMEOUT_MS = 5_000L
    }
}
