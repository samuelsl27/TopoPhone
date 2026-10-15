package es.topophone.receiver.transport

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

/** Enlace de bytes con el receptor (Bluetooth SPP en S002; TCP en S020; reproducción en S003). */
interface ReceiverTransport : Closeable {
    val description: String

    /** Abre la conexión. Bloquea: se llama desde un hilo de E/S. */
    fun open()

    val input: InputStream
    val output: OutputStream

    /** Receptor simulado (reproducción): sin grabación ni correcciones (S003/RF-44, S003/RF-45). */
    val simulated: Boolean get() = false
}

/** El Bluetooth del móvil está apagado: no se puede abrir el receptor (S004/RF-22). */
class BluetoothOffException : IOException("Bluetooth apagado")

/** Transporte sobre flujos ya abiertos (tests y, más adelante, reproducción de sesiones). */
class StreamTransport(override val description: String, override val input: InputStream, override val output: OutputStream) :
    ReceiverTransport {
    override fun open() = Unit

    override fun close() {
        runCatching { input.close() }
        runCatching { output.close() }
    }
}

/** Dispositivo Bluetooth emparejado con el móvil (S002/RF-1). */
data class BondedDevice(val name: String, val address: String)

/** Acceso al Bluetooth del móvil (S002/RF-1 a RF-5). */
interface BluetoothDevices {
    val isSupported: Boolean
    val isEnabled: Boolean

    /** Android 12+ exige `BLUETOOTH_CONNECT` en tiempo de ejecución (RNF-T1). */
    fun hasConnectPermission(): Boolean

    /** Emparejados ordenados por nombre; vacío sin permiso o sin Bluetooth. */
    fun bondedDevices(): List<BondedDevice>

    fun transportFor(address: String): ReceiverTransport
}

class AndroidBluetoothDevices(private val context: Context) : BluetoothDevices {

    private val adapter: BluetoothAdapter? get() = context.getSystemService(BluetoothManager::class.java)?.adapter

    override val isSupported: Boolean get() = adapter != null

    override val isEnabled: Boolean get() = adapter?.isEnabled == true

    override fun hasConnectPermission(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
        context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission") // comprobado con hasConnectPermission()
    override fun bondedDevices(): List<BondedDevice> {
        if (!hasConnectPermission()) return emptyList()
        val devices = runCatching { adapter?.bondedDevices }.getOrNull().orEmpty()
        return devices.map { BondedDevice(name = it.name ?: it.address, address = it.address) }.sortedBy { it.name.lowercase() }
    }

    @SuppressLint("MissingPermission")
    override fun transportFor(address: String): ReceiverTransport {
        val adapter = adapter ?: throw IOException("Bluetooth no disponible")
        // Se usa el dispositivo emparejado (con su nombre) y, si no está, el remoto por dirección.
        val bonded = if (hasConnectPermission()) {
            runCatching {
                adapter.bondedDevices
            }.getOrNull()?.firstOrNull { it.address == address }
        } else {
            null
        }
        val device = bonded ?: adapter.getRemoteDevice(address)
        val name = if (hasConnectPermission()) device.name ?: address else address
        return BluetoothSppTransport(device, "$name ($address)")
    }
}

/**
 * Perfil serie Bluetooth (SPP) con el receptor (S002/RF-5). Si el canal seguro por SDP falla,
 * reintenta con el canal RFCOMM 1, como hacen otras apps con los Reach.
 */
@SuppressLint("MissingPermission")
class BluetoothSppTransport(private val device: BluetoothDevice, override val description: String) : ReceiverTransport {

    private var socket: BluetoothSocket? = null

    override fun open() {
        socket = try {
            device.createRfcommSocketToServiceRecord(SPP_UUID).also { it.connect() }
        } catch (first: IOException) {
            runCatching { socket?.close() }
            try {
                val method = device.javaClass.getMethod("createRfcommSocket", Int::class.javaPrimitiveType)
                (method.invoke(device, 1) as BluetoothSocket).also { it.connect() }
            } catch (_: Exception) {
                throw first
            }
        }
    }

    override val input: InputStream get() = socket?.inputStream ?: throw IOException("No conectado")
    override val output: OutputStream get() = socket?.outputStream ?: throw IOException("No conectado")

    override fun close() {
        runCatching { socket?.close() }
        socket = null
    }

    companion object {
        val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    }
}
