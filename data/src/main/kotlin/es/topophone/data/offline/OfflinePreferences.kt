package es.topophone.data.offline

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import es.topophone.core.model.offline.DetailLevel
import es.topophone.core.model.offline.OfflineAreaKind
import es.topophone.core.model.offline.OfflineSettings
import java.io.IOException
import javax.inject.Inject
import javax.inject.Qualifier
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** El límite de almacenamiento y la última elección de área y nivel de las zonas sin conexión (S037/RF-8, RF-31). No viajan en la copia: son de este móvil. */
interface OfflinePreferences {
    val settings: Flow<OfflineSettings>

    suspend fun save(settings: OfflineSettings)
}

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class OfflineDataStoreQualifier

@Singleton
class DataStoreOfflinePreferences @Inject constructor(@param:OfflineDataStoreQualifier private val dataStore: DataStore<Preferences>) :
    OfflinePreferences {

    override val settings: Flow<OfflineSettings> = dataStore.data
        .catch { error -> if (error is IOException) emit(emptyPreferences()) else throw error }
        .map { p ->
            val factory = OfflineSettings()
            OfflineSettings(
                storageLimitBytes = p[LIMIT] ?: factory.storageLimitBytes,
                // Un valor desconocido (de otra versión o dañado) cuenta como el de fábrica.
                detail = DetailLevel.fromKey(p[DETAIL].orEmpty()) ?: factory.detail,
                areaKind = OfflineAreaKind.fromKey(p[AREA_KIND].orEmpty()) ?: factory.areaKind,
                radiusM = p[RADIUS] ?: factory.radiusM,
                marginM = p[MARGIN] ?: factory.marginM,
            ).sanitized()
        }.distinctUntilChanged()

    override suspend fun save(settings: OfflineSettings) {
        val clean = settings.sanitized()
        dataStore.edit {
            it[LIMIT] = clean.storageLimitBytes
            it[DETAIL] = clean.detail.key
            it[AREA_KIND] = clean.areaKind.key
            it[RADIUS] = clean.radiusM
            it[MARGIN] = clean.marginM
        }
    }

    private companion object {
        val LIMIT = longPreferencesKey("offline_limit_bytes")
        val DETAIL = stringPreferencesKey("offline_detail")
        val AREA_KIND = stringPreferencesKey("offline_area_kind")
        val RADIUS = doublePreferencesKey("offline_radius_m")
        val MARGIN = doublePreferencesKey("offline_margin_m")
    }
}
