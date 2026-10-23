package es.topophone.data.receiver

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import es.topophone.core.model.point.TiltMode
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * El modo de inclinación de un receptor (S016/RF-1).
 *
 * @property mode null si aún no se ha elegido (S016/RF-2).
 * @property verified lo confirmó el asistente (S016/RF-10); elegido a mano, "sin comprobar" (S016/RF-3).
 * @property detected lo último que encontró el asistente, aunque luego se eligiera otro modo (S016/RF-4).
 */
data class ReceiverTiltSettings(val mode: TiltMode? = null, val verified: Boolean = false, val detected: TiltMode? = null) {
    companion object {
        val NONE = ReceiverTiltSettings()
    }
}

/** El modo de inclinación de cada receptor, por su dirección Bluetooth o [REPLAY] para las reproducciones (S016/RF-1). */
interface TiltPreferences {
    fun settings(key: String): Flow<ReceiverTiltSettings>

    suspend fun save(key: String, settings: ReceiverTiltSettings)

    companion object {
        /** La clave común a todas las reproducciones (S016, plan A8). */
        const val REPLAY = "replay"
    }
}

/**
 * En el DataStore `"receiver"`, una clave `tilt_<receptor>` con `MODO;comprobado;DETECTADO`. No viaja en las copias
 * (S013): `SettingsArchive.PORTABLE` solo lleva otras claves de ese DataStore, como con el último receptor.
 */
@Singleton
class DataStoreTiltPreferences @Inject constructor(@param:ReceiverDataStoreQualifier private val dataStore: DataStore<Preferences>) :
    TiltPreferences {

    private val data: Flow<Preferences> = dataStore.data.catch { error ->
        if (error is IOException) emit(emptyPreferences()) else throw error
    }

    override fun settings(key: String): Flow<ReceiverTiltSettings> = data.map { decode(it[preferenceKey(key)]) }.distinctUntilChanged()

    override suspend fun save(key: String, settings: ReceiverTiltSettings) {
        dataStore.edit { it[preferenceKey(key)] = encode(settings) }
    }

    private fun preferenceKey(key: String) = stringPreferencesKey(PREFIX + key)

    private fun encode(settings: ReceiverTiltSettings) =
        listOf(settings.mode?.name.orEmpty(), if (settings.verified) "1" else "0", settings.detected?.name.orEmpty()).joinToString(";")

    /** Un valor que no se entiende es un modo sin elegir: se vuelve a pedir (S016/RF-2). */
    private fun decode(value: String?): ReceiverTiltSettings {
        val parts = value?.split(";") ?: return ReceiverTiltSettings.NONE
        if (parts.size != FIELDS) return ReceiverTiltSettings.NONE
        val mode = TiltMode.entries.firstOrNull { it.name == parts[0] }
        val detected = TiltMode.entries.firstOrNull { it.name == parts[2] }
        return ReceiverTiltSettings(mode, verified = mode != null && parts[1] == "1", detected = detected)
    }

    private companion object {
        const val PREFIX = "tilt_"
        const val FIELDS = 3
    }
}
