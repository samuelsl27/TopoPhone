package es.topophone.data.backup

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

/** Estado de las copias automáticas de este móvil (S013/RF-17, RF-18, RF-20, RF-21, RF-23). */
class AutoBackupPreferencesTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val file: File by lazy { File(folder.root, "backup.preferences_pb") }

    private fun <T> withPreferences(block: suspend (DataStoreAutoBackupPreferences) -> T): T = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            block(DataStoreAutoBackupPreferences(SettingsDataStore.create(scope) { file }))
        } finally {
            scope.coroutineContext.job.cancelAndJoin()
        }
    }

    @Test
    fun `S013 RF-17 de fabrica no hay carpeta ni copias`() {
        withPreferences { it.state.first() } shouldBe AutoBackupState()
    }

    @Test
    fun `S013 RF-17 elegir la carpeta la recuerda y quitarla la olvida`() {
        withPreferences { it.setFolder("content://tree/copias") }
        withPreferences { it.state.first().folderUri } shouldBe "content://tree/copias"

        withPreferences {
            it.recordFailure()
            it.setFolder(null)
            it.state.first()
        } shouldBe AutoBackupState(pendingChanges = true)
    }

    @Test
    fun `S013 RF-20 y RF-21 una copia buena se recuerda y borra el fallo`() {
        val state = withPreferences {
            it.markPending()
            it.recordFailure()
            it.state.first().failing shouldBe true
            it.recordSuccess(1_000)
            it.clearPending()
            it.state.first()
        }

        state shouldBe AutoBackupState(lastSuccessMs = 1_000)
    }

    @Test
    fun `S013 RF-21 un fallo deja los cambios pendientes para reintentar`() {
        withPreferences {
            it.recordFailure()
            it.state.first()
        } shouldBe AutoBackupState(failing = true, pendingChanges = true)
    }

    @Test
    fun `S013 RF-23 cerrar el aviso se recuerda`() {
        withPreferences { it.dismissNotice(5_000) }

        withPreferences { it.state.first().noticeDismissedMs } shouldBe 5_000
    }
}
