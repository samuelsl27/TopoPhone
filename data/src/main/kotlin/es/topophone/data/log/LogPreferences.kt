package es.topophone.data.log

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import es.topophone.core.model.log.LogSize
import es.topophone.data.settings.SettingsDataStoreQualifier
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** Tamaño del log elegido en Ajustes (S014/RF-8, RF-9). Está en el DataStore "settings", que viaja en la copia (S013). */
interface LogPreferences {
    val sizeMb: Flow<Int>

    suspend fun saveSizeMb(mb: Int)
}

@Singleton
class DataStoreLogPreferences @Inject constructor(@param:SettingsDataStoreQualifier private val dataStore: DataStore<Preferences>) :
    LogPreferences {

    override val sizeMb: Flow<Int> = dataStore.data
        .catch { error -> if (error is IOException) emit(emptyPreferences()) else throw error }
        .map { LogSize.clamp(it[SIZE] ?: LogSize.DEFAULT_MB) }
        .distinctUntilChanged()

    override suspend fun saveSizeMb(mb: Int) {
        dataStore.edit { it[SIZE] = LogSize.clamp(mb) }
    }

    private companion object {
        val SIZE = intPreferencesKey("log_size_mb")
    }
}
