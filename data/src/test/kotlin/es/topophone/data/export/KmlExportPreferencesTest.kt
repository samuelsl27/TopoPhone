package es.topophone.data.export

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import es.topophone.core.formats.kml.export.KmlFormat
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

/** El formato, «Incluir altura» e «Incluir fotos» de la exportación a KML o KMZ, para todos los proyectos (S031/RF-32). */
class KmlExportPreferencesTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val file: File by lazy { File(folder.root, "kml-export.preferences_pb") }

    private fun <T> withPreferences(block: suspend (DataStoreKmlExportPreferences) -> T): T = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            block(DataStoreKmlExportPreferences(SettingsDataStore.create(scope) { file }))
        } finally {
            scope.coroutineContext.job.cancelAndJoin()
        }
    }

    @Test
    fun `S031 RF-32 de fabrica se exporta a KML sin altura y sin fotos`() {
        withPreferences { it.settings.first() } shouldBe KmlExportSettings(KmlFormat.KML, includeHeight = false, includePhotos = false)
    }

    @Test
    fun `S031 RF-32 lo elegido se recuerda al volver a abrir la app`() {
        withPreferences { it.save(KmlExportSettings(KmlFormat.KMZ, includeHeight = true, includePhotos = true)) }

        withPreferences { it.settings.first() } shouldBe KmlExportSettings(KmlFormat.KMZ, includeHeight = true, includePhotos = true)
    }

    @Test
    fun `S031 RF-32 cada opcion se guarda por separado`() {
        withPreferences { it.save(KmlExportSettings(KmlFormat.KML, includeHeight = true, includePhotos = false)) }

        withPreferences { it.settings.first() } shouldBe KmlExportSettings(KmlFormat.KML, includeHeight = true, includePhotos = false)
        withPreferences { it.save(KmlExportSettings(KmlFormat.KMZ, includeHeight = false, includePhotos = true)) }
        withPreferences { it.settings.first() } shouldBe KmlExportSettings(KmlFormat.KMZ, includeHeight = false, includePhotos = true)
    }

    @Test
    fun `S031 RF-32 se puede volver a los valores de fabrica`() {
        withPreferences { it.save(KmlExportSettings(KmlFormat.KMZ, includeHeight = true, includePhotos = true)) }
        withPreferences { it.save(KmlExportSettings()) }

        withPreferences { it.settings.first() } shouldBe KmlExportSettings()
    }

    @Test
    fun `S031 RF-32 un formato guardado que no se conoce vuelve al de fabrica y las demas opciones se respetan`() {
        runBlocking {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            try {
                val store = SettingsDataStore.create(scope) { file }
                store.edit {
                    it[stringPreferencesKey("format")] = "KMX"
                    it[booleanPreferencesKey("includeHeight")] = true
                }

                DataStoreKmlExportPreferences(store).settings.first() shouldBe KmlExportSettings(KmlFormat.KML, includeHeight = true)
            } finally {
                scope.coroutineContext.job.cancelAndJoin()
            }
        }
    }
}
