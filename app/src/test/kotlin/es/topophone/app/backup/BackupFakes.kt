package es.topophone.app.backup

import es.topophone.core.formats.backup.BackupKind
import es.topophone.data.backup.AutoBackupPreferences
import es.topophone.data.backup.AutoBackupState
import es.topophone.data.backup.BackupFolder
import es.topophone.data.backup.BackupRead
import es.topophone.data.backup.Backups
import es.topophone.data.backup.ExternalPhotos
import es.topophone.data.backup.ImportChoice
import es.topophone.data.backup.ImportResult
import es.topophone.data.backup.RestoreResult
import java.io.File
import java.io.InputStream
import java.io.OutputStream
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

    /** La subcarpeta de fotos (S017b/RF-30): nombre → contenido. */
    val photos = linkedMapOf<String, String>()

    /** Las veces que se ha escrito una foto, en orden: una foto no debe subirse dos veces. */
    val photoWrites = mutableListOf<String>()
    var failPhotoWrites = false
    var photoFolderUnreadable = false

    /** Ficheros de la carpeta que el proveedor no deja abrir. */
    val unreadable = mutableSetOf<String>()

    override suspend fun write(treeUri: String, name: String, from: File): Boolean {
        if (failWrites) return false
        files[name] = from.readText(Charsets.ISO_8859_1) // Sin pérdida: así también guarda ZIP reales.
        return true
    }

    override suspend fun openFile(treeUri: String, name: String): InputStream? =
        if (name in unreadable) null else files[name]?.toByteArray(Charsets.ISO_8859_1)?.inputStream()

    override suspend fun list(treeUri: String): List<String> = files.keys.toList()

    override suspend fun delete(treeUri: String, name: String): Boolean = files.remove(name) != null

    override suspend fun writePhoto(treeUri: String, name: String, from: File): Boolean {
        if (failPhotoWrites) return false
        photoWrites += name
        photos.getOrPut(name) { from.readText(Charsets.ISO_8859_1) }
        return true
    }

    override suspend fun listPhotos(treeUri: String): List<String>? = if (photoFolderUnreadable) null else photos.keys.toList()

    override suspend fun deletePhoto(treeUri: String, name: String): Boolean = photos.remove(name) != null

    override suspend fun openPhoto(treeUri: String, name: String): InputStream? =
        photos[name]?.toByteArray(Charsets.ISO_8859_1)?.inputStream()

    override fun persist(treeUri: String) {
        if (failPersist) throw SecurityException("sin permiso")
        persisted += treeUri
    }

    override fun release(treeUri: String) {
        persisted -= treeUri
    }
}

/** Paquetes en memoria para los tests de S013: lo que se exporta y cómo responde la importación. */
class FakePackageBackups : Backups {
    override val undoAvailable = MutableStateFlow(false)
    var writeOk = true
    val written = mutableListOf<String>()
    var readResult: BackupRead = BackupRead.Damaged
    var readKind: BackupKind? = null
    val imports = mutableListOf<ImportChoice?>()
    var importResults = ArrayDeque<ImportResult>()

    override suspend fun writePackage(projectId: String, out: OutputStream): Boolean {
        written += projectId
        if (writeOk) out.write("paquete".toByteArray())
        return writeOk
    }

    override suspend fun read(input: () -> InputStream, kind: BackupKind): BackupRead {
        readKind = kind
        return readResult
    }

    override suspend fun importPackage(valid: BackupRead.Valid, choice: ImportChoice?, importedLabel: String): ImportResult {
        imports += choice
        return importResults.removeFirstOrNull() ?: ImportResult.Failed
    }

    override suspend fun writeBackup(out: OutputStream): Boolean = false

    override suspend fun restore(valid: BackupRead.Valid, external: ExternalPhotos?, keepCopy: suspend (File) -> Unit): RestoreResult =
        RestoreResult.Failed

    override suspend fun undoRestore(): RestoreResult = RestoreResult.Failed
}
