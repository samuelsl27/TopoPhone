package es.topophone.data.map

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import es.topophone.core.model.map.Basemap
import es.topophone.core.model.map.MapSettings
import java.io.IOException
import javax.inject.Inject
import javax.inject.Qualifier
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** Mapa base y Catastro, los mismos para todos los proyectos (S009/RF-6 a RF-8). */
interface MapPreferences {
    val settings: Flow<MapSettings>

    suspend fun save(settings: MapSettings)
}

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class MapDataStoreQualifier

@Singleton
class DataStoreMapPreferences @Inject constructor(@param:MapDataStoreQualifier private val dataStore: DataStore<Preferences>) :
    MapPreferences {

    override val settings: Flow<MapSettings> = dataStore.data
        .catch { error -> if (error is IOException) emit(emptyPreferences()) else throw error }
        .map { p ->
            val factory = MapSettings()
            MapSettings(
                // Un valor desconocido (de otra versión o dañado) cuenta como el de fábrica.
                basemap = Basemap.entries.firstOrNull { it.name == p[BASEMAP] } ?: factory.basemap,
                cadastre = p[CADASTRE] ?: factory.cadastre,
            )
        }.distinctUntilChanged()

    override suspend fun save(settings: MapSettings) {
        dataStore.edit {
            it[BASEMAP] = settings.basemap.name
            it[CADASTRE] = settings.cadastre
        }
    }

    private companion object {
        val BASEMAP = stringPreferencesKey("map_basemap")
        val CADASTRE = booleanPreferencesKey("map_cadastre")
    }
}
