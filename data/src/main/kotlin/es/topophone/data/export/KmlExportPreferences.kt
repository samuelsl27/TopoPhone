package es.topophone.data.export

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import es.topophone.core.formats.kml.export.KmlFormat
import java.io.IOException
import javax.inject.Inject
import javax.inject.Qualifier
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** Lo que se recuerda de la última exportación a KML o KMZ, para todos los proyectos (S031/RF-32). */
data class KmlExportSettings(
    val format: KmlFormat = KmlFormat.KML,
    val includeHeight: Boolean = false,
    /** «Incluir fotos» solo cuenta con KMZ: se recuerda aunque el formato sea KML para cuando vuelva a elegirse KMZ. */
    val includePhotos: Boolean = false,
)

interface KmlExportPreferences {
    /** Lo guardado, o de fábrica (KML, sin altura y sin fotos) lo que falte o no sea válido. */
    val settings: Flow<KmlExportSettings>

    suspend fun save(settings: KmlExportSettings)
}

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class KmlExportDataStoreQualifier

@Singleton
class DataStoreKmlExportPreferences @Inject constructor(@param:KmlExportDataStoreQualifier private val dataStore: DataStore<Preferences>) :
    KmlExportPreferences {

    override val settings: Flow<KmlExportSettings> = dataStore.data
        .catch { error -> if (error is IOException) emit(emptyPreferences()) else throw error }
        .map { p ->
            KmlExportSettings(
                format = KmlFormat.entries.firstOrNull { it.name == p[FORMAT] } ?: KmlFormat.KML,
                includeHeight = p[INCLUDE_HEIGHT] ?: false,
                includePhotos = p[INCLUDE_PHOTOS] ?: false,
            )
        }
        .distinctUntilChanged()

    override suspend fun save(settings: KmlExportSettings) {
        dataStore.edit {
            it[FORMAT] = settings.format.name
            it[INCLUDE_HEIGHT] = settings.includeHeight
            it[INCLUDE_PHOTOS] = settings.includePhotos
        }
    }

    private companion object {
        val FORMAT = stringPreferencesKey("format")
        val INCLUDE_HEIGHT = booleanPreferencesKey("includeHeight")
        val INCLUDE_PHOTOS = booleanPreferencesKey("includePhotos")
    }
}
