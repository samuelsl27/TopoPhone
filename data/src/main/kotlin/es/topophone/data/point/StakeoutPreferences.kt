package es.topophone.data.point

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import es.topophone.core.model.point.HeadingChoice
import es.topophone.core.model.point.StakeMode
import es.topophone.core.model.point.StakeoutSettings
import java.io.IOException
import javax.inject.Inject
import javax.inject.Qualifier
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** El modo de guía, el origen del rumbo y la tolerancia del replanteo, los mismos para todos los proyectos (S018/RF-5, RF-8, RF-18). */
interface StakeoutPreferences {
    val settings: Flow<StakeoutSettings>

    suspend fun save(settings: StakeoutSettings)
}

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class StakeoutDataStoreQualifier

@Singleton
class DataStoreStakeoutPreferences @Inject constructor(@param:StakeoutDataStoreQualifier private val dataStore: DataStore<Preferences>) :
    StakeoutPreferences {

    override val settings: Flow<StakeoutSettings> = dataStore.data
        .catch { error -> if (error is IOException) emit(emptyPreferences()) else throw error }
        .map { p ->
            val factory = StakeoutSettings()
            StakeoutSettings(
                mode = StakeMode.entries.firstOrNull { it.name == p[MODE] } ?: factory.mode,
                heading = HeadingChoice.entries.firstOrNull { it.name == p[HEADING] } ?: factory.heading,
                toleranceM = p[TOLERANCE_M] ?: factory.toleranceM,
                toleranceVM = p[TOLERANCE_V_M] ?: factory.toleranceVM,
            ).let { it.copy(toleranceM = it.validToleranceM, toleranceVM = it.validToleranceVM) }
        }.distinctUntilChanged()

    override suspend fun save(settings: StakeoutSettings) {
        dataStore.edit {
            it[MODE] = settings.mode.name
            it[HEADING] = settings.heading.name
            it[TOLERANCE_M] = settings.validToleranceM
            it[TOLERANCE_V_M] = settings.validToleranceVM
        }
    }

    private companion object {
        val MODE = stringPreferencesKey("stakeout_mode")
        val HEADING = stringPreferencesKey("stakeout_heading")
        val TOLERANCE_M = doublePreferencesKey("stakeout_tolerance_m")
        val TOLERANCE_V_M = doublePreferencesKey("stakeout_tolerance_v_m") // S019/RF-10
    }
}
