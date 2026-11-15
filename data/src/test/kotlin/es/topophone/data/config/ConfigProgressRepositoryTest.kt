package es.topophone.data.config

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.core.model.guide.ConfigListId
import es.topophone.data.db.TopoPhoneDatabase
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith

/** Los pasos hechos de cada lista se guardan por receptor y por lista (S043/RF-3). */
@RunWith(AndroidJUnit4::class)
class ConfigProgressRepositoryTest {

    private val db = Room.inMemoryDatabaseBuilder(
        ApplicationProvider.getApplicationContext<Context>(),
        TopoPhoneDatabase::class.java,
    ).build()
    private val repository = RoomConfigProgressRepository(db, clock = { 7_000 })

    @After
    fun close() = db.close()

    private fun done(key: String, list: ConfigListId) = runBlocking { repository.done(key, list).first() }

    @Test
    fun `S043 RF-3 un receptor sin pasos hechos empieza con la lista vacia`() {
        done("AA:BB", ConfigListId.NTRIP_ROVER) shouldBe emptySet()
    }

    @Test
    fun `S043 RF-3 marcar un paso lo deja hecho y marcarlo otra vez no lo duplica`() = runBlocking<Unit> {
        repository.setDone("AA:BB", ConfigListId.NTRIP_ROVER, "ntrip_rate", true)
        repository.setDone("AA:BB", ConfigListId.NTRIP_ROVER, "ntrip_rate", true)
        repository.setDone("AA:BB", ConfigListId.NTRIP_ROVER, "ntrip_fix", true)

        done("AA:BB", ConfigListId.NTRIP_ROVER) shouldBe setOf("ntrip_rate", "ntrip_fix")
    }

    @Test
    fun `S043 RF-3 desmarcar un paso borra solo ese paso`() = runBlocking<Unit> {
        repository.setDone("AA:BB", ConfigListId.NTRIP_ROVER, "ntrip_rate", true)
        repository.setDone("AA:BB", ConfigListId.NTRIP_ROVER, "ntrip_fix", true)

        repository.setDone("AA:BB", ConfigListId.NTRIP_ROVER, "ntrip_rate", false)

        done("AA:BB", ConfigListId.NTRIP_ROVER) shouldBe setOf("ntrip_fix")
    }

    @Test
    fun `S043 RF-3 cada receptor y cada lista llevan su progreso aparte`() = runBlocking<Unit> {
        repository.setDone("AA:BB", ConfigListId.NTRIP_ROVER, "ntrip_fix", true)
        repository.setDone("wifi:w1", ConfigListId.NTRIP_ROVER, "ntrip_rate", true)
        repository.setDone("AA:BB", ConfigListId.WIFI_ROVER, "wifi_tcp", true)

        done("AA:BB", ConfigListId.NTRIP_ROVER) shouldBe setOf("ntrip_fix")
        done("wifi:w1", ConfigListId.NTRIP_ROVER) shouldBe setOf("ntrip_rate")
        done("AA:BB", ConfigListId.WIFI_ROVER) shouldBe setOf("wifi_tcp")
    }

    @Test
    fun `S043 RF-3 reiniciar una lista borra sus pasos y deja las demas`() = runBlocking<Unit> {
        repository.setDone("AA:BB", ConfigListId.NTRIP_ROVER, "ntrip_fix", true)
        repository.setDone("AA:BB", ConfigListId.WIFI_ROVER, "wifi_tcp", true)
        repository.setDone("wifi:w1", ConfigListId.NTRIP_ROVER, "ntrip_rate", true)

        repository.reset("AA:BB", ConfigListId.NTRIP_ROVER)

        done("AA:BB", ConfigListId.NTRIP_ROVER) shouldBe emptySet()
        done("AA:BB", ConfigListId.WIFI_ROVER) shouldBe setOf("wifi_tcp")
        done("wifi:w1", ConfigListId.NTRIP_ROVER) shouldBe setOf("ntrip_rate")
    }
}
