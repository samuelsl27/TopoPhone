package es.topophone.app.ui.receiver

import es.topophone.core.gnss.nmea.NmeaChecksum
import es.topophone.data.receiver.NtripPreferences
import es.topophone.data.receiver.ReceiverPreferences
import es.topophone.data.receiver.SavedReceiver
import es.topophone.receiver.platform.Alerts
import es.topophone.receiver.transport.BluetoothDevices
import es.topophone.receiver.transport.BondedDevice
import es.topophone.receiver.transport.ReceiverTransport
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow

/** Dobles de prueba de la sección Receptor. */
internal val RS3_DEVICE = BondedDevice("reach-RS3", "00:11:22:33:44:55")

internal class FakeBluetooth : BluetoothDevices {
    var permission = true
    var enabled = true
    val transports = mutableMapOf<String, ReceiverTransport>()
    override val isSupported = true
    override val isEnabled get() = enabled
    override fun hasConnectPermission() = permission
    override fun bondedDevices() = listOf(RS3_DEVICE)
    override fun transportFor(address: String) = transports.getValue(address)
}

/** Avisos que no hacen nada, para los tests que no los miran. */
internal object NoAlerts : Alerts {
    override fun connectionLost() = Unit

    override fun correctionsLost() = Unit
}

internal class FakePreferences : ReceiverPreferences {
    val ntripFlow = MutableStateFlow(NtripPreferences())
    val poleFlow = MutableStateFlow(1.8)
    val sessionSpaceFlow = MutableStateFlow(500)
    override val ntrip = ntripFlow
    override val poleHeightM = poleFlow
    override val sessionSpaceMb = sessionSpaceFlow

    override suspend fun saveSessionSpaceMb(mb: Int) {
        sessionSpaceFlow.value = mb
    }

    val lastReceiverFlow = MutableStateFlow<SavedReceiver?>(null)
    val notificationsAskedFlow = MutableStateFlow(false)
    override val lastReceiver = lastReceiverFlow
    override val notificationsAsked = notificationsAskedFlow

    override suspend fun saveLastReceiver(receiver: SavedReceiver) {
        lastReceiverFlow.value = receiver
    }

    override suspend fun markNotificationsAsked() {
        notificationsAskedFlow.value = true
    }

    override suspend fun saveNtrip(preferences: NtripPreferences) {
        ntripFlow.value = preferences
    }

    override suspend fun savePoleHeight(heightM: Double) {
        poleFlow.value = heightM
    }

    val lastNtripProfileFlow = MutableStateFlow<String?>(null)
    val ntripWasActiveFlow = MutableStateFlow(false)
    val correctionsAlarmFlow = MutableStateFlow(10)
    override val lastNtripProfileId = lastNtripProfileFlow
    override val ntripWasActive = ntripWasActiveFlow
    override val correctionsAlarmS = correctionsAlarmFlow

    override suspend fun saveNtripSession(profileId: String?, active: Boolean) {
        lastNtripProfileFlow.value = profileId
        ntripWasActiveFlow.value = active
    }

    override suspend fun saveCorrectionsAlarmS(seconds: Int) {
        correctionsAlarmFlow.value = seconds
    }

    override suspend fun legacyNtrip(): NtripPreferences? = null

    override suspend fun markLegacyNtripImported() = Unit
}

internal fun nmea(body: String) = "$" + body + "*" + "%02X".format(NmeaChecksum.compute(body)) + "\r\n"

/** Raíz del repositorio, para leer `fixtures/`. */
internal val repositoryRoot: File by lazy {
    generateSequence(File("").absoluteFile) { it.parentFile }
        .firstOrNull { File(it, "settings.gradle.kts").exists() && File(it, "fixtures").isDirectory }
        ?: error("No se encuentra la raíz del repositorio")
}
