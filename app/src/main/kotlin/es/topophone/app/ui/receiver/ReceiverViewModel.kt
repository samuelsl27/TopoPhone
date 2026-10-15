package es.topophone.app.ui.receiver

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.Lazy
import dagger.hilt.android.lifecycle.HiltViewModel
import es.topophone.app.receiver.ReceiverConnections
import es.topophone.core.gnss.nmea.ReplaySpeed
import es.topophone.core.ntrip.NtripConfig
import es.topophone.core.ntrip.NtripFailure
import es.topophone.core.survey.GroundPoint
import es.topophone.core.survey.GroundPointCalculator
import es.topophone.core.survey.PoleHeight
import es.topophone.data.receiver.NtripPreferences
import es.topophone.data.receiver.ReceiverPreferences
import es.topophone.receiver.platform.Alerts
import es.topophone.receiver.platform.NetworkStatus
import es.topophone.receiver.platform.SessionFiles
import es.topophone.receiver.session.ConnectionState
import es.topophone.receiver.session.NtripState
import es.topophone.receiver.session.ReceiverSession
import es.topophone.receiver.session.ReceiverSnapshot
import es.topophone.receiver.session.ReplayController
import es.topophone.receiver.transport.BluetoothDevices
import es.topophone.receiver.transport.BondedDevice
import java.io.File
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
import kotlinx.coroutines.launch

/** Estado del Bluetooth del móvil (S002/RF-1 a RF-4). */
sealed interface BluetoothUi {
    data object Unsupported : BluetoothUi

    data object PermissionNeeded : BluetoothUi

    data object Disabled : BluetoothUi

    data class Ready(val devices: List<BondedDevice>) : BluetoothUi
}

/** Formulario del caster tal como lo escribe el usuario (S002/RF-18). La contraseña solo vive aquí. */
data class NtripForm(
    val host: String = "",
    val port: String = NtripConfig.DEFAULT_PORT.toString(),
    val mountpoint: String = "",
    val username: String = "",
    val password: String = "",
) {
    fun toConfig(): NtripConfig? {
        val portNumber = port.trim().toIntOrNull()?.takeIf { it in 1..MAX_PORT } ?: return null
        if (host.isBlank() || mountpoint.isBlank()) return null
        return NtripConfig(host.trim(), portNumber, mountpoint.trim(), username.trim(), password)
    }

    private companion object {
        const val MAX_PORT = 65535
    }
}

data class ReceiverUiState(
    val bluetooth: BluetoothUi = BluetoothUi.PermissionNeeded,
    val snapshot: ReceiverSnapshot = ReceiverSnapshot(),
    val lastAddress: String? = null,
    val form: NtripForm = NtripForm(),
    val formInvalid: Boolean = false,
    val internetAvailable: Boolean = true,
    val poleHeightText: String = "",
    val poleHeightValid: Boolean = true,
    val groundPoint: GroundPoint? = null,
    val sessionAvailable: Boolean = false,
    val nowMs: Long = 0,
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
    private val sessionFiles: SessionFiles,
    @param:Named(MONOTONIC_CLOCK) private val clock: () -> Long,
    @param:Named(UI_SAMPLE_MS) private val samplePeriodMs: Long,
    private val replay: ReplayController,
    private val connections: ReceiverConnections,
) : ViewModel() {

    private val bluetoothUi = MutableStateFlow(readBluetooth())
    private val lastAddress = MutableStateFlow<String?>(null)
    private val form = MutableStateFlow(NtripForm())
    private val formInvalid = MutableStateFlow(false)
    private val internet = MutableStateFlow(true)
    private val poleText = MutableStateFlow(formatPoleHeight(PoleHeight.DEFAULT_M))
    private val poleValue = MutableStateFlow(PoleHeight.DEFAULT_M)

    private val ticker: Flow<Long> = flow {
        while (true) {
            emit(clock())
            delay(TICK_MS)
        }
    }

    private val snapshots: Flow<ReceiverSnapshot> = if (samplePeriodMs > 0) session.state.sample(samplePeriodMs) else session.state

    private data class FormState(val form: NtripForm, val invalid: Boolean, val internet: Boolean)
    private data class PoleState(val text: String, val value: Double)
    private data class Extras(val nowMs: Long, val sessionAvailable: Boolean, val lastAddress: String?)

    val uiState: StateFlow<ReceiverUiState> = combine(
        snapshots,
        bluetoothUi,
        combine(form, formInvalid, internet, ::FormState),
        combine(poleText, poleValue, ::PoleState),
        combine(
            ticker.map {
                it to (sessionFiles.latest() != null)
            },
            lastAddress,
        ) { (now, available), address -> Extras(now, available, address) },
    ) { snapshot, bt, formState, pole, extras ->
        ReceiverUiState(
            bluetooth = bt,
            snapshot = snapshot,
            lastAddress = extras.lastAddress,
            form = formState.form,
            formInvalid = formState.invalid,
            internetAvailable = formState.internet,
            poleHeightText = pole.text,
            poleHeightValid = PoleHeight.parse(pole.text) != null,
            groundPoint = snapshot.gga?.let { calculator.get().compute(it, pole.value) },
            sessionAvailable = extras.sessionAvailable,
            nowMs = extras.nowMs,
        )
    }.flowOn(Dispatchers.Default).stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
        ReceiverUiState(bluetooth = bluetoothUi.value, snapshot = session.state.value, poleHeightText = poleText.value),
    )

    init {
        // El geoide se descodifica la primera vez que se usa: fuera del hilo principal y antes de la
        // primera GGA, para no bloquear la interfaz ni retrasar las primeras coordenadas (RNF-T13).
        viewModelScope.launch(Dispatchers.Default) { calculator.get() }
        viewModelScope.launch {
            val saved = preferences.ntrip.first()
            form.value = NtripForm(saved.host, saved.port.toString(), saved.mountpoint, saved.username)
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
                if (state == NtripState.Stopped(NtripFailure.UNREACHABLE)) internet.value = network.hasInternet() // S002/RF-23
            }
        }
    }

    /** Se llama al volver a la pantalla y tras pedir el permiso o activar el Bluetooth (S002/RF-1 a RF-4). */
    fun refreshBluetooth() {
        bluetoothUi.value = readBluetooth()
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

    fun updateForm(newForm: NtripForm) {
        form.value = newForm
        formInvalid.value = false
    }

    fun connectNtrip() {
        val config = form.value.toConfig()
        if (config == null) {
            formInvalid.value = true
            return
        }
        internet.value = true
        viewModelScope.launch {
            preferences.saveNtrip(NtripPreferences(config.host, config.port, config.mountpoint, config.username))
        } // S002/RF-26
        session.startNtrip(config) // S002/RF-19, S002/RF-28
    }

    fun disconnectNtrip() = session.stopNtrip()

    fun updatePoleHeight(text: String) {
        poleText.value = text
        PoleHeight.parse(text)?.let { value ->
            poleValue.value = value
            viewModelScope.launch { preferences.savePoleHeight(value) } // S002/RF-30
        }
    }

    fun latestSession(): List<File>? = sessionFiles.latest()

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
