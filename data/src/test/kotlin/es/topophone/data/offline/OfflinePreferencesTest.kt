package es.topophone.data.offline

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import es.topophone.core.model.offline.DetailLevel
import es.topophone.core.model.offline.OfflineAreaKind
import es.topophone.core.model.offline.OfflineLimits
import es.topophone.core.model.offline.OfflineSettings
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

/** El límite de almacenamiento y la última elección de área y nivel de las zonas sin conexión (S037/RF-8, RF-31). */
class OfflinePreferencesTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val file: File by lazy { File(folder.root, "offline.preferences_pb") }

    /** Las preferencias y su almacén, para escribir valores de otra versión. */
    private fun <T> withPreferences(block: suspend (DataStoreOfflinePreferences, DataStore<Preferences>) -> T): T = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val store = SettingsDataStore.create(scope) { file }
            block(DataStoreOfflinePreferences(store), store)
        } finally {
            scope.coroutineContext.job.cancelAndJoin()
        }
    }

    @Test
    fun `S037 RF-31 de fabrica el limite es 2 GB y la zona un radio de 500 m en Normal`() {
        withPreferences { preferences, _ -> preferences.settings.first() } shouldBe OfflineSettings()
    }

    @Test
    fun `S037 RF-8 y RF-31 lo elegido se recuerda al volver a abrir la app`() {
        val settings = OfflineSettings(
            storageLimitBytes = 5L * 1024 * 1024 * 1024,
            detail = DetailLevel.HIGH,
            areaKind = OfflineAreaKind.PROJECT,
            radiusM = 2_500.0,
            marginM = 750.0,
        )
        withPreferences { preferences, _ -> preferences.save(settings) }

        withPreferences { preferences, _ -> preferences.settings.first() } shouldBe settings
    }

    @Test
    fun `S037 RF-31 un limite fuera de 200 MB a 10 GB se acota al guardarlo`() {
        withPreferences { preferences, _ -> preferences.save(OfflineSettings(storageLimitBytes = 1L)) }

        withPreferences { preferences, _ -> preferences.settings.first() }.storageLimitBytes shouldBe OfflineLimits.MIN_STORAGE_LIMIT_BYTES
    }

    @Test
    fun `S037 RF-31 y RF-3 y RF-4 un valor fuera de limites escrito por otra version se acota al leerlo`() {
        withPreferences { _, store ->
            store.edit {
                it[longPreferencesKey("offline_limit_bytes")] = 50L * 1024 * 1024 * 1024
                it[doublePreferencesKey("offline_radius_m")] = 10.0
                it[doublePreferencesKey("offline_margin_m")] = 5_000.0
            }
        }

        withPreferences { preferences, _ -> preferences.settings.first() }.let {
            it.storageLimitBytes shouldBe OfflineLimits.MAX_STORAGE_LIMIT_BYTES
            it.radiusM shouldBe OfflineLimits.MIN_RADIUS_M
            it.marginM shouldBe OfflineLimits.MAX_MARGIN_M
        }
    }

    @Test
    fun `S037 RF-8 un nivel o un tipo de area desconocido de otra version cuenta como el de fabrica`() {
        withPreferences { _, store ->
            store.edit {
                it[stringPreferencesKey("offline_detail")] = "ENORME"
                it[stringPreferencesKey("offline_area_kind")] = "hexagono"
            }
        }

        withPreferences { preferences, _ -> preferences.settings.first() }.let {
            it.detail shouldBe DetailLevel.NORMAL
            it.areaKind shouldBe OfflineAreaKind.RADIUS
        }
    }
}
