package es.topophone.data.crs

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.data.db.TopoPhoneDatabase
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith

/** Los SC favoritos y los últimos elegidos de la biblioteca (S034/RF-6, RF-7, RF-8). */
@RunWith(AndroidJUnit4::class)
class CrsShortcutRepositoryTest {
    private val db = Room.inMemoryDatabaseBuilder(
        ApplicationProvider.getApplicationContext<Context>(),
        TopoPhoneDatabase::class.java,
    ).build()
    private var now = 1_000L
    private val repository = RoomCrsShortcutRepository(db) { now++ }

    @After
    fun tearDown() = db.close()

    @Test
    fun `S034 RF-7 un favorito se marca, se guarda en el movil y se desmarca`() = runBlocking<Unit> {
        repository.setFavorite(2154, true)
        repository.setFavorite(25830, true)

        repository.favorites().first() shouldBe listOf(25830, 2154) // el último marcado primero

        repository.setFavorite(2154, false)
        repository.favorites().first() shouldBe listOf(25830)
    }

    @Test
    fun `S034 RF-7 marcar dos veces el mismo favorito no lo repite`() = runBlocking<Unit> {
        repository.setFavorite(2154, true)
        repository.setFavorite(2154, true)

        repository.favorites().first() shouldBe listOf(2154)
    }

    @Test
    fun `S034 RF-7 un favorito que ya no esta en la biblioteca se conserva por su codigo EPSG`() = runBlocking<Unit> {
        repository.setFavorite(99999999, true)

        repository.favorites().first() shouldBe listOf(99999999)
    }

    @Test
    fun `S034 RF-8 los recientes son los diez ultimos, del mas reciente al mas antiguo y sin repetir`() = runBlocking<Unit> {
        (1..12).forEach { repository.markUsed(30000 + it) }
        repository.markUsed(30005) // vuelve a elegirse: sube al primer puesto sin repetirse

        val recents = repository.recents().first()

        recents.size shouldBe RECENT_LIMIT
        recents.first() shouldBe 30005
        recents.distinct().size shouldBe RECENT_LIMIT
        recents shouldBe listOf(30005, 30012, 30011, 30010, 30009, 30008, 30007, 30006, 30004, 30003)
    }

    @Test
    fun `S034 RF-8 sin nada elegido no hay recientes ni favoritos`() = runBlocking<Unit> {
        repository.recents().first() shouldBe emptyList()
        repository.favorites().first() shouldBe emptyList()
    }
}
