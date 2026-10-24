package es.topophone.data.backup

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import es.topophone.core.formats.backup.BackupSettings
import es.topophone.core.formats.backup.SettingValue
import kotlinx.coroutines.flow.first

/** Idioma de la app ("" = el del sistema). No está en un DataStore: lo guarda AppCompat, y lo implementa `app`. */
interface AppLanguageStore {
    fun current(): String

    suspend fun apply(tag: String)
}

/**
 * Los ajustes que viajan en la copia completa (S013/RF-2) y los que no salen del móvil (S013/RF-3): el receptor
 * asociado, los permisos ya pedidos y el estado de la sesión NTRIP se quedan donde están al restaurar (S013/RF-8).
 * [stores] son los DataStores de la app por su nombre.
 */
class SettingsArchive(private val stores: Map<String, DataStore<Preferences>>, private val language: AppLanguageStore) {

    suspend fun export(): BackupSettings = BackupSettings(
        stores = PORTABLE.mapNotNull { (name, keys) ->
            val store = stores[name] ?: return@mapNotNull null
            name to store.data.first().asMap()
                .filterKeys { keys == null || it.name in keys }
                .mapNotNull { (key, value) -> value.toSetting()?.let { key.name to it } }
                .toMap()
        }.toMap(),
        appLanguage = language.current(),
    )

    /** Pone los ajustes de [settings]; los que viajan y no están en la copia vuelven a su valor por defecto. */
    suspend fun restore(settings: BackupSettings) {
        for ((name, keys) in PORTABLE) {
            val store = stores[name] ?: continue
            store.edit { prefs ->
                prefs.asMap().keys.filter { keys == null || it.name in keys }.forEach { prefs.remove(it) }
                settings.stores[name].orEmpty()
                    .filterKeys { keys == null || it in keys }
                    .forEach { (key, value) -> prefs.set(key, value) }
            }
        }
        language.apply(settings.appLanguage)
    }

    private fun Any.toSetting(): SettingValue? = when (this) {
        is String -> SettingValue.Text(this)
        is Int -> SettingValue.Int32(this)
        is Long -> SettingValue.Int64(this)
        is Float -> SettingValue.Float32(this)
        is Double -> SettingValue.Float64(this)
        is Boolean -> SettingValue.Flag(this)
        is Set<*> -> SettingValue.Texts(filterIsInstance<String>().toSet())
        else -> null
    }

    private fun MutablePreferences.set(key: String, value: SettingValue) {
        when (value) {
            is SettingValue.Text -> this[stringPreferencesKey(key)] = value.value
            is SettingValue.Int32 -> this[intPreferencesKey(key)] = value.value
            is SettingValue.Int64 -> this[longPreferencesKey(key)] = value.value
            is SettingValue.Float32 -> this[floatPreferencesKey(key)] = value.value
            is SettingValue.Float64 -> this[doublePreferencesKey(key)] = value.value
            is SettingValue.Flag -> this[booleanPreferencesKey(key)] = value.value
            is SettingValue.Texts -> this[stringSetPreferencesKey(key)] = value.value
        }
    }

    companion object {
        /**
         * Los DataStores cuyos ajustes viajan y, si no viajan todos, cuáles (plan, tabla de DataStores). El de
         * "backup" (carpeta de las copias automáticas) es del móvil y no está.
         */
        val PORTABLE: Map<String, Set<String>?> = mapOf(
            "settings" to null,
            "projects" to null,
            "collector" to null,
            "stakeout" to null,
            "export" to null,
            "map" to null,
            "receiver" to setOf("pole_height_m", "session_space_mb", "corrections_alarm_s"),
        )
    }
}
