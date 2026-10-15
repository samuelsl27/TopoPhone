package es.topophone.data.receiver

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import es.topophone.core.survey.PoleHeight
import java.io.IOException
import javax.inject.Inject
import javax.inject.Qualifier
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** Datos del caster que se recuerdan (S002/RF-26). La contraseña no existe aquí (S002/RF-27, RNF-T11). */
data class NtripPreferences(val host: String = "", val port: Int = DEFAULT_PORT, val mountpoint: String = "", val username: String = "") {
    companion object {
        const val DEFAULT_PORT = 2101
    }
}

/** Espacio para las sesiones grabadas: valores entre 100 MB y 2 GB que se ofrecen en Ajustes (S003/RF-26). */
object SessionSpace {
    val OPTIONS_MB = listOf(100, 250, 500, 1000, 2000)
    const val DEFAULT_MB = 500

    /** MB decimales, como Android. */
    const val BYTES_PER_MB = 1_000_000L
}

/** Último receptor con el que se conectó, para la conexión automática (S004/RF-24, S004/RF-25). */
data class SavedReceiver(val address: String, val name: String)

/** Preferencias del receptor y del cálculo del punto del suelo. */
interface ReceiverPreferences {
    val ntrip: Flow<NtripPreferences>
    val poleHeightM: Flow<Double>
    val sessionSpaceMb: Flow<Int>

    suspend fun saveNtrip(preferences: NtripPreferences)

    suspend fun savePoleHeight(heightM: Double)

    /** Guarda el espacio para sesiones; un valor que no está entre las opciones se ignora (S003/RF-26). */
    suspend fun saveSessionSpaceMb(mb: Int)

    val lastReceiver: Flow<SavedReceiver?>

    suspend fun saveLastReceiver(receiver: SavedReceiver)

    /** Ya se pidió el permiso de notificaciones una vez (S004/RF-5). */
    val notificationsAsked: Flow<Boolean>

    suspend fun markNotificationsAsked()
}

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ReceiverDataStoreQualifier

@Singleton
class DataStoreReceiverPreferences @Inject constructor(@param:ReceiverDataStoreQualifier private val dataStore: DataStore<Preferences>) :
    ReceiverPreferences {

    private val data: Flow<Preferences> = dataStore.data.catch { error ->
        if (error is IOException) emit(emptyPreferences()) else throw error
    }

    override val ntrip: Flow<NtripPreferences> = data.map { p ->
        NtripPreferences(
            host = p[HOST].orEmpty(),
            port = p[PORT] ?: NtripPreferences.DEFAULT_PORT,
            mountpoint = p[MOUNTPOINT].orEmpty(),
            username = p[USERNAME].orEmpty(),
        )
    }.distinctUntilChanged()

    override val poleHeightM: Flow<Double> = data.map { it[POLE_HEIGHT] ?: PoleHeight.DEFAULT_M }.distinctUntilChanged()

    override val sessionSpaceMb: Flow<Int> =
        data.map { p -> p[SESSION_SPACE]?.takeIf { it in SessionSpace.OPTIONS_MB } ?: SessionSpace.DEFAULT_MB }.distinctUntilChanged()

    override suspend fun saveNtrip(preferences: NtripPreferences) {
        dataStore.edit {
            it[HOST] = preferences.host
            it[PORT] = preferences.port
            it[MOUNTPOINT] = preferences.mountpoint
            it[USERNAME] = preferences.username
        }
    }

    override suspend fun savePoleHeight(heightM: Double) {
        dataStore.edit { it[POLE_HEIGHT] = heightM }
    }

    override val lastReceiver: Flow<SavedReceiver?> = data.map { p ->
        val address = p[LAST_RECEIVER_ADDRESS]
        if (address.isNullOrEmpty()) null else SavedReceiver(address, p[LAST_RECEIVER_NAME].orEmpty())
    }.distinctUntilChanged()

    override val notificationsAsked: Flow<Boolean> = data.map { it[NOTIFICATIONS_ASKED] ?: false }.distinctUntilChanged()

    override suspend fun saveLastReceiver(receiver: SavedReceiver) {
        dataStore.edit {
            it[LAST_RECEIVER_ADDRESS] = receiver.address
            it[LAST_RECEIVER_NAME] = receiver.name
        }
    }

    override suspend fun markNotificationsAsked() {
        dataStore.edit { it[NOTIFICATIONS_ASKED] = true }
    }

    override suspend fun saveSessionSpaceMb(mb: Int) {
        if (mb in SessionSpace.OPTIONS_MB) dataStore.edit { it[SESSION_SPACE] = mb }
    }

    private companion object {
        val HOST = stringPreferencesKey("ntrip_host")
        val PORT = intPreferencesKey("ntrip_port")
        val MOUNTPOINT = stringPreferencesKey("ntrip_mountpoint")
        val USERNAME = stringPreferencesKey("ntrip_username")
        val POLE_HEIGHT = doublePreferencesKey("pole_height_m")
        val SESSION_SPACE = intPreferencesKey("session_space_mb")
        val LAST_RECEIVER_ADDRESS = stringPreferencesKey("last_receiver_address")
        val LAST_RECEIVER_NAME = stringPreferencesKey("last_receiver_name")
        val NOTIFICATIONS_ASKED = booleanPreferencesKey("notifications_asked")
    }
}
