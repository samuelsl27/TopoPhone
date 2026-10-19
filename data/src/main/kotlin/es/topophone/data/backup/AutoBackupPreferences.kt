package es.topophone.data.backup

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import java.io.IOException
import javax.inject.Inject
import javax.inject.Qualifier
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * Estado de las copias automáticas de este móvil (S013/RF-17 a RF-23; plan, DataStore "backup"). Nada de esto viaja en
 * la copia: la carpeta y su permiso son de este móvil.
 */
data class AutoBackupState(
    /** Carpeta elegida (árbol de documentos con el permiso persistido); null = copias automáticas desactivadas. */
    val folderUri: String? = null,
    /** Última copia automática guardada bien (S013/RF-20). */
    val lastSuccessMs: Long? = null,
    /** La última copia automática falló (S013/RF-21). */
    val failing: Boolean = false,
    /** Hay cambios sin copiar (S013/RF-18). */
    val pendingChanges: Boolean = false,
    /** Cuándo se cerró el aviso de la lista de proyectos (S013/RF-23). */
    val noticeDismissedMs: Long? = null,
)

interface AutoBackupPreferences {
    val state: Flow<AutoBackupState>

    /** S013/RF-17: con una carpeta se activan; con null se desactivan y se olvida el fallo. */
    suspend fun setFolder(uri: String?)

    suspend fun markPending()

    suspend fun clearPending()

    suspend fun recordSuccess(atMs: Long)

    /** El fallo deja los cambios pendientes, para volver a intentarlo (S013/RF-21). */
    suspend fun recordFailure()

    suspend fun dismissNotice(atMs: Long)
}

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class BackupDataStoreQualifier

@Singleton
class DataStoreAutoBackupPreferences @Inject constructor(@param:BackupDataStoreQualifier private val dataStore: DataStore<Preferences>) :
    AutoBackupPreferences {

    override val state: Flow<AutoBackupState> = dataStore.data
        .catch { error -> if (error is IOException) emit(emptyPreferences()) else throw error }
        .map { p ->
            AutoBackupState(
                folderUri = p[FOLDER],
                lastSuccessMs = p[LAST_SUCCESS],
                failing = p[FAILING] ?: false,
                pendingChanges = p[PENDING] ?: false,
                noticeDismissedMs = p[NOTICE_DISMISSED],
            )
        }.distinctUntilChanged()

    override suspend fun setFolder(uri: String?) = update {
        if (uri == null) it.remove(FOLDER) else it[FOLDER] = uri
        it.remove(FAILING)
    }

    override suspend fun markPending() = update { it[PENDING] = true }

    override suspend fun clearPending() = update { it.remove(PENDING) }

    override suspend fun recordSuccess(atMs: Long) = update {
        it[LAST_SUCCESS] = atMs
        it.remove(FAILING)
    }

    override suspend fun recordFailure() = update {
        it[FAILING] = true
        it[PENDING] = true
    }

    override suspend fun dismissNotice(atMs: Long) = update { it[NOTICE_DISMISSED] = atMs }

    private suspend fun update(change: (MutablePreferences) -> Unit) {
        dataStore.edit(change)
    }

    private companion object {
        val FOLDER = stringPreferencesKey("folder_uri")
        val LAST_SUCCESS = longPreferencesKey("last_success_ms")
        val FAILING = booleanPreferencesKey("failing")
        val PENDING = booleanPreferencesKey("pending_changes")
        val NOTICE_DISMISSED = longPreferencesKey("notice_dismissed_ms")
    }
}
