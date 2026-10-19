package es.topophone.data.log

import es.topophone.data.settings.SettingsDataStore
import io.kotest.matchers.shouldBe
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Tamaño del log elegido en Ajustes: de 5 a 50 MB, 10 MB por defecto (S014/RF-8, RF-9). */
class LogPreferencesTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val file: File by lazy { File(folder.root, "settings.preferences_pb") }

    private fun <T> withPreferences(block: suspend (DataStoreLogPreferences) -> T): T = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            block(DataStoreLogPreferences(SettingsDataStore.create(scope) { file }))
        } finally {
            scope.coroutineContext.job.cancelAndJoin()
        }
    }

    @Test
    fun `S014 RF-8 de fabrica el log ocupa 10 MB como mucho`() {
        withPreferences { it.sizeMb.first() } shouldBe 10
    }

    @Test
    fun `S014 RF-9 el tamano elegido se recuerda`() {
        withPreferences { it.saveSizeMb(50) }

        withPreferences { it.sizeMb.first() } shouldBe 50
    }

    @Test
    fun `S014 RF-8 fuera de 5 a 50 MB se queda en el borde`() {
        withPreferences {
            it.saveSizeMb(500)
            it.sizeMb.first()
        } shouldBe 50
        withPreferences {
            it.saveSizeMb(1)
            it.sizeMb.first()
        } shouldBe 5
    }
}
