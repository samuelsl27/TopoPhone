package es.topophone.data.point

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import es.topophone.core.model.point.CollectorSettings
import es.topophone.core.model.point.MeasurementMode
import es.topophone.core.model.point.PrecisionControl
import java.io.IOException
import javax.inject.Inject
import javax.inject.Qualifier
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** Modo de medida, tiempo de promediado y control de precisión, los mismos para todos los proyectos (S010/RF-9, RF-13). */
interface CollectorPreferences {
    val settings: Flow<CollectorSettings>

    suspend fun save(settings: CollectorSettings)
}

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class CollectorDataStoreQualifier

@Singleton
class DataStoreCollectorPreferences @Inject constructor(@param:CollectorDataStoreQualifier private val dataStore: DataStore<Preferences>) :
    CollectorPreferences {

    override val settings: Flow<CollectorSettings> = dataStore.data
        .catch { error -> if (error is IOException) emit(emptyPreferences()) else throw error }
        .map { p ->
            val factory = CollectorSettings()
            CollectorSettings(
                mode = MeasurementMode.entries.firstOrNull { it.name == p[MODE] } ?: factory.mode,
                averagingSeconds = p[AVERAGING_SECONDS] ?: factory.averagingSeconds,
                precision = PrecisionControl(
                    fixOnly = p[FIX_ONLY] ?: factory.precision.fixOnly,
                    horizontalLimitM = p[HORIZONTAL_LIMIT],
                    verticalLimitM = p[VERTICAL_LIMIT],
                ),
            )
        }.distinctUntilChanged()

    override suspend fun save(settings: CollectorSettings) {
        dataStore.edit {
            it[MODE] = settings.mode.name
            it[AVERAGING_SECONDS] = settings.validAveragingSeconds
            it[FIX_ONLY] = settings.precision.fixOnly
            // Un límite desactivado no se guarda (S010/RF-13).
            settings.precision.horizontalLimitM?.let { limit -> it[HORIZONTAL_LIMIT] = limit } ?: it.remove(HORIZONTAL_LIMIT)
            settings.precision.verticalLimitM?.let { limit -> it[VERTICAL_LIMIT] = limit } ?: it.remove(VERTICAL_LIMIT)
        }
    }

    private companion object {
        val MODE = stringPreferencesKey("collector_mode")
        val AVERAGING_SECONDS = intPreferencesKey("collector_averaging_seconds")
        val FIX_ONLY = booleanPreferencesKey("collector_fix_only")
        val HORIZONTAL_LIMIT = doublePreferencesKey("collector_horizontal_limit_m")
        val VERTICAL_LIMIT = doublePreferencesKey("collector_vertical_limit_m")
    }
}
