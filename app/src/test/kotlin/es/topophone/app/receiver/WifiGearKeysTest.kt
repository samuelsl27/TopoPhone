package es.topophone.app.receiver

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.core.model.point.PoleKind
import es.topophone.core.model.point.TiltMode
import es.topophone.core.model.receiver.ReceiverKey
import es.topophone.core.model.receiver.WifiReceiverInput
import es.topophone.core.survey.AntennaCatalog
import es.topophone.data.db.TopoPhoneDatabase
import es.topophone.data.gear.GearResult
import es.topophone.data.gear.RoomGearRepository
import es.topophone.data.receiver.ReceiverTiltSettings
import es.topophone.data.receiver.RoomWifiReceiverRepository
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith

/** Los ajustes de cada receptor (inclinación, jalón y antena) van por su clave: un Wi-Fi y un Bluetooth no comparten (S021/RF-18). */
@RunWith(AndroidJUnit4::class)
class WifiGearKeysTest {

    private val db = Room.inMemoryDatabaseBuilder(
        ApplicationProvider.getApplicationContext<Context>(),
        TopoPhoneDatabase::class.java,
    ).build()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val gear = RoomGearRepository(db)
    private val wifi = RoomWifiReceiverRepository(db)
    private val tiltPreferences = MemoryTiltPreferences()
    private val current = MutableStateFlow<String?>(null)
    private val tilt = CurrentTilt(MutableStateFlow(false), current, tiltPreferences, scope)

    private val bluetooth = "00:11:22:33:44:55"

    @After
    fun tearDown() {
        scope.cancel()
        db.close()
    }

    private fun <T> await(block: suspend () -> T): T = runBlocking { withTimeout(30_000) { block() } }

    private fun saveWifi(host: String = "192.168.42.1", port: Int = 9001) = await { wifi.add(WifiReceiverInput("RS3", host, port, null)) }

    private fun pole(name: String, heightText: String): String =
        await { gear.savePole(null, name, PoleKind.VERTICAL, heightText) }.shouldBeInstanceOf<GearResult.Saved>().id

    @Test
    fun `S021 RF-18 el jalon de un receptor Wi-Fi y el de uno Bluetooth no se mezclan`() {
        val wifiKey = ReceiverKey.wifi(saveWifi())
        val short = pole("Corto", "1.500")
        val long = pole("Largo", "2.000")

        await { gear.setActivePole(wifiKey, long) }.shouldBeInstanceOf<GearResult.Saved>()
        await { gear.setActivePole(bluetooth, short) }.shouldBeInstanceOf<GearResult.Saved>()

        await { gear.gear(wifiKey).first() }.pole!!.id shouldBe long
        await { gear.gear(bluetooth).first() }.pole!!.id shouldBe short
    }

    @Test
    fun `S021 RF-18 la antena de un receptor Wi-Fi no cambia la del Bluetooth`() {
        val wifiKey = ReceiverKey.wifi(saveWifi())
        pole("Corto", "1.500")
        val other = AntennaCatalog.ALL.first { it.ref != es.topophone.core.survey.AntennaProfile.RS3.ref }

        await { gear.setProfile(wifiKey, other.ref) }.shouldBeInstanceOf<GearResult.Saved>()

        await { gear.gear(wifiKey).first() }.profile shouldBe other
        await { gear.gear(bluetooth).first() }.profile shouldBe es.topophone.core.survey.AntennaProfile.RS3
    }

    @Test
    fun `S021 RF-18 el modo de inclinacion de un receptor Wi-Fi tampoco se mezcla con el del Bluetooth`() {
        val wifiKey = ReceiverKey.wifi(saveWifi())
        await { tiltPreferences.save(wifiKey, ReceiverTiltSettings(TiltMode.APP, verified = true, detected = TiltMode.APP)) }

        current.value = bluetooth
        await { tilt.state.first { it.key == bluetooth } }.settings shouldBe ReceiverTiltSettings.NONE
        current.value = wifiKey

        await { tilt.state.first { it.key == wifiKey } }.settings shouldBe
            ReceiverTiltSettings(TiltMode.APP, verified = true, detected = TiltMode.APP)
    }

    @Test
    fun `S021 RF-18 editar la IP o el puerto del receptor Wi-Fi conserva su equipo y su inclinacion`() {
        val id = saveWifi(host = "192.168.0.20", port = 9001)
        val key = ReceiverKey.wifi(id)
        pole("Corto", "1.500")
        val mine = pole("Mi jalon", "1.650")
        await { gear.setActivePole(key, mine) }
        await { tiltPreferences.save(key, ReceiverTiltSettings(TiltMode.RECEIVER, verified = true, detected = TiltMode.RECEIVER)) }

        await { wifi.update(id, WifiReceiverInput("RS3 campo", "192.168.42.1", 9100, 9101)) } shouldBe true

        ReceiverKey.of(await { wifi.byId(id) }!!) shouldBe key
        await { gear.gear(key).first() }.pole!!.id shouldBe mine
        current.value = key
        await { tilt.state.first { it.key == key } }.settings.mode shouldBe TiltMode.RECEIVER
    }

    @Test
    fun `S021 RF-18 dos receptores Wi-Fi tienen cada uno sus ajustes`() {
        val first = ReceiverKey.wifi(saveWifi(host = "192.168.42.1"))
        val second = ReceiverKey.wifi(saveWifi(host = "192.168.42.2"))
        val short = pole("Corto", "1.500")
        val long = pole("Largo", "2.000")
        await { gear.setActivePole(first, long) }

        (first == second) shouldBe false
        await { gear.gear(first).first() }.pole!!.id shouldBe long
        // Cada uno guarda lo suyo, sea cual sea el orden en que salen los jalones.
        await { gear.setActivePole(second, short) }
        await { gear.gear(first).first() }.pole!!.id shouldBe long
        await { gear.gear(second).first() }.pole!!.id shouldBe short
    }

    @Test
    fun `S021 RF-18 el equipo en uso sigue a la clave del receptor conectado, Wi-Fi o Bluetooth`() {
        val wifiKey = ReceiverKey.wifi(saveWifi())
        val short = pole("Corto", "1.500")
        val long = pole("Largo", "2.000")
        await { gear.setActivePole(wifiKey, long) }
        await { gear.setActivePole(bluetooth, short) } // cada clave guarda lo suyo, sea cual sea el orden de los jalones
        val follows = CurrentGear(current, gear, scope)

        current.value = wifiKey
        await { follows.state.first { it.pole?.id == long } }
        current.value = bluetooth

        await { follows.state.first { it.pole?.id == short } }.pole!!.id shouldBe short
    }
}
