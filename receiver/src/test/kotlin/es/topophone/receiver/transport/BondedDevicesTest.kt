package es.topophone.receiver.transport

import android.Manifest
import android.app.Application
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowBluetoothDevice

/** Dispositivos Bluetooth emparejados y transporte (S002/RF-1, S002/RF-3, S002/RF-4, S002/RF-5). */
@RunWith(AndroidJUnit4::class)
class BondedDevicesTest {

    private val context: Application = ApplicationProvider.getApplicationContext()
    private val adapter: BluetoothAdapter get() = context.getSystemService(BluetoothManager::class.java).adapter

    private fun device(address: String, name: String): BluetoothDevice =
        ShadowBluetoothDevice.newInstance(address).also { shadowOf(it).setName(name) }

    @Before
    fun setUp() {
        shadowOf(context).grantPermissions(Manifest.permission.BLUETOOTH_CONNECT)
        shadowOf(adapter).setEnabled(true)
        shadowOf(adapter).setBondedDevices(setOf(device("00:11:22:33:44:55", "reach-RS3"), device("AA:BB:CC:DD:EE:FF", "Auriculares")))
    }

    @Test
    fun `S002 RF-1 la lista de emparejados muestra nombre y direccion ordenados por nombre`() {
        AndroidBluetoothDevices(context).bondedDevices() shouldBe listOf(
            BondedDevice(name = "Auriculares", address = "AA:BB:CC:DD:EE:FF"),
            BondedDevice(name = "reach-RS3", address = "00:11:22:33:44:55"),
        )
    }

    @Test
    @Config(sdk = [36])
    fun `S002 RF-3 sin el permiso de Bluetooth no se lista nada y se indica`() {
        shadowOf(context).denyPermissions(Manifest.permission.BLUETOOTH_CONNECT)
        val devices = AndroidBluetoothDevices(context)

        devices.hasConnectPermission() shouldBe false
        devices.bondedDevices().shouldBeEmpty()
    }

    @Test
    @Config(sdk = [28])
    fun `S002 RF-3 en Android 11 o inferior no hace falta permiso en tiempo de ejecucion`() {
        AndroidBluetoothDevices(context).hasConnectPermission() shouldBe true
    }

    @Test
    fun `S002 RF-4 se detecta el Bluetooth apagado`() {
        shadowOf(adapter).setEnabled(false)

        AndroidBluetoothDevices(context).isEnabled shouldBe false
    }

    @Test
    fun `S002 RF-5 el transporte de flujos entrega los bytes en los dos sentidos`() {
        val output = ByteArrayOutputStream()
        val transport = StreamTransport("prueba", ByteArrayInputStream(byteArrayOf(1, 2, 3)), output)

        transport.open()
        transport.input.readBytes().toList() shouldBe listOf<Byte>(1, 2, 3)
        transport.output.write(byteArrayOf(9))

        output.toByteArray().toList() shouldBe listOf<Byte>(9)
        transport.description shouldBe "prueba"
    }

    @Test
    fun `S002 RF-5 el transporte Bluetooth usa el perfil serie SPP`() {
        BluetoothSppTransport.SPP_UUID.toString() shouldBe "00001101-0000-1000-8000-00805f9b34fb"
        AndroidBluetoothDevices(context).transportFor("00:11:22:33:44:55").description shouldBe "reach-RS3 (00:11:22:33:44:55)"
    }
}
