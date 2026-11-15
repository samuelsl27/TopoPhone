package es.topophone.app.receiver

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.core.model.guide.EquipmentProfile
import es.topophone.core.model.receiver.ReceiverKey
import es.topophone.data.db.TopoPhoneDatabase
import es.topophone.data.equipment.RoomEquipmentRepository
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith

/** Los datos del receptor en uso: solo un receptor real los tiene (S043/RF-19, RF-23). */
@RunWith(AndroidJUnit4::class)
class CurrentEquipmentTest {

    private val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), TopoPhoneDatabase::class.java)
        .allowMainThreadQueries()
        .build()
    private val repository = RoomEquipmentRepository(db)
    private val scope = CoroutineScope(Dispatchers.Default)
    private val key = MutableStateFlow<String?>(null)
    private val current = CurrentEquipment(key, repository, scope)
    private val rs3 = EquipmentProfile("Reach RS3", "RS3-0042", "2026.2")

    @After
    fun close() {
        scope.cancel()
        db.close()
    }

    private fun awaitState(expected: EquipmentProfile?) = runBlocking {
        withTimeout(10_000) { current.state.first { it == expected } }
    }

    @Test
    fun `S043 RF-19 cada receptor Bluetooth o Wi-Fi tiene su perfil`() = runBlocking<Unit> {
        repository.save("AA:BB", rs3)
        repository.save(ReceiverKey.wifi("w1"), EquipmentProfile("Reach RS3", "OTRO", "2026.3"))

        key.value = "AA:BB"
        awaitState(rs3)
        key.value = ReceiverKey.wifi("w1")

        awaitState(EquipmentProfile("Reach RS3", "OTRO", "2026.3")) shouldBe EquipmentProfile("Reach RS3", "OTRO", "2026.3")
    }

    @Test
    fun `S043 RF-23 sin receptor, movil, virtual o reproduccion no hay perfil`() = runBlocking<Unit> {
        for (fixed in listOf(null, ReceiverKey.PHONE, ReceiverKey.VIRTUAL, CurrentEquipment.REPLAY_KEY)) {
            if (fixed != null) repository.save(fixed, rs3)
            key.value = "AA:BB"
            repository.save("AA:BB", rs3)
            awaitState(rs3)

            key.value = fixed

            awaitState(null).shouldBeNull()
        }
    }

    @Test
    fun `S043 RF-19 editar el perfil en uso se ve enseguida`() = runBlocking<Unit> {
        key.value = "AA:BB"
        repository.save("AA:BB", rs3)
        awaitState(rs3)

        repository.save("AA:BB", rs3.copy(firmware = "2026.4"))

        awaitState(rs3.copy(firmware = "2026.4"))
    }

    @Test
    fun `S043 RF-23 solo un receptor de verdad admite perfil`() {
        CurrentEquipment.appliesTo("AA:BB:CC:DD:EE:FF") shouldBe true
        CurrentEquipment.appliesTo(ReceiverKey.wifi("w1")) shouldBe true
        CurrentEquipment.appliesTo(null) shouldBe false
        CurrentEquipment.appliesTo(ReceiverKey.PHONE) shouldBe false
        CurrentEquipment.appliesTo(ReceiverKey.VIRTUAL) shouldBe false
        CurrentEquipment.appliesTo(CurrentEquipment.REPLAY_KEY) shouldBe false
    }
}
