package es.topophone.receiver.transport

import android.Manifest
import android.app.Activity
import android.app.Application
import android.app.PendingIntent
import android.bluetooth.BluetoothDevice
import android.companion.CompanionDeviceManager
import android.content.Intent
import android.content.IntentSender
import android.content.pm.PackageManager
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowBluetoothDevice
import org.robolectric.shadows.ShadowCompanionDeviceManager

/** Buscar y emparejar un receptor nuevo (S004/RF-1, RF-2, RF-3, RF-4). */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class ReceiverPairingTest {

    private val context: Application = ApplicationProvider.getApplicationContext()
    private val companion: ShadowCompanionDeviceManager get() = shadowOf(context.getSystemService(CompanionDeviceManager::class.java))
    private val pairing = AndroidReceiverPairing(context)

    private val rs3: BluetoothDevice = ShadowBluetoothDevice.newInstance("00:11:22:33:44:55").also { shadowOf(it).setName("reach-RS3") }

    @Before
    fun setUp() {
        shadowOf(context).grantPermissions(Manifest.permission.BLUETOOTH_CONNECT)
        shadowOf(context.packageManager).setSystemFeature(PackageManager.FEATURE_COMPANION_DEVICE_SETUP, true)
    }

    /** Arranca la llamada hasta su primera espera; lo que la reanuda llega por el hilo principal. */
    private fun <T> start(block: suspend () -> T): Deferred<T> = CoroutineScope(Dispatchers.Unconfined).async { block() }

    private fun chooser(): IntentSender = PendingIntent.getActivity(context, 0, Intent(), PendingIntent.FLAG_IMMUTABLE).intentSender

    private fun chosen(device: BluetoothDevice) = Intent().putExtra(CompanionDeviceManager.EXTRA_DEVICE, device)

    private fun bondChanged(device: BluetoothDevice, state: Int) {
        context.sendBroadcast(
            Intent(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
                .putExtra(BluetoothDevice.EXTRA_DEVICE, device)
                .putExtra(BluetoothDevice.EXTRA_BOND_STATE, state),
        )
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test
    fun `S004 RF-1 con companion_device_setup se busca con el dialogo del sistema`() {
        pairing.isSupported shouldBe true

        val search = start { pairing.search() }
        companion.lastAssociationRequest.shouldNotBeNull()
        val sender = chooser()
        companion.lastAssociationCallback.onAssociationPending(sender)

        search.getCompleted() shouldBe sender
    }

    @Test
    @Config(sdk = [28])
    fun `S004 RF-1 en Android 12 o anterior la busqueda tambien usa el dialogo del sistema`() {
        val search = start { pairing.search() }
        val sender = chooser()
        @Suppress("DEPRECATION")
        companion.lastAssociationCallback.onDeviceFound(sender)

        search.getCompleted() shouldBe sender
    }

    @Test
    fun `S004 RF-4 si el sistema no puede buscar se indica sin dialogo`() {
        val search = start { pairing.search() }

        companion.lastAssociationCallback.onFailure("sin Bluetooth")

        search.getCompleted() shouldBe null
    }

    @Test
    fun `S004 RF-1 sin companion_device_setup se usa la reserva de los ajustes de Bluetooth`() {
        shadowOf(context.packageManager).setSystemFeature(PackageManager.FEATURE_COMPANION_DEVICE_SETUP, false)

        AndroidReceiverPairing(context).isSupported shouldBe false
    }

    @Test
    fun `S004 RF-4 si se cierra la busqueda sin elegir se indica que se ha cancelado`() {
        start { pairing.complete(Activity.RESULT_CANCELED, null) }.getCompleted() shouldBe PairingResult.SearchCancelled
    }

    @Test
    fun `S004 RF-3 si el elegido ya estaba emparejado se conecta sin emparejar`() {
        shadowOf(rs3).setBondState(BluetoothDevice.BOND_BONDED)

        val result = start { pairing.complete(Activity.RESULT_OK, chosen(rs3)) }

        result.getCompleted() shouldBe PairingResult.Paired(BondedDevice("reach-RS3", "00:11:22:33:44:55"))
    }

    @Test
    fun `S004 RF-2 S004 RF-3 si no esta emparejado se empareja con el PIN y al terminar se conecta`() {
        shadowOf(rs3).setBondState(BluetoothDevice.BOND_NONE)
        shadowOf(rs3).setCreatedBond(true)

        val result = start { pairing.complete(Activity.RESULT_OK, chosen(rs3)) }
        result.isCompleted shouldBe false // el sistema pide el PIN
        bondChanged(rs3, BluetoothDevice.BOND_BONDING)
        result.isCompleted shouldBe false
        bondChanged(rs3, BluetoothDevice.BOND_BONDED)

        result.getCompleted() shouldBe PairingResult.Paired(BondedDevice("reach-RS3", "00:11:22:33:44:55"))
    }

    @Test
    fun `S004 RF-4 si el emparejamiento se cancela o el PIN es incorrecto se indica`() {
        shadowOf(rs3).setBondState(BluetoothDevice.BOND_NONE)
        shadowOf(rs3).setCreatedBond(true)

        val result = start { pairing.complete(Activity.RESULT_OK, chosen(rs3)) }
        bondChanged(rs3, BluetoothDevice.BOND_BONDING)
        bondChanged(rs3, BluetoothDevice.BOND_NONE)

        result.getCompleted() shouldBe PairingResult.PairingFailed
    }

    @Test
    fun `S004 RF-4 si el emparejamiento no llega a empezar se indica`() {
        shadowOf(rs3).setBondState(BluetoothDevice.BOND_NONE)
        shadowOf(rs3).setCreatedBond(false)

        start { pairing.complete(Activity.RESULT_OK, chosen(rs3)) }.getCompleted() shouldBe PairingResult.PairingFailed
    }

    @Test
    fun `S004 RF-2 los cambios de otro dispositivo no cuentan`() {
        shadowOf(rs3).setBondState(BluetoothDevice.BOND_NONE)
        shadowOf(rs3).setCreatedBond(true)
        val other = ShadowBluetoothDevice.newInstance("AA:BB:CC:DD:EE:FF")

        val result = start { pairing.complete(Activity.RESULT_OK, chosen(rs3)) }
        bondChanged(other, BluetoothDevice.BOND_BONDED)

        result.isCompleted shouldBe false
        bondChanged(rs3, BluetoothDevice.BOND_BONDED)
        result.isCompleted shouldBe true
    }
}
