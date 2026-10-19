package es.topophone.data.backup

import android.content.Context
import android.database.SQLException
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.room.RoomDatabase
import es.topophone.core.formats.backup.BackupArchive
import es.topophone.core.formats.backup.BackupKind
import es.topophone.core.formats.backup.BackupManifest
import es.topophone.core.formats.backup.BackupReadResult
import es.topophone.core.formats.backup.BackupSettings
import es.topophone.core.formats.backup.BackupSettingsJson
import es.topophone.data.db.TopoPhoneDatabase
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Una copia o un paquete leídos y comprobados, listos para restaurar o importar (S013/RF-6, RF-12 a RF-14). */
sealed interface BackupRead {
    /** [database] ya está en la versión actual del esquema; [settings] solo en la copia completa. */
    data class Valid(val manifest: BackupManifest, val database: File, val settings: BackupSettings?) : BackupRead

    data object NotTopoPhone : BackupRead

    data class WrongKind(val actual: BackupKind) : BackupRead

    data object Damaged : BackupRead

    data object Newer : BackupRead
}

/**
 * Copias de seguridad y paquetes de proyecto (S013; plan, A2 a A6). Un cerrojo impide que una copia, una
 * restauración o una importación se pisen. [workDir] es una carpeta privada para los ficheros intermedios.
 */
class BackupRepository(
    private val context: Context,
    private val db: TopoPhoneDatabase,
    private val settings: SettingsArchive,
    private val appVersion: String,
    private val clock: () -> Long = System::currentTimeMillis,
    private val workDir: File = File(context.cacheDir, "backup"),
) {
    private val lock = Mutex()

    private val databaseFile: File
        get() {
            val name = requireNotNull(db.openHelper.databaseName) { "La base de datos de la app está en un fichero" }
            return File(name).takeIf { it.isAbsolute } ?: context.getDatabasePath(name)
        }

    /** S013/RF-1, RF-5: escribe la copia completa en [out]; false si no se ha podido. No cierra [out]. */
    suspend fun writeBackup(out: OutputStream): Boolean = lock.withLock {
        withContext(Dispatchers.IO) {
            val file = File(dir(BUILD), "backup.zip")
            try {
                createBackupFileLocked(file)
                file.inputStream().use { it.copyTo(out) }
                out.flush()
                true
            } catch (_: IOException) {
                false
            } catch (_: SQLException) {
                false
            } finally {
                file.delete()
            }
        }
    }

    /** Escribe la copia completa en [target] y devuelve su manifiesto. */
    suspend fun createBackupFile(target: File): BackupManifest =
        lock.withLock { withContext(Dispatchers.IO) { createBackupFileLocked(target) } }

    /**
     * S013/RF-6, RF-12 a RF-14: lee una copia ([kind] = copia) o un paquete y lo comprueba. La base de datos se pasa a
     * la versión actual con las migraciones de Room. El resultado vale hasta la siguiente lectura.
     */
    suspend fun read(input: () -> InputStream, kind: BackupKind): BackupRead = lock.withLock {
        withContext(Dispatchers.IO) {
            val dir = dir(READ)
            val result = try {
                input().use { BackupArchive.read(it, kind, TopoPhoneDatabase.VERSION, dir) }
            } catch (_: IOException) {
                BackupReadResult.Damaged
            } catch (_: SecurityException) {
                BackupReadResult.Damaged
            }
            when (result) {
                BackupReadResult.NotTopoPhone -> BackupRead.NotTopoPhone
                BackupReadResult.Damaged -> BackupRead.Damaged
                BackupReadResult.Newer -> BackupRead.Newer
                is BackupReadResult.WrongKind -> BackupRead.WrongKind(result.actual)
                is BackupReadResult.Valid -> checked(result)
            }.also { if (it !is BackupRead.Valid) dir.deleteRecursively() }
        }
    }

    private fun checked(valid: BackupReadResult.Valid): BackupRead {
        val database = valid.files.getValue(BackupArchive.DATABASE)
        checkDatabase(database, valid.manifest.schemaVersion)?.let { return it }
        val backupSettings = valid.files[BackupArchive.SETTINGS]?.let {
            BackupSettingsJson.decode(it.readText())
                ?: return BackupRead.Damaged
        }
        if (valid.manifest.kind == BackupKind.BACKUP && backupSettings == null) return BackupRead.Damaged
        return BackupRead.Valid(valid.manifest, database, backupSettings)
    }

    /** null si la base de datos es de TopoPhone y ya está en la versión actual (plan, A3 paso 4). */
    private fun checkDatabase(file: File, manifestVersion: Int): BackupRead? {
        try {
            SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).use { sqlite ->
                if (sqlite.version > TopoPhoneDatabase.VERSION) return BackupRead.Newer // S013/RF-13
                if (sqlite.version < 1 || sqlite.version != manifestVersion) return BackupRead.Damaged
                sqlite.rawQuery("PRAGMA integrity_check", null).use {
                    if (!it.moveToFirst() ||
                        it.getString(0) != "ok"
                    ) {
                        return BackupRead.Damaged
                    }
                }
            }
            // Room pasa la base de datos a la versión actual (S013/RF-14) y comprueba que su esquema es el de la app.
            val room = archiveDatabase(file)
            try {
                room.openHelper.writableDatabase.query("PRAGMA foreign_key_check").use { if (it.moveToFirst()) return BackupRead.Damaged }
            } finally {
                room.close()
            }
        } catch (_: SQLException) {
            return BackupRead.Damaged
        } catch (_: IllegalStateException) {
            return BackupRead.Damaged
        }
        return null
    }

    private suspend fun createBackupFileLocked(target: File): BackupManifest {
        val dir = dir(SNAPSHOT)
        try {
            val database = File(dir, BackupArchive.DATABASE)
            val counts = DatabaseSnapshot(context, databaseFile).copy(database, SnapshotScope.All)
            val settingsFile = File(dir, BackupArchive.SETTINGS)
            settingsFile.writeText(BackupSettingsJson.encode(settings.export()))
            val manifest = BackupManifest(
                kind = BackupKind.BACKUP,
                schemaVersion = TopoPhoneDatabase.VERSION,
                appVersion = appVersion,
                createdAt = Instant.ofEpochMilli(clock()),
                projectCount = counts.projects,
                pointCount = counts.points,
            )
            target.parentFile?.mkdirs()
            target.outputStream().use {
                BackupArchive.write(
                    manifest,
                    mapOf(
                        BackupArchive.DATABASE to database,
                        BackupArchive.SETTINGS to settingsFile,
                    ),
                    it,
                )
            }
            return manifest
        } finally {
            dir.deleteRecursively()
        }
    }

    /** Una base de datos de una copia abierta con Room, sin WAL, para que quede en un solo fichero. */
    private fun archiveDatabase(file: File): TopoPhoneDatabase =
        Room.databaseBuilder(context.applicationContext, TopoPhoneDatabase::class.java, file.absolutePath)
            .setJournalMode(RoomDatabase.JournalMode.TRUNCATE)
            .build()

    /** Una subcarpeta vacía de [workDir]. */
    private fun dir(name: String): File = File(workDir, name).apply {
        deleteRecursively()
        mkdirs()
    }

    private companion object {
        const val BUILD = "build"
        const val READ = "read"
        const val SNAPSHOT = "snapshot"
    }
}
