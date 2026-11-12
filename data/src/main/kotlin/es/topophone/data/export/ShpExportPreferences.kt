package es.topophone.data.export

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import es.topophone.core.formats.shp.export.ShpCoordinates
import java.io.IOException
import javax.inject.Inject
import javax.inject.Qualifier
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** El sistema de coordenadas de la última exportación a Shapefile, para todos los proyectos (S055/RF-23). */
interface ShpExportPreferences {
    /** Proyectadas de fábrica, y las del proyecto: el valor guardado, o las de fábrica si no hay o no es válido. */
    val coordinates: Flow<ShpCoordinates>

    suspend fun save(coordinates: ShpCoordinates)
}

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ShpExportDataStoreQualifier

@Singleton
class DataStoreShpExportPreferences @Inject constructor(@param:ShpExportDataStoreQualifier private val dataStore: DataStore<Preferences>) :
    ShpExportPreferences {

    override val coordinates: Flow<ShpCoordinates> = dataStore.data
        .catch { error -> if (error is IOException) emit(emptyPreferences()) else throw error }
        .map { p -> ShpCoordinates.entries.firstOrNull { it.name == p[COORDINATES] } ?: ShpCoordinates.PROJECTED }
        .distinctUntilChanged()

    override suspend fun save(coordinates: ShpCoordinates) {
        dataStore.edit { it[COORDINATES] = coordinates.name }
    }

    private companion object {
        val COORDINATES = stringPreferencesKey("coordinates")
    }
}
