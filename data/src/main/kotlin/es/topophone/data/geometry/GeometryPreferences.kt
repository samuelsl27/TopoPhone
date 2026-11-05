package es.topophone.data.geometry

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import es.topophone.core.model.geometry.DistanceMode
import java.io.IOException
import javax.inject.Inject
import javax.inject.Qualifier
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** La distancia de la información geométrica, de cuadrícula o de terreno, para todos los proyectos (S026/RF-9). */
interface GeometryPreferences {
    val distanceMode: Flow<DistanceMode>

    suspend fun setDistanceMode(mode: DistanceMode)
}

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class GeometryDataStoreQualifier

@Singleton
class DataStoreGeometryPreferences @Inject constructor(@param:GeometryDataStoreQualifier private val dataStore: DataStore<Preferences>) :
    GeometryPreferences {

    override val distanceMode: Flow<DistanceMode> = dataStore.data
        .catch { error -> if (error is IOException) emit(emptyPreferences()) else throw error }
        .map { DistanceMode.fromKey(it[DISTANCE_MODE]) }
        .distinctUntilChanged()

    override suspend fun setDistanceMode(mode: DistanceMode) {
        dataStore.edit { it[DISTANCE_MODE] = mode.name }
    }

    private companion object {
        val DISTANCE_MODE = stringPreferencesKey("distance_mode")
    }
}
