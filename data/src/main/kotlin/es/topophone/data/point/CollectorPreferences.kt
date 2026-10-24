package es.topophone.data.point

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import es.topophone.core.model.point.AutoTrigger
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

/**
 * Modo de medida, tiempo de promediado y control de precisión, con la inclinación máxima, y el disparo, el intervalo y la
 * distancia del modo automático, los mismos para todos los proyectos (S010/RF-9, RF-13; S016/RF-20; S017/RF-16, RF-17).
 */
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
                    // S016/RF-20: activa por defecto, así que se guarda si está activa además del valor.
                    maxTiltRad = if (p[MAX_TILT_ENABLED] ?: true) p[MAX_TILT] ?: PrecisionControl.DEFAULT_MAX_TILT_RAD else null,
                ),
                // S017/RF-16, RF-17: el disparo, el intervalo y la distancia; que el modo esté encendido no se guarda.
                autoTrigger = AutoTrigger.entries.firstOrNull { it.name == p[AUTO_TRIGGER] } ?: factory.autoTrigger,
                autoIntervalS = p[AUTO_INTERVAL_S] ?: factory.autoIntervalS,
                autoDistanceM = p[AUTO_DISTANCE_M] ?: factory.autoDistanceM,
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
            it[MAX_TILT_ENABLED] = settings.precision.maxTiltRad != null
            settings.precision.maxTiltRad?.let { limit -> it[MAX_TILT] = limit }
            it[AUTO_TRIGGER] = settings.autoTrigger.name
            it[AUTO_INTERVAL_S] = settings.validAutoIntervalS
            it[AUTO_DISTANCE_M] = settings.validAutoDistanceM
        }
    }

    private companion object {
        val MODE = stringPreferencesKey("collector_mode")
        val AVERAGING_SECONDS = intPreferencesKey("collector_averaging_seconds")
        val FIX_ONLY = booleanPreferencesKey("collector_fix_only")
        val HORIZONTAL_LIMIT = doublePreferencesKey("collector_horizontal_limit_m")
        val VERTICAL_LIMIT = doublePreferencesKey("collector_vertical_limit_m")
        val MAX_TILT_ENABLED = booleanPreferencesKey("collector_max_tilt_enabled")
        val MAX_TILT = doublePreferencesKey("collector_max_tilt_rad")
        val AUTO_TRIGGER = stringPreferencesKey("collector_auto_trigger")
        val AUTO_INTERVAL_S = intPreferencesKey("collector_auto_interval_s")
        val AUTO_DISTANCE_M = doublePreferencesKey("collector_auto_distance_m")
    }
}
