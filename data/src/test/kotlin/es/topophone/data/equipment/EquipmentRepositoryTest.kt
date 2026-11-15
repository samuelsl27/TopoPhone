package es.topophone.data.equipment

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.core.model.guide.EquipmentProfile
import es.topophone.data.db.TopoPhoneDatabase
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith

/** Los datos de cada receptor y su dirección web se guardan por receptor y por separado (S043/RF-13, RF-18). */
@RunWith(AndroidJUnit4::class)
class EquipmentRepositoryTest {

    private val db = Room.inMemoryDatabaseBuilder(
        ApplicationProvider.getApplicationContext<Context>(),
        TopoPhoneDatabase::class.java,
    ).build()
    private val repository = RoomEquipmentRepository(db)

    @After
    fun close() = db.close()

    private fun profile(key: String) = runBlocking { repository.profile(key).first() }

    private fun address(key: String) = runBlocking { repository.webAddress(key).first() }

    @Test
    fun `S043 RF-18 un receptor sin datos no tiene perfil ni direccion`() {
        profile("AA:BB").shouldBeNull()
        address("AA:BB").shouldBeNull()
    }

    @Test
    fun `S043 RF-18 el perfil se guarda sin espacios en los extremos y se lee de vuelta`() = runBlocking<Unit> {
        repository.save("AA:BB", EquipmentProfile(" Reach RS3 ", "SN-123 ", " 2026.1"))

        profile("AA:BB") shouldBe EquipmentProfile("Reach RS3", "SN-123", "2026.1")
    }

    @Test
    fun `S043 RF-18 guardar otra vez cambia el perfil y cada receptor lleva el suyo`() = runBlocking<Unit> {
        repository.save("AA:BB", EquipmentProfile("Reach RS3", "1", "a"))
        repository.save("wifi:w1", EquipmentProfile("Reach RS3", "2", "b"))
        repository.save("AA:BB", EquipmentProfile("Reach RS3", "1", "c"))

        profile("AA:BB") shouldBe EquipmentProfile("Reach RS3", "1", "c")
        profile("wifi:w1") shouldBe EquipmentProfile("Reach RS3", "2", "b")
    }

    @Test
    fun `S043 RF-18 un perfil en blanco borra el guardado`() = runBlocking<Unit> {
        repository.save("AA:BB", EquipmentProfile("Reach RS3", "1", "a"))

        repository.save("AA:BB", EquipmentProfile("  ", "", " "))

        profile("AA:BB").shouldBeNull()
    }

    @Test
    fun `S043 RF-13 la direccion web se guarda sin espacios y no toca el perfil`() = runBlocking<Unit> {
        repository.save("AA:BB", EquipmentProfile("Reach RS3", "1", "a"))

        repository.saveWebAddress("AA:BB", " 192.168.42.1:8080 ")

        address("AA:BB") shouldBe "192.168.42.1:8080"
        profile("AA:BB") shouldBe EquipmentProfile("Reach RS3", "1", "a")
    }

    @Test
    fun `S043 RF-13 guardar el perfil no borra la direccion web y la direccion sola no crea un perfil`() = runBlocking<Unit> {
        repository.saveWebAddress("wifi:w1", "192.168.42.1")

        profile("wifi:w1").shouldBeNull()
        repository.save("wifi:w1", EquipmentProfile("Reach RS3", "", ""))

        address("wifi:w1") shouldBe "192.168.42.1"
        profile("wifi:w1") shouldBe EquipmentProfile("Reach RS3", "", "")
    }

    @Test
    fun `S043 RF-13 una direccion vacia borra la direccion y deja el perfil`() = runBlocking<Unit> {
        repository.save("AA:BB", EquipmentProfile("Reach RS3", "1", "a"))
        repository.saveWebAddress("AA:BB", "192.168.42.1")

        repository.saveWebAddress("AA:BB", "  ")

        address("AA:BB").shouldBeNull()
        profile("AA:BB") shouldBe EquipmentProfile("Reach RS3", "1", "a")
    }
}
