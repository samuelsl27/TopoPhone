package es.topophone.app.backup

import es.topophone.core.formats.backup.AutoBackupPolicy
import es.topophone.core.formats.backup.BackupFileNames
import es.topophone.data.backup.AutoBackupPreferences
import es.topophone.data.backup.BackupFolder
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Copias automáticas en la carpeta elegida (S013/RF-17 a RF-21; plan, A7). Comprueba cada 5 min mientras vive el
 * proceso (en campo lo mantiene el servicio del receptor) y al pasar la app a segundo plano; toca con cambios sin copiar
 * y una hora después de la última buena. [createBackup] escribe la copia completa en un fichero: el cerrojo del
 * repositorio impide que coincida con una restauración. [onStart] arranca lo que marca los cambios sin copiar.
 */
class AutoBackupScheduler(
    private val createBackup: suspend (File) -> Unit,
    private val folder: BackupFolder,
    private val preferences: AutoBackupPreferences,
    private val workDir: File,
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
    private val zone: () -> ZoneId = ZoneId::systemDefault,
    private val onStart: (CoroutineScope) -> Unit = {},
) {
    private val started = AtomicBoolean(false)
    private val mutex = Mutex()

    /** Una vez por proceso, aunque la actividad se cree varias veces. */
    fun start() {
        if (!started.compareAndSet(false, true)) return
        onStart(scope)
        scope.launch {
            while (true) {
                check()
                delay(AutoBackupPolicy.CHECK_EVERY_MS)
            }
        }
    }

    /** S013/RF-18: la app pasa a segundo plano. */
    fun onBackground() {
        scope.launch { check() }
    }

    /** Hace la copia si toca (S013/RF-18); true si la ha hecho. */
    suspend fun check(): Boolean {
        val state = preferences.state.first()
        if (!AutoBackupPolicy.due(clock(), state.lastSuccessMs, state.pendingChanges, state.folderUri != null)) return false
        return backupNow()
    }

    /** S013/RF-17: guarda el permiso de la carpeta, la recuerda y hace la primera copia (suposición de la spec). */
    suspend fun folderChosen(treeUri: String): Boolean {
        val previous = preferences.state.first().folderUri
        attempt { folder.persist(treeUri) } ?: return false
        if (previous != null && previous != treeUri) folder.release(previous)
        preferences.setFolder(treeUri)
        return backupNow()
    }

    /** S013/RF-17: sin carpeta no hay copias automáticas. */
    suspend fun folderRemoved() {
        preferences.state.first().folderUri?.let(folder::release)
        preferences.setFolder(null)
    }

    /**
     * S013/RF-18 a RF-21: copia completa en la carpeta y retención de las 24 últimas. Los cambios que lleguen mientras se
     * hace vuelven a quedar pendientes; si falla, se avisa y se intenta en la siguiente comprobación.
     */
    suspend fun backupNow(): Boolean = mutex.withLock {
        val treeUri = preferences.state.first().folderUri ?: return false
        preferences.clearPending()
        val now = clock()
        val file = File(workDir, "auto.zip")
        val saved = try {
            attempt {
                workDir.mkdirs()
                createBackup(file)
                folder.write(treeUri, BackupFileNames.automatic(Instant.ofEpochMilli(now), zone()), file)
            } == true
        } finally {
            file.delete()
        }
        if (!saved) {
            preferences.recordFailure()
            return false
        }
        preferences.recordSuccess(now)
        folder.list(treeUri)?.let { names -> AutoBackupPolicy.surplus(names).forEach { folder.delete(treeUri, it) } }
        true
    }

    /**
     * S013/RF-7: la copia del estado anterior a una restauración también va a la carpeta, si hay; nunca se borra por la
     * retención. Si no se puede guardar, se avisa como una copia automática fallida (S013/RF-21).
     */
    suspend fun keepBeforeRestore(file: File) {
        val treeUri = preferences.state.first().folderUri ?: return
        val saved = attempt { folder.write(treeUri, BackupFileNames.beforeRestore(Instant.ofEpochMilli(clock()), zone()), file) } == true
        if (!saved) preferences.recordFailure()
    }

    @Suppress("TooGenericExceptionCaught") // Un fallo de la copia o de la carpeta es "no se ha podido" (S013/RF-21).
    private inline fun <T> attempt(block: () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }
}
