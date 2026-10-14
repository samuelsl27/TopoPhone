package es.topophone.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import es.topophone.core.model.preferences.ThemeMode
import es.topophone.core.model.preferences.ThemeState
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** Ajustes de la app que se recuerdan entre sesiones (S001/RF-15). */
interface SettingsRepository {

    /** Tema activo y tema anterior al modo sol; emite cada cambio. */
    val themeState: Flow<ThemeState>

    /** Elección en Ajustes (S001/RF-9). */
    suspend fun selectTheme(mode: ThemeMode)

    /** Acceso rápido al modo sol (S001/RF-12). */
    suspend fun toggleSun()
}

/** [SettingsRepository] sobre un DataStore de Preferences (plan S001: `theme_mode`, `theme_before_sun`). */
@Singleton
class DataStoreSettingsRepository @Inject constructor(@param:SettingsDataStoreQualifier private val dataStore: DataStore<Preferences>) :
    SettingsRepository {

    override val themeState: Flow<ThemeState> = dataStore.data
        .catch { error -> if (error is IOException) emit(emptyPreferences()) else throw error }
        .map { it.toThemeState() }
        .distinctUntilChanged()

    override suspend fun selectTheme(mode: ThemeMode) = update { it.select(mode) }

    override suspend fun toggleSun() = update { it.toggleSun() }

    /** Lee y escribe en la misma transacción, así dos cambios seguidos no se pisan. */
    private suspend fun update(transform: (ThemeState) -> ThemeState) {
        dataStore.edit { preferences ->
            val next = transform(preferences.toThemeState())
            preferences[THEME_MODE] = next.mode.name
            preferences[THEME_BEFORE_SUN] = next.beforeSun.name
        }
    }

    private fun Preferences.toThemeState() = ThemeState.restore(this[THEME_MODE], this[THEME_BEFORE_SUN])

    companion object {
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val THEME_BEFORE_SUN = stringPreferencesKey("theme_before_sun")
    }
}
