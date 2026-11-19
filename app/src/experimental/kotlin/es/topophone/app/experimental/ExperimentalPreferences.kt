package es.topophone.app.experimental

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import java.io.File
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Si el módulo experimental está activo y desde cuándo (S044/RF-3, RF-4, RF-5; plan D4): activo ⇔ hay fecha de aceptación del aviso legal.
 * El fichero vive en `noBackupFilesDir`, así que no viaja ni con la copia automática de Android, ni con la transferencia a otro móvil, ni
 * con las copias y paquetes de la app (S013): quien instala el flavor en otro móvil vuelve a ver el aviso.
 */
class ExperimentalPreferences(private val store: DataStore<Preferences>) {

    /** La fecha (ms) en que se aceptó el aviso, o `null` si el módulo está apagado. */
    val acceptedAtMs: Flow<Long?> = store.data.map { it[ACCEPTED_AT_MS] }

    suspend fun accept(atMs: Long) {
        store.edit { it[ACCEPTED_AT_MS] = atMs }
    }

    /** Desactivar olvida la aceptación: al activar otra vez, el aviso vuelve a salir (S044/RF-5). */
    suspend fun clear() {
        store.edit { it.remove(ACCEPTED_AT_MS) }
    }

    companion object {
        private val ACCEPTED_AT_MS = longPreferencesKey("accepted_at_ms")

        fun file(directory: File): File = File(directory, "experimental.preferences_pb")
    }
}
