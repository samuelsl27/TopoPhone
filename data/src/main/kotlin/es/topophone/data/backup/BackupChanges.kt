package es.topophone.data.backup

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import es.topophone.data.db.TopoPhoneDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch

/**
 * Marca los cambios sin copiar para las copias automáticas (S013/RF-18; plan, A7): proyectos, carpetas, puntos, su
 * historial y perfiles NTRIP en Room, y los ajustes que viajan en sus DataStores. Mover el mapa (`map_viewports`) no
 * cuenta: no es un cambio de datos. El idioma no está en un DataStore y lo avisa la app con [languageChanged].
 */
class BackupChanges(
    private val db: TopoPhoneDatabase,
    private val settings: Collection<DataStore<Preferences>>,
    private val preferences: AutoBackupPreferences,
) {
    /** Escucha los cambios mientras viva [scope]. */
    fun start(scope: CoroutineScope): Job = scope.launch {
        val tables = db.invalidationTracker.createFlow(*TRACKED, emitInitialState = false).map { }
        val stores = settings.map { store -> store.data.drop(1).map { } }
        merge(tables, *stores.toTypedArray()).collect { preferences.markPending() }
    }

    suspend fun languageChanged() = preferences.markPending()

    private companion object {
        val TRACKED = arrayOf("projects", "project_folders", "points", "point_history", "ntrip_profiles")
    }
}
