package es.topophone.data.export

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import es.topophone.core.formats.dxf.export.DxfCoordinates
import es.topophone.core.formats.dxf.export.DxfExportOptions
import es.topophone.core.formats.dxf.export.DxfLabel
import java.io.IOException
import javax.inject.Inject
import javax.inject.Qualifier
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** Las etiquetas, la altura del texto y el sistema de la última exportación a DXF, para todos los proyectos (S029/RF-20, plan D9). */
interface DxfExportPreferences {
    val options: Flow<DxfExportOptions>

    suspend fun save(options: DxfExportOptions)
}

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class DxfExportDataStoreQualifier

@Singleton
class DataStoreDxfExportPreferences @Inject constructor(@param:DxfExportDataStoreQualifier private val dataStore: DataStore<Preferences>) :
    DxfExportPreferences {

    override val options: Flow<DxfExportOptions> = dataStore.data
        .catch { error -> if (error is IOException) emit(emptyPreferences()) else throw error }
        .map { p ->
            val factory = DxfExportOptions() // RF-7, RF-8: Nombre y Cota, 0,5 m
            DxfExportOptions(
                labels = p[LABELS]?.mapNotNull { name -> DxfLabel.entries.firstOrNull { it.name == name } }?.toSet() ?: factory.labels,
                textHeightM = p[TEXT_HEIGHT]?.takeIf { it in DxfExportOptions.MIN_TEXT_HEIGHT_M..DxfExportOptions.MAX_TEXT_HEIGHT_M }
                    ?: factory.textHeightM,
                coordinates = DxfCoordinates.entries.firstOrNull { it.name == p[COORDINATES] } ?: factory.coordinates,
            )
        }.distinctUntilChanged()

    override suspend fun save(options: DxfExportOptions) {
        dataStore.edit {
            it[LABELS] = options.labels.map { label -> label.name }.toSet()
            it[TEXT_HEIGHT] = options.textHeightM
            it[COORDINATES] = options.coordinates.name
        }
    }

    private companion object {
        val LABELS = stringSetPreferencesKey("labels")
        val TEXT_HEIGHT = doublePreferencesKey("text_height_m")
        val COORDINATES = stringPreferencesKey("coordinates")
    }
}
