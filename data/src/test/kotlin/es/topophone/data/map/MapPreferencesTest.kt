package es.topophone.data.map

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import es.topophone.core.model.map.Basemap
import es.topophone.core.model.map.MapSettings
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

/** Mapa base y Catastro, los mismos para todos los proyectos (S009/RF-6, RF-7, RF-8). */
class MapPreferencesTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val file: File by lazy { File(folder.root, "map.preferences_pb") }

    /** Las preferencias y su almacén, para escribir valores de otra versión. */
    private fun <T> withPreferences(block: suspend (DataStoreMapPreferences, DataStore<Preferences>) -> T): T = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val store = SettingsDataStore.create(scope) { file }
            block(DataStoreMapPreferences(store), store)
        } finally {
            scope.coroutineContext.job.cancelAndJoin()
        }
    }

    @Test
    fun `S009 RF-8 de fabrica el mapa del IGN sin Catastro`() {
        withPreferences { preferences, _ -> preferences.settings.first() } shouldBe MapSettings(Basemap.IGN_VECTOR, cadastre = false)
    }

    @Test
    fun `S009 RF-6, RF-7 y RF-8 el mapa base y el Catastro elegidos se recuerdan al volver a abrir la app`() {
        withPreferences { preferences, _ -> preferences.save(MapSettings(Basemap.PNOA_ORTHOPHOTO, cadastre = true)) }

        withPreferences { preferences, _ -> preferences.settings.first() } shouldBe MapSettings(Basemap.PNOA_ORTHOPHOTO, cadastre = true)
    }

    @Test
    fun `S009 RF-8 un mapa base desconocido de otra version cuenta como el de fabrica`() {
        withPreferences { _, store -> store.edit { it[stringPreferencesKey("map_basemap")] = "SATELITE_3D" } }

        withPreferences { preferences, _ -> preferences.settings.first() }.basemap shouldBe Basemap.IGN_VECTOR
    }
}
