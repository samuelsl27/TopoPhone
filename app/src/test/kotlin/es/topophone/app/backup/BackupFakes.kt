package es.topophone.app.backup

import es.topophone.data.backup.AutoBackupPreferences
import es.topophone.data.backup.AutoBackupState
import es.topophone.data.backup.BackupFolder
import java.io.File
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/** Estado de las copias automáticas en memoria, para los tests de S013. */
class FakePreferences(initial: AutoBackupState = AutoBackupState()) : AutoBackupPreferences {
    val flow = MutableStateFlow(initial)
    override val state: Flow<AutoBackupState> = flow

    override suspend fun setFolder(uri: String?) = flow.update { it.copy(folderUri = uri, failing = false) }

    override suspend fun markPending() = flow.update { it.copy(pendingChanges = true) }

    override suspend fun clearPending() = flow.update { it.copy(pendingChanges = false) }

    override suspend fun recordSuccess(atMs: Long) = flow.update { it.copy(lastSuccessMs = atMs, failing = false) }

    override suspend fun recordFailure() = flow.update { it.copy(failing = true, pendingChanges = true) }

    override suspend fun dismissNotice(atMs: Long) = flow.update { it.copy(noticeDismissedMs = atMs) }
}

/** Carpeta de las copias automáticas en memoria, para los tests de S013. */
class FakeFolder : BackupFolder {
    val files = linkedMapOf<String, String>()
    var failWrites = false
    var failPersist = false
    val persisted = mutableListOf<String>()

    override suspend fun write(treeUri: String, name: String, from: File): Boolean {
        if (failWrites) return false
        files[name] = from.readText()
        return true
    }

    override suspend fun list(treeUri: String): List<String> = files.keys.toList()

    override suspend fun delete(treeUri: String, name: String): Boolean = files.remove(name) != null

    override fun persist(treeUri: String) {
        if (failPersist) throw SecurityException("sin permiso")
        persisted += treeUri
    }

    override fun release(treeUri: String) {
        persisted -= treeUri
    }
}
