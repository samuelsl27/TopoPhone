package es.topophone.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.core.okio.OkioStorage
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferencesSerializer
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.preferencesDataStoreFile
import java.io.File
import javax.inject.Qualifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okio.FileSystem
import okio.Path.Companion.toOkioPath

/** Identifica el DataStore de ajustes de la app frente a otros que se añadan después. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class SettingsDataStoreQualifier

object SettingsDataStore {
    private const val FILE_NAME = "settings"

    /** DataStore de ajustes en el almacenamiento privado de la app. */
    fun create(context: Context): DataStore<Preferences> =
        create(CoroutineScope(Dispatchers.IO + SupervisorJob())) { context.preferencesDataStoreFile(FILE_NAME) }

    /**
     * Si el fichero está corrupto se sustituye por unos ajustes vacíos (valores por defecto)
     * en lugar de fallar al arrancar (S001/RF-15).
     *
     * Usa el almacenamiento Okio de DataStore: su sustitución atómica del fichero
     * (`Files.move` con `ATOMIC_MOVE` y `REPLACE_EXISTING`) funciona igual en Android y en la JVM
     * de Windows, donde el `renameTo` del almacenamiento por defecto falla si el destino existe.
     */
    fun create(scope: CoroutineScope, produceFile: () -> File): DataStore<Preferences> = PreferenceDataStoreFactory.create(
        storage = OkioStorage(fileSystem = FileSystem.SYSTEM, serializer = PreferencesSerializer) { produceFile().toOkioPath() },
        corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
        scope = scope,
    )
}
