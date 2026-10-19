package es.topophone.data.backup

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import es.topophone.data.settings.SettingsDataStore
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/** DataStores de ajustes en [dir] para los tests de copias; [close] los cierra. */
class TestSettings(dir: File, val language: FakeLanguage = FakeLanguage()) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val stores: Map<String, DataStore<Preferences>> = SettingsArchive.PORTABLE.keys.associateWith { name ->
        SettingsDataStore.create(scope) { File(dir, "$name.preferences_pb") }
    }

    val archive = SettingsArchive(stores, language)

    fun close() = scope.cancel()

    class FakeLanguage(var tag: String = "") : AppLanguageStore {
        override fun current(): String = tag

        override suspend fun apply(tag: String) {
            this.tag = tag
        }
    }
}
