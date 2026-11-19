package es.topophone.app.experimental

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.data.settings.SettingsDataStore
import io.kotest.matchers.shouldBe
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith

/** S044/RF-3, RF-4, RF-5: el módulo viene apagado, la aceptación guarda su fecha y se olvida al desactivar; plan D4. */
@RunWith(AndroidJUnit4::class)
class ExperimentalPreferencesTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val scopes = mutableListOf<CoroutineScope>()

    @After
    fun close() = scopes.forEach { it.cancel() }

    /** Cierra los DataStore abiertos y espera: dos activos sobre el mismo fichero no se permiten, como al reabrir la app. */
    private fun closeStores() = runBlocking {
        scopes.forEach { it.coroutineContext[Job]!!.cancelAndJoin() }
        scopes.clear()
    }

    private fun preferences(name: String = "experimental.preferences_pb"): ExperimentalPreferences {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO).also { scopes += it }
        return ExperimentalPreferences(SettingsDataStore.create(scope) { File(folder.root, name) })
    }

    @Test
    fun `S044 RF-3 de fabrica el modulo esta apagado`() = runBlocking<Unit> {
        preferences().acceptedAtMs.first() shouldBe null
    }

    @Test
    fun `S044 RF-4 aceptar guarda la fecha y sobrevive a reabrir el fichero`() = runBlocking<Unit> {
        preferences().accept(1_795_082_400_000L)
        closeStores()

        preferences().acceptedAtMs.first() shouldBe 1_795_082_400_000L
    }

    @Test
    fun `S044 RF-5 desactivar olvida la aceptacion`() = runBlocking<Unit> {
        val prefs = preferences()
        prefs.accept(5L)

        prefs.clear()
        prefs.acceptedAtMs.first() shouldBe null
        closeStores()

        preferences().acceptedAtMs.first() shouldBe null
    }

    @Test
    fun `S044 RF-3 un fichero danado se lee como apagado`() = runBlocking<Unit> {
        File(folder.root, "roto.preferences_pb").writeBytes(byteArrayOf(1, 2, 3, 4, 5))

        preferences("roto.preferences_pb").acceptedAtMs.first() shouldBe null
    }

    @Test
    fun `S044 RF-4 el fichero esta en el almacenamiento excluido de las copias`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()

        val file = ExperimentalPreferences.file(context.noBackupFilesDir)

        file.parentFile shouldBe context.noBackupFilesDir
        file.name.endsWith(".preferences_pb") shouldBe true
    }
}
