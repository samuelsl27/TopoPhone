package es.topophone.receiver.transport

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothDevice
import android.companion.AssociationInfo
import android.companion.AssociationRequest
import android.companion.BluetoothDeviceFilter
import android.companion.CompanionDeviceManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.IntentSender
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Parcelable
import kotlin.coroutines.resume
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

/** Resultado de "Añadir receptor" (S004/RF-1 a RF-4). */
sealed interface PairingResult {
    /** Emparejado ahora o ya antes: se conecta con él (RF-3). */
    data class Paired(val device: BondedDevice) : PairingResult

    /** El usuario cerró la búsqueda sin elegir ningún dispositivo (RF-4). */
    data object SearchCancelled : PairingResult

    /** Emparejamiento cancelado, PIN incorrecto u otro fallo (RF-4). */
    data object PairingFailed : PairingResult
}

/** Busca un receptor con el diálogo del sistema y lo empareja (S004/RF-1, RF-2). */
interface ReceiverPairing {
    /** Sin `companion_device_setup` se recurre a los ajustes de Bluetooth del móvil, la vía de S002. */
    val isSupported: Boolean

    /** Pide al sistema el diálogo de búsqueda; null si no puede buscar (RF-4). */
    suspend fun search(): IntentSender?

    /** Con el resultado del diálogo, empareja el dispositivo elegido si hace falta (RF-2, RF-3, RF-4). */
    suspend fun complete(resultCode: Int, data: Intent?): PairingResult

    companion object {
        /** Sin búsqueda del sistema: solo queda emparejar desde los ajustes de Bluetooth. */
        val Unsupported: ReceiverPairing = object : ReceiverPairing {
            override val isSupported = false

            override suspend fun search(): IntentSender? = null

            override suspend fun complete(resultCode: Int, data: Intent?): PairingResult = PairingResult.SearchCancelled
        }
    }
}

/**
 * CompanionDeviceManager: el sistema busca y muestra los dispositivos, así que la app no necesita
 * el permiso de búsqueda ni el de ubicación. El emparejamiento es el de Android, que pide el PIN.
 */
class AndroidReceiverPairing(private val context: Context) : ReceiverPairing {

    override val isSupported: Boolean
        get() = context.packageManager.hasSystemFeature(PackageManager.FEATURE_COMPANION_DEVICE_SETUP)

    override suspend fun search(): IntentSender? {
        if (!isSupported) return null
        val manager = context.getSystemService(CompanionDeviceManager::class.java) ?: return null
        // Bluetooth clásico y sin filtrar por nombre: cada receptor se llama de una forma.
        val request = AssociationRequest.Builder().addDeviceFilter(BluetoothDeviceFilter.Builder().build()).build()
        return suspendCancellableCoroutine { continuation ->
            fun finish(sender: IntentSender?) {
                if (continuation.isActive) continuation.resume(sender)
            }
            val callback = object : CompanionDeviceManager.Callback() {
                override fun onAssociationPending(intentSender: IntentSender) = finish(intentSender)

                @Suppress("OVERRIDE_DEPRECATION") // Android 12 o anterior solo llama a este
                override fun onDeviceFound(intentSender: IntentSender) = finish(intentSender)

                override fun onFailure(error: CharSequence?) = finish(null)
            }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    manager.associate(request, context.mainExecutor, callback)
                } else {
                    @Suppress("DEPRECATION")
                    manager.associate(request, callback, Handler(Looper.getMainLooper()))
                }
            } catch (_: RuntimeException) {
                finish(null)
            }
        }
    }

    override suspend fun complete(resultCode: Int, data: Intent?): PairingResult {
        if (resultCode != Activity.RESULT_OK) return PairingResult.SearchCancelled
        val device = data?.chosenDevice() ?: return PairingResult.SearchCancelled
        return pair(device)
    }

    @SuppressLint("MissingPermission") // comprobado con hasConnectPermission()
    private suspend fun pair(device: BluetoothDevice): PairingResult {
        if (!hasConnectPermission()) return PairingResult.PairingFailed
        if (device.bondState == BluetoothDevice.BOND_BONDED) return paired(device)
        val bonded = CompletableDeferred<Boolean>()
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.parcelable<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)?.address != device.address) return
                when (intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.ERROR)) {
                    BluetoothDevice.BOND_BONDED -> bonded.complete(true)
                    BluetoothDevice.BOND_NONE -> bonded.complete(false) // cancelado o PIN incorrecto
                }
            }
        }
        register(receiver)
        try {
            // Android muestra su diálogo y pide el PIN del receptor (RF-2).
            if (!device.createBond()) return PairingResult.PairingFailed
            val ok = withTimeoutOrNull(BOND_TIMEOUT_MS) { bonded.await() } == true
            return if (ok) paired(device) else PairingResult.PairingFailed
        } finally {
            context.unregisterReceiver(receiver)
        }
    }

    private fun register(receiver: BroadcastReceiver) {
        val filter = IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
        // Difusión protegida: solo la envía el sistema.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            context.registerReceiver(receiver, filter)
        }
    }

    @SuppressLint("MissingPermission") // comprobado con hasConnectPermission()
    private fun paired(device: BluetoothDevice) = PairingResult.Paired(BondedDevice(device.name ?: device.address, device.address))

    private fun hasConnectPermission(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
        context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    private fun Intent.chosenDevice(): BluetoothDevice? {
        parcelable<BluetoothDevice>(CompanionDeviceManager.EXTRA_DEVICE)?.let { return it }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return null
        return parcelable<AssociationInfo>(CompanionDeviceManager.EXTRA_ASSOCIATION)?.associatedDevice?.bluetoothDevice
    }

    private inline fun <reified T : Parcelable> Intent.parcelable(name: String): T? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            getParcelableExtra(name, T::class.java)
        } else {
            @Suppress("DEPRECATION")
            getParcelableExtra(name)
        }

    private companion object {
        /** El diálogo del PIN tiene su propio plazo; este solo evita esperar para siempre. */
        const val BOND_TIMEOUT_MS = 2 * 60_000L
    }
}
