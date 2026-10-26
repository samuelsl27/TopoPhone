package es.topophone.data.gear

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.core.model.point.PoleKind
import es.topophone.data.db.TopoPhoneDatabase
import es.topophone.data.receiver.DataStoreReceiverPreferences
import es.topophone.data.settings.SettingsDataStore
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith

/** Al actualizar a S046, la altura de jalón guardada pasa a ser el primer jalón guardado (S046/RF-29). */
@RunWith(AndroidJUnit4::class)
class GearLegacyImportTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val db = Room.inMemoryDatabaseBuilder(context, TopoPhoneDatabase::class.java).build()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val preferences by lazy {
        DataStoreReceiverPreferences(SettingsDataStore.create(scope) { File(folder.root, "receiver.preferences_pb") })
    }
    private val repository = RoomGearRepository(db)
    private val import by lazy { GearLegacyImport(preferences, repository) }

    @After
    fun close() {
        scope.cancel()
        db.close()
    }

    private fun run() = runBlocking { import.run("Jalón estándar", "Jalón anterior") }

    private fun poles() = runBlocking { repository.poles().first() }

    @Test
    fun `S046 RF-29 la altura de 1,8 m guardada pasa a ser el Jalon estandar`() {
        runBlocking { preferences.savePoleHeight(1.8) }

        run()

        poles().map { Triple(it.name, it.kind, it.enteredM) } shouldBe listOf(Triple("Jalón estándar", PoleKind.VERTICAL, 1.8))
    }

    @Test
    fun `S046 RF-29 otra altura guardada pasa a ser el Jalon anterior sin redondear`() {
        runBlocking { preferences.savePoleHeight(1.8725) }

        run()

        poles().map { Triple(it.name, it.kind, it.enteredM) } shouldBe listOf(Triple("Jalón anterior", PoleKind.VERTICAL, 1.8725))
    }

    @Test
    fun `S046 RF-9 sin altura guardada se crea el Jalon estandar de 1,8 m`() {
        run()

        poles().map { it.name to it.enteredM } shouldBe listOf("Jalón estándar" to 1.8)
    }

    @Test
    fun `S046 RF-29 se hace una sola vez y borra el dato antiguo`() {
        runBlocking { preferences.savePoleHeight(2.0) }
        run()
        val id = poles().single().id

        runBlocking { preferences.legacyPoleHeightM() }.shouldBeNull()
        runBlocking { preferences.savePoleHeight(2.5) } // una copia restaurada trae el dato antiguo otra vez
        runBlocking { repository.deletePole(id) } // el último no se borra
        run()

        poles().map { it.enteredM } shouldBe listOf(2.0)
    }

    @Test
    fun `S046 RF-29 si ya hay jalones no se crea ninguno y el dato antiguo se descarta`() {
        runBlocking { repository.savePole(null, "Mío", PoleKind.VERTICAL, "1.5") }
        runBlocking { preferences.savePoleHeight(2.0) }

        run()

        poles().map { it.name } shouldBe listOf("Mío")
        runBlocking { preferences.legacyPoleHeightM() }.shouldBeNull()
    }
}
