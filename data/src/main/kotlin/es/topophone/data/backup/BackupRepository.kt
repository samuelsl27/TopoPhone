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
import es.topophone.core.model.log.AppLog
import es.topophone.core.model.log.LogCategory
import es.topophone.core.model.project.ProjectNames
import es.topophone.data.db.TopoPhoneDatabase
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.util.Base64
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** Una copia o un paquete leídos y comprobados, listos para restaurar o importar (S013/RF-6, RF-12 a RF-14). */
sealed interface BackupRead {
    /**
     * [database] ya está en la versión actual del esquema; [settings] solo en la copia completa; [photos] son los ficheros de foto del
     * ZIP por id de foto (S017b/RF-28, RF-29).
     */
    data class Valid(
        val manifest: BackupManifest,
        val database: File,
        val settings: BackupSettings?,
        val photos: Map<String, File> = emptyMap(),
    ) : BackupRead

    data object NotTopoPhone : BackupRead

    data class WrongKind(val actual: BackupKind) : BackupRead

    data object Damaged : BackupRead

    data object Newer : BackupRead
}

/** Resultado de restaurar una copia o de deshacer una restauración (S013/RF-8, RF-10, RF-11). */
sealed interface RestoreResult {
    /** [missingPhotos]: las fotos de una copia automática que no estaban en la carpeta de fotos o no coincidían (S017b/RF-33). */
    data class Restored(val projects: Int, val points: Int, val missingPhotos: Int = 0) : RestoreResult

    data object Failed : RestoreResult
}

/** Resultado de importar un paquete (S013/RF-25 a RF-28). */
sealed interface ImportResult {
    data class Imported(val projectId: String, val name: String) : ImportResult

    /** El proyecto ya está en el móvil: hay que elegir entre sustituirlo e importarlo como nuevo (S013/RF-26). */
    data class NeedsChoice(val existingName: String) : ImportResult

    data object Failed : ImportResult
}

/** Qué hacer con un paquete de un proyecto que ya está en el móvil (S013/RF-26). */
enum class ImportChoice { REPLACE, AS_NEW }

/** Lo que las pantallas usan de las copias y los paquetes (S013); lo implementa [BackupRepository]. */
interface Backups {
    /** Hay una restauración que se puede deshacer (S013/RF-11). */
    val undoAvailable: StateFlow<Boolean>

    suspend fun writeBackup(out: OutputStream): Boolean

    suspend fun read(input: () -> InputStream, kind: BackupKind): BackupRead

    suspend fun restore(valid: BackupRead.Valid, external: ExternalPhotos? = null, keepCopy: suspend (File) -> Unit = {}): RestoreResult

    suspend fun undoRestore(): RestoreResult

    suspend fun writePackage(projectId: String, out: OutputStream): Boolean

    suspend fun importPackage(valid: BackupRead.Valid, choice: ImportChoice?, importedLabel: String): ImportResult
}

/** Sitios donde los tests provocan un fallo en mitad de una restauración (S013/RF-10). */
internal class RestoreHooks(val beforeCommit: () -> Unit = {}, val afterDatabase: () -> Unit = {})

/**
 * Copias de seguridad y paquetes de proyecto (S013; plan, A2 a A6). Un cerrojo impide que una copia, una
 * restauración o una importación se pisen. [workDir] es una carpeta privada para los ficheros intermedios; [undoDir],
 * la de los ficheros para deshacer la última restauración (S013/RF-11), que no salen nunca del móvil (RNF-T11).
 */
class BackupRepository(
    private val context: Context,
    private val db: TopoPhoneDatabase,
    private val settings: SettingsArchive,
    private val appVersion: String,
    private val clock: () -> Long = System::currentTimeMillis,
    private val workDir: File = File(context.cacheDir, "backup"),
    private val undoDir: File = File(context.filesDir, "backups/undo"),
    private val newId: () -> String = { UUID.randomUUID().toString() },
    private val log: AppLog = AppLog.NONE,
    private val photosDir: File = File(context.filesDir, "photos"),
) : Backups {
    private val lock = Mutex()

    internal var hooks = RestoreHooks()

    private val undo = MutableStateFlow(File(undoDir, BEFORE_RESTORE).isFile && File(undoDir, NTRIP_SECRETS).isFile)

    /** Hay una restauración que se puede deshacer (S013/RF-11). */
    override val undoAvailable: StateFlow<Boolean> = undo.asStateFlow()

    private val databaseFile: File
        get() {
            val name = requireNotNull(db.openHelper.databaseName) { "La base de datos de la app está en un fichero" }
            return File(name).takeIf { it.isAbsolute } ?: context.getDatabasePath(name)
        }

    /** S013/RF-1, RF-5: escribe la copia completa en [out]; false si no se ha podido. No cierra [out]. */
    override suspend fun writeBackup(out: OutputStream): Boolean = lock.withLock {
        withContext(Dispatchers.IO) {
            val file = File(dir(BUILD), "backup.zip")
            try {
                val manifest = createBackupFileLocked(file)
                file.inputStream().use { it.copyTo(out) }
                out.flush()
                // S014/RF-4
                log.info(LogCategory.BACKUPS, "Copia de seguridad creada: ${counts(manifest.projectCount, manifest.pointCount)}")
                true
            } catch (_: IOException) {
                log.error(LogCategory.BACKUPS, "No se pudo guardar la copia de seguridad")
                false
            } catch (_: SQLException) {
                log.error(LogCategory.BACKUPS, "No se pudo guardar la copia de seguridad")
                false
            } finally {
                file.delete()
            }
        }
    }

    /**
     * Escribe la copia completa en [target] y devuelve su manifiesto. Con [externalPhotos] (`<id>.jpg` → fichero) es una copia
     * automática: el ZIP no lleva fotos y su manifiesto nombra esas, con su suma (S017b/RF-30, plan A6).
     */
    suspend fun createBackupFile(target: File, externalPhotos: Map<String, File>? = null): BackupManifest =
        lock.withLock { withContext(Dispatchers.IO) { createBackupFileLocked(target, externalPhotos) } }

    /** Las fotos de todas las filas que tienen fichero, por nombre de fichero (`<id>.jpg`) (S017b/RF-30). */
    suspend fun photoFilesByName(): Map<String, File> = withContext(Dispatchers.IO) {
        db.openHelper.readableDatabase.query("SELECT id FROM point_photos ORDER BY id").use { cursor ->
            buildMap {
                while (cursor.moveToNext()) {
                    val name = "${cursor.getString(0)}.jpg"
                    File(photosDir, name).takeIf { it.isFile }?.let { put(name, it) }
                }
            }
        }
    }

    /**
     * S013/RF-6, RF-12 a RF-14: lee una copia ([kind] = copia) o un paquete y lo comprueba. La base de datos se pasa a
     * la versión actual con las migraciones de Room. El resultado vale hasta la siguiente lectura.
     */
    override suspend fun read(input: () -> InputStream, kind: BackupKind): BackupRead = lock.withLock {
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
            }.also {
                if (it !is BackupRead.Valid) {
                    dir.deleteRecursively()
                    logRejected(kind, it) // S014/RF-5
                }
            }
        }
    }

    /**
     * S013/RF-7 a RF-10: guarda el estado actual para deshacer (y lo pasa a [keepCopy], que lo guarda en la carpeta de las
     * copias automáticas si están activas), sustituye los datos por los de [valid] en una sola transacción y pone sus
     * ajustes. Si algo falla, los datos quedan como estaban y se conserva lo que había para deshacer. Los perfiles NTRIP
     * que siguen en el móvil conservan su contraseña (S013/RF-9); los que solo estaban en la copia la pedirán (S013/RF-15).
     */
    override suspend fun restore(valid: BackupRead.Valid, external: ExternalPhotos?, keepCopy: suspend (File) -> Unit): RestoreResult =
        restoreUnlogged(valid, keepCopy, external).also { result ->
            // S014/RF-4
            val createdAt = valid.manifest.createdAt
            if (result is RestoreResult.Restored) {
                val missing = if (result.missingPhotos > 0) "; fotos no recuperadas: ${result.missingPhotos}" else ""
                log.info(LogCategory.BACKUPS, "Restaurada la copia del $createdAt: ${counts(result.projects, result.points)}$missing")
            } else {
                log.error(LogCategory.BACKUPS, "No se pudo restaurar la copia del $createdAt; los datos siguen como estaban")
            }
        }

    private suspend fun restoreUnlogged(
        valid: BackupRead.Valid,
        keepCopy: suspend (File) -> Unit,
        external: ExternalPhotos?,
    ): RestoreResult = lock.withLock {
        withContext(Dispatchers.IO) {
            val backupSettings = valid.settings
            if (valid.manifest.kind != BackupKind.BACKUP || backupSettings == null) return@withContext RestoreResult.Failed
            val secrets = currentSecrets()
            val before = attempt {
                if (!undoDir.isDirectory && !undoDir.mkdirs()) throw IOException("No se puede crear $undoDir")
                saveState(secrets, File(undoDir, "$BEFORE_RESTORE.tmp"), File(undoDir, "$NTRIP_SECRETS.tmp"))
            } ?: return@withContext RestoreResult.Failed // S013/RF-10
            attempt { keepCopy(before.zip) } // S013/RF-7: si la carpeta falla, la copia privada ya permite deshacer.
            // S017b/RF-32, RF-33: las fotos de una copia automática se recuperan de la carpeta de fotos; las que faltan se cuentan.
            val fetched = fetchExternalPhotos(valid.manifest, external)
            if (!apply(valid.database, backupSettings, secrets, valid.photos + fetched.photos, rollbackTo = before)) {
                before.delete()
                return@withContext RestoreResult.Failed
            }
            before.moveTo(undoState())
            undo.value = true
            RestoreResult.Restored(valid.manifest.projectCount, valid.manifest.pointCount, fetched.missing)
        }
    }

    /**
     * S013/RF-11: vuelve al estado guardado antes de la última restauración, contraseñas NTRIP incluidas, y la opción
     * desaparece. Si falla, los datos quedan como estaban y se puede volver a intentar.
     */
    override suspend fun undoRestore(): RestoreResult = undoUnlogged().also { result ->
        if (result is RestoreResult.Restored) {
            log.info(LogCategory.BACKUPS, "Deshecha la restauración") // S014/RF-4
        } else {
            log.error(LogCategory.BACKUPS, "No se pudo deshacer la restauración")
        }
    }

    private suspend fun undoUnlogged(): RestoreResult = lock.withLock {
        withContext(Dispatchers.IO) {
            val saved = undoState()
            val before = attempt { saved.open(UNDO) } ?: return@withContext RestoreResult.Failed
            val current = attempt {
                val dir = dir(CURRENT)
                saveState(currentSecrets(), File(dir, BEFORE_RESTORE), File(dir, NTRIP_SECRETS))
            } ?: return@withContext RestoreResult.Failed
            try {
                if (!apply(before.database, before.settings, before.secrets, before.photos, rollbackTo = current)) {
                    return@withContext RestoreResult.Failed
                }
            } finally {
                current.delete()
            }
            saved.delete()
            undo.value = false
            RestoreResult.Restored(before.manifest.projectCount, before.manifest.pointCount)
        }
    }

    /** S013/RF-24: escribe en [out] el paquete del proyecto [projectId]; false si no existe o no se ha podido. */
    override suspend fun writePackage(projectId: String, out: OutputStream): Boolean = lock.withLock {
        withContext(Dispatchers.IO) {
            val dir = dir(SNAPSHOT)
            try {
                val name = db.openHelper.readableDatabase.query("SELECT name FROM projects WHERE id = ?", arrayOf(projectId))
                    .use { if (it.moveToFirst()) it.getString(0) else null } ?: return@withContext false
                val database = File(dir, BackupArchive.DATABASE)
                val counts = DatabaseSnapshot(context, databaseFile).copy(database, SnapshotScope.Project(projectId))
                val manifest = BackupManifest(
                    kind = BackupKind.PACKAGE,
                    schemaVersion = TopoPhoneDatabase.VERSION,
                    appVersion = appVersion,
                    createdAt = Instant.ofEpochMilli(clock()),
                    projectCount = counts.projects,
                    pointCount = counts.points,
                    projectId = projectId,
                    projectName = name,
                )
                val zip = File(dir, "package.zip")
                zip.outputStream().use {
                    BackupArchive.write(manifest, mapOf(BackupArchive.DATABASE to database) + photoFiles(database), it)
                }
                zip.inputStream().use { it.copyTo(out) }
                out.flush()
                log.info(LogCategory.BACKUPS, "Paquete exportado: «$name»") // S014/RF-4
                true
            } catch (_: IOException) {
                log.error(LogCategory.BACKUPS, "No se pudo exportar el paquete")
                false
            } catch (_: SQLException) {
                log.error(LogCategory.BACKUPS, "No se pudo exportar el paquete")
                false
            } finally {
                dir.deleteRecursively()
            }
        }
    }

    /**
     * S013/RF-25 a RF-28: añade el proyecto del paquete [valid]. Si el mismo proyecto ya está activo en el móvil y no hay
     * [choice], devuelve [ImportResult.NeedsChoice]. Se importa con sus ids salvo como nuevo o si su id está en la
     * papelera; el nombre lleva "([importedLabel])" como nuevo o si otro proyecto activo lo tiene. Al sustituir, el del
     * móvil pasa a la papelera con ids nuevos y el del paquete se queda los suyos y su carpeta (S013/RF-27).
     */
    override suspend fun importPackage(valid: BackupRead.Valid, choice: ImportChoice?, importedLabel: String): ImportResult =
        importUnlogged(valid, choice, importedLabel).also { result ->
            // S014/RF-4
            when (result) {
                is ImportResult.Imported -> log.info(
                    LogCategory.BACKUPS,
                    when (choice) {
                        null -> "Paquete importado: «${result.name}»"
                        ImportChoice.AS_NEW -> "Paquete importado como nuevo: «${result.name}»"
                        ImportChoice.REPLACE -> "Paquete importado sustituyendo al del móvil: «${result.name}»"
                    },
                )
                is ImportResult.NeedsChoice -> Unit
                ImportResult.Failed -> log.error(LogCategory.BACKUPS, "No se pudo importar el paquete de «${valid.manifest.projectName}»")
            }
        }

    private suspend fun importUnlogged(valid: BackupRead.Valid, choice: ImportChoice?, importedLabel: String): ImportResult =
        lock.withLock {
            withContext(Dispatchers.IO) {
                val projectId = valid.manifest.projectId
                if (valid.manifest.kind != BackupKind.PACKAGE || projectId == null) return@withContext ImportResult.Failed
                // S017b/RF-29: las fotos copiadas por una importación que falla no se quedan en el móvil.
                val created = mutableListOf<File>()
                attempt { importLocked(valid.database, valid.photos, created, projectId, choice, importedLabel) }
                    ?: ImportResult.Failed.also { created.forEach(File::delete) }
            }
        }

    private fun importLocked(
        database: File,
        photos: Map<String, File>,
        created: MutableList<File>,
        projectId: String,
        choice: ImportChoice?,
        importedLabel: String,
    ): ImportResult = SQLiteDatabase.openDatabase(database.path, null, SQLiteDatabase.OPEN_READONLY).use { from ->
        val packageName = from.rawQuery("SELECT name FROM projects WHERE id = ?", arrayOf(projectId))
            .use { if (it.moveToFirst()) it.getString(0) else null } ?: return ImportResult.Failed
        val main = db.openHelper.writableDatabase
        val existing = main.query("SELECT name FROM projects WHERE id = ? AND deleted_at IS NULL", arrayOf(projectId))
            .use { if (it.moveToFirst()) it.getString(0) else null }
        if (existing != null && choice == null) return ImportResult.NeedsChoice(existing)
        if (existing != null && choice == ImportChoice.REPLACE) {
            return replaceProject(from, main, photos, created, projectId, packageName, importedLabel)
        }
        val idTaken = main.query("SELECT 1 FROM projects WHERE id = ?", arrayOf(projectId)).use { it.moveToFirst() }
        val activeNames = main.query("SELECT name FROM projects WHERE deleted_at IS NULL").use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.getString(0)) }
        }
        val name = if (choice == ImportChoice.AS_NEW) {
            ProjectNames.copyName(packageName, activeNames, importedLabel)
        } else {
            ProjectNames.restoredName(packageName, activeNames, importedLabel)
        }
        val newProjectId = if (idTaken) newId() else projectId
        db.runInTransaction(
            Runnable {
                val photoSource = PhotoSource({ photos[it] }, created)
                copyProject({ sql, args ->
                    from.rawQuery(sql, args)
                }, main, projectId, newProjectId, !idTaken, photoSource) { project ->
                    project.put("name", name)
                    project.putNull("folder_id") // S013: el paquete no lleva la carpeta.
                }
            },
        )
        ImportResult.Imported(newProjectId, name)
    }

    /** S013/RF-27: el proyecto del móvil, con ids nuevos, a la papelera (S006/RF-22); el del paquete, en su sitio. */
    private fun replaceProject(
        from: SQLiteDatabase,
        main: androidx.sqlite.db.SupportSQLiteDatabase,
        photos: Map<String, File>,
        created: MutableList<File>,
        projectId: String,
        packageName: String,
        importedLabel: String,
    ): ImportResult {
        val folderId = main.query("SELECT folder_id FROM projects WHERE id = ?", arrayOf(projectId))
            .use { if (it.moveToFirst() && !it.isNull(0)) it.getString(0) else null }
        val otherNames = main.query("SELECT name FROM projects WHERE deleted_at IS NULL AND id <> ?", arrayOf(projectId)).use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.getString(0)) }
        }
        val name = ProjectNames.restoredName(packageName, otherNames, importedLabel)
        val trashedId = newId()
        val now = clock()
        db.runInTransaction(
            Runnable {
                // S017b/RF-29: el del móvil, a la papelera, se lleva copias de sus fotos con ids nuevos (los ids no se repiten en la base).
                val ownPhotos = PhotoSource({ id -> File(photosDir, "$id.jpg").takeIf { it.isFile } }, created)
                copyProject({ sql, args ->
                    main.query(sql, args)
                }, main, projectId, trashedId, false, ownPhotos) { it.put("deleted_at", now) }
                main.execSQL("DELETE FROM projects WHERE id = ?", arrayOf(projectId)) // Sus puntos, historial y encuadre se van con él.
                val packagePhotos = PhotoSource({ photos[it] }, created)
                copyProject({ sql, args -> from.rawQuery(sql, args) }, main, projectId, projectId, true, packagePhotos) { project ->
                    project.put("name", name)
                    if (folderId == null) project.putNull("folder_id") else project.put("folder_id", folderId)
                }
            },
        )
        return ImportResult.Imported(projectId, name)
    }

    /**
     * Copia el proyecto [oldId] de [source] a [sink] como [newProjectId], con sus encuadres, sus puntos (también los de su
     * papelera) y su historial. Sin [keepIds], cada punto y cada cambio del historial reciben un id nuevo; la clave de
     * papelera de un punto borrado es su id (S011). [project] cambia la fila del proyecto antes de insertarla.
     */
    private fun copyProject(
        source: (String, Array<String>) -> android.database.Cursor,
        sink: androidx.sqlite.db.SupportSQLiteDatabase,
        oldId: String,
        newProjectId: String,
        keepIds: Boolean,
        photoSource: PhotoSource,
        project: (android.content.ContentValues) -> Unit,
    ) {
        val args = arrayOf(oldId)
        val surfaceIds = surfaceIds(source, args, keepIds)
        val alignmentIds = alignmentIds(source, args, keepIds)
        val shiftIds = baseShiftIds(source, args, keepIds)
        source("SELECT * FROM projects WHERE id = ?", args).use { cursor ->
            DatabaseSnapshot.copyRows(cursor, sink, "projects") {
                it.put("id", newProjectId)
                val active = it.getAsString("active_surface_id")?.let(surfaceIds::get)
                if (active == null) it.putNull("active_surface_id") else it.put("active_surface_id", active)
                val activeAlignment = it.getAsString("active_alignment_id")?.let(alignmentIds::get)
                if (activeAlignment == null) it.putNull("active_alignment_id") else it.put("active_alignment_id", activeAlignment)
                project(it)
            }
        }
        source("SELECT * FROM map_viewports WHERE project_id = ?", args).use { cursor ->
            DatabaseSnapshot.copyRows(cursor, sink, "map_viewports") { it.put("project_id", newProjectId) }
        }
        val pointIds = mutableMapOf<String, String>()
        source("SELECT * FROM points WHERE project_id = ?", args).use { cursor ->
            DatabaseSnapshot.copyRows(cursor, sink, "points") { point ->
                val old = point.getAsString("id")
                val id = if (keepIds) old else newId()
                pointIds[old] = id
                point.put("id", id)
                point.put("project_id", newProjectId)
                if (point.getAsString("trash_key").isNotEmpty()) point.put("trash_key", id)
                // S020/RF-8: el punto sigue señalando el cálculo de su desplazamiento, que en la copia tiene id nuevo.
                point.getAsString("shift_id")?.let { oldShift -> point.put("shift_id", shiftIds[oldShift] ?: oldShift) }
            }
        }
        source("SELECT * FROM point_history WHERE point_id IN (SELECT id FROM points WHERE project_id = ?)", args).use { cursor ->
            DatabaseSnapshot.copyRows(cursor, sink, "point_history") { change ->
                if (!keepIds) change.put("id", newId())
                change.put("point_id", pointIds.getValue(change.getAsString("point_id")))
            }
        }
        copyPhotos(source, sink, args, pointIds, photoSource)
        copyDrawings(source, sink, args, newProjectId, keepIds)
        copySurfaces(source, sink, args, newProjectId, surfaceIds)
        copyAlignments(source, sink, args, newProjectId, alignmentIds)
        copyBaseShifts(source, sink, args, newProjectId, shiftIds)
    }

    /** De dónde sacar el fichero de una foto al copiarla y qué ficheros se han creado, para borrarlos si la importación falla. */
    private class PhotoSource(val file: (String) -> File?, val created: MutableList<File>)

    /**
     * Copia las filas de las fotos y sus sucesos con los puntos ya copiados ([pointIds]) y los ficheros de las fotos a `photos/`
     * (S017b/RF-29). Una foto cuyo id ya existe en la base de datos recibe uno nuevo; su fichero se copia con el nombre nuevo, sin
     * deduplicar. La foto de una fila sin fichero se copia sin fichero (se abrirá como RF-17).
     */
    private fun copyPhotos(
        source: (String, Array<String>) -> android.database.Cursor,
        sink: androidx.sqlite.db.SupportSQLiteDatabase,
        args: Array<String>,
        pointIds: Map<String, String>,
        photoSource: PhotoSource,
    ) {
        val inProject = "point_id IN (SELECT id FROM points WHERE project_id = ?)"
        source("SELECT * FROM point_photos WHERE $inProject", args).use { cursor ->
            DatabaseSnapshot.copyRows(cursor, sink, "point_photos") { photo ->
                val old = photo.getAsString("id")
                val taken = sink.query("SELECT 1 FROM point_photos WHERE id = ?", arrayOf(old)).use { it.moveToFirst() }
                val id = if (taken) newId() else old
                photo.put("id", id)
                photo.put("point_id", pointIds.getValue(photo.getAsString("point_id")))
                val from = photoSource.file(old)
                if (from != null) {
                    photosDir.mkdirs()
                    val target = File(photosDir, "$id.jpg")
                    photoSource.created += target
                    from.copyTo(target, overwrite = true)
                }
            }
        }
        source("SELECT * FROM photo_events WHERE $inProject", args).use { cursor ->
            DatabaseSnapshot.copyRows(cursor, sink, "photo_events") { event ->
                event.put("id", newId())
                event.put("point_id", pointIds.getValue(event.getAsString("point_id")))
            }
        }
    }

    /** De los ids de los desplazamientos de base del proyecto a los que tendrán al copiarlos: los mismos con [keepIds], nuevos si no (S020/RF-12). */
    private fun baseShiftIds(
        source: (String, Array<String>) -> android.database.Cursor,
        args: Array<String>,
        keepIds: Boolean,
    ): Map<String, String> = source("SELECT id FROM base_shifts WHERE project_id = ?", args).use { cursor ->
        buildMap { while (cursor.moveToNext()) cursor.getString(0).let { old -> put(old, if (keepIds) old else newId()) } }
    }

    /** La lista de desplazamientos del proyecto con el activo (S020/RF-12). */
    private fun copyBaseShifts(
        source: (String, Array<String>) -> android.database.Cursor,
        sink: androidx.sqlite.db.SupportSQLiteDatabase,
        args: Array<String>,
        newProjectId: String,
        shiftIds: Map<String, String>,
    ) {
        source("SELECT * FROM base_shifts WHERE project_id = ? ORDER BY created_at_ms, rowid", args).use { cursor ->
            DatabaseSnapshot.copyRows(cursor, sink, "base_shifts") { shift ->
                shift.put("id", shiftIds.getValue(shift.getAsString("id")))
                shift.put("project_id", newProjectId)
            }
        }
    }

    /** De los ids de los ejes del proyecto a los que tendrán al copiarlos: los mismos con [keepIds], nuevos si no (S049/RF-16). */
    private fun alignmentIds(
        source: (String, Array<String>) -> android.database.Cursor,
        args: Array<String>,
        keepIds: Boolean,
    ): Map<String, String> = source("SELECT id FROM alignments WHERE project_id = ?", args).use { cursor ->
        buildMap { while (cursor.moveToNext()) cursor.getString(0).let { old -> put(old, if (keepIds) old else newId()) } }
    }

    /** Los ejes del proyecto con su planta y su rasante (S049/RF-16). El eje activo va en la fila del proyecto. */
    private fun copyAlignments(
        source: (String, Array<String>) -> android.database.Cursor,
        sink: androidx.sqlite.db.SupportSQLiteDatabase,
        args: Array<String>,
        newProjectId: String,
        alignmentIds: Map<String, String>,
    ) {
        source("SELECT * FROM alignments WHERE project_id = ? ORDER BY imported_at_ms, rowid", args).use { cursor ->
            DatabaseSnapshot.copyRows(cursor, sink, "alignments") { alignment ->
                alignment.put("id", alignmentIds.getValue(alignment.getAsString("id")))
                alignment.put("project_id", newProjectId)
            }
        }
    }

    /**
     * Los dibujos terminados del proyecto con sus capas y trozos (S040/RF-31). Sin [keepIds] cada dibujo y cada capa reciben un id
     * nuevo; los trozos reciben siempre el suyo, porque su identificador es de la base de datos de cada móvil.
     */
    private fun copyDrawings(
        source: (String, Array<String>) -> android.database.Cursor,
        sink: androidx.sqlite.db.SupportSQLiteDatabase,
        args: Array<String>,
        newProjectId: String,
        keepIds: Boolean,
    ) {
        val drawingIds = mutableMapOf<String, String>()
        val ready = "SELECT id FROM cad_drawings WHERE project_id = ? AND status = 'READY'"
        source("SELECT * FROM cad_drawings WHERE project_id = ? AND status = 'READY' ORDER BY imported_at_ms, id", args).use { cursor ->
            DatabaseSnapshot.copyRows(cursor, sink, "cad_drawings") { drawing ->
                val old = drawing.getAsString("id")
                val id = if (keepIds) old else newId()
                drawingIds[old] = id
                drawing.put("id", id)
                drawing.put("project_id", newProjectId)
            }
        }
        val layerIds = mutableMapOf<String, String>()
        source("SELECT * FROM cad_layers WHERE drawing_id IN ($ready)", args).use { cursor ->
            DatabaseSnapshot.copyRows(cursor, sink, "cad_layers") { layer ->
                val old = layer.getAsString("id")
                val id = if (keepIds) old else newId()
                layerIds[old] = id
                layer.put("id", id)
                layer.put("drawing_id", drawingIds.getValue(layer.getAsString("drawing_id")))
            }
        }
        val chunks = "SELECT * FROM cad_chunks WHERE layer_id IN (SELECT id FROM cad_layers WHERE drawing_id IN ($ready))"
        val oldChunkIds = ArrayDeque<Long>()
        val chunkIds = mutableMapOf<Long, Long>()
        source(chunks, args).use { cursor ->
            DatabaseSnapshot.copyRows(cursor, sink, "cad_chunks", onInserted = { chunkIds[oldChunkIds.removeFirst()] = it }) { chunk ->
                oldChunkIds.addLast(chunk.getAsLong("id"))
                chunk.remove("id")
                chunk.put("layer_id", layerIds.getValue(chunk.getAsString("layer_id")))
            }
        }
        // S048/RF-28: los atributos de los objetos de un GeoPackage siguen a su trozo, que recibe un id nuevo.
        val attributes = "SELECT * FROM cad_chunk_attributes WHERE chunk_id IN " +
            "(SELECT c.id FROM cad_chunks c JOIN cad_layers l ON l.id = c.layer_id WHERE l.drawing_id IN ($ready))"
        source(attributes, args).use { cursor ->
            DatabaseSnapshot.copyRows(cursor, sink, "cad_chunk_attributes") { row ->
                row.put("chunk_id", chunkIds.getValue(row.getAsLong("chunk_id")))
            }
        }
    }

    /** De los ids de las superficies terminadas del proyecto a los que tendrán al copiarlas: los mismos con [keepIds], nuevos si no. */
    private fun surfaceIds(
        source: (String, Array<String>) -> android.database.Cursor,
        args: Array<String>,
        keepIds: Boolean,
    ): Map<String, String> = source("SELECT id FROM surfaces WHERE project_id = ? AND status = 'READY'", args).use { cursor ->
        buildMap { while (cursor.moveToNext()) cursor.getString(0).let { old -> put(old, if (keepIds) old else newId()) } }
    }

    /**
     * Las superficies terminadas del proyecto con sus teselas o sus bloques (S042/RF-21, S045/RF-20). La superficie activa y el desplazamiento vertical van en
     * la fila del proyecto. Los trozos reciben siempre un id propio, porque el suyo es de la base de datos de cada móvil.
     */
    private fun copySurfaces(
        source: (String, Array<String>) -> android.database.Cursor,
        sink: androidx.sqlite.db.SupportSQLiteDatabase,
        args: Array<String>,
        newProjectId: String,
        surfaceIds: Map<String, String>,
    ) {
        source("SELECT * FROM surfaces WHERE project_id = ? AND status = 'READY' ORDER BY imported_at_ms, id", args).use { cursor ->
            DatabaseSnapshot.copyRows(cursor, sink, "surfaces") { surface ->
                surface.put("id", surfaceIds.getValue(surface.getAsString("id")))
                surface.put("project_id", newProjectId)
            }
        }
        val tiles = "SELECT * FROM surface_tiles WHERE surface_id IN (SELECT id FROM surfaces WHERE project_id = ? AND status = 'READY')"
        source(tiles, args).use { cursor ->
            DatabaseSnapshot.copyRows(cursor, sink, "surface_tiles") { tile ->
                tile.remove("id")
                tile.put("surface_id", surfaceIds.getValue(tile.getAsString("surface_id")))
            }
        }
        // S045/RF-20: los bloques de los rásteres, con el id de su superficie en la copia y uno propio.
        val rasterTiles =
            "SELECT * FROM raster_tiles WHERE surface_id IN (SELECT id FROM surfaces WHERE project_id = ? AND status = 'READY')"
        source(rasterTiles, args).use { cursor ->
            DatabaseSnapshot.copyRows(cursor, sink, "raster_tiles") { tile ->
                tile.remove("id")
                tile.put("surface_id", surfaceIds.getValue(tile.getAsString("surface_id")))
            }
        }
    }

    /** Lo que se guarda para deshacer la última restauración. */
    private fun undoState() = SavedState(File(undoDir, BEFORE_RESTORE), File(undoDir, NTRIP_SECRETS))

    /** Contraseñas cifradas tal como están en la base de datos, por perfil; las marcas vacías no cuentan. */
    private fun currentSecrets(): Map<String, Secret> = db.openHelper.readableDatabase
        .query("SELECT id, password_iv, password_ct FROM ntrip_profiles WHERE length(password_ct) > 0")
        .use { cursor ->
            buildMap { while (cursor.moveToNext()) put(cursor.getString(0), Secret(cursor.getBlob(1), cursor.getBlob(2))) }
        }

    /** Copia completa del estado actual en [zip] y sus contraseñas cifradas en [secretsFile]. */
    private suspend fun saveState(secrets: Map<String, Secret>, zip: File, secretsFile: File): SavedState {
        createBackupFileLocked(zip)
        secretsFile.writeText(encode(secrets))
        return SavedState(zip, secretsFile)
    }

    /**
     * Pone [database], [secrets] y [newSettings]. Si la base de datos falla, su transacción se deshace sola; si fallan los
     * ajustes, se vuelve a [rollbackTo] (S013/RF-10). false si no se ha podido.
     */
    private suspend fun apply(
        database: File,
        newSettings: BackupSettings,
        secrets: Map<String, Secret>,
        photos: Map<String, File>,
        rollbackTo: SavedState,
    ): Boolean {
        if (attempt { replaceDatabase(database, secrets, photos, withHooks = true) } == null) return false
        val applied = attempt {
            hooks.afterDatabase()
            settings.restore(newSettings)
        }
        if (applied == null) {
            attempt {
                val previous = rollbackTo.open(ROLLBACK) ?: return@attempt
                replaceDatabase(previous.database, previous.secrets, previous.photos, withHooks = false)
                settings.restore(previous.settings)
            }
            return false
        }
        return true
    }

    /**
     * Sustituye todas las tablas por las de [file] en una transacción de Room (los flujos avisan a las pantallas) y vuelve
     * a poner [secrets] en los perfiles que existan. Si algo falla, la transacción se deshace sola.
     */
    private fun replaceDatabase(file: File, secrets: Map<String, Secret>, photos: Map<String, File>, withHooks: Boolean) {
        val incoming = stagePhotos(photos)
        var swapped: PhotoSwap? = null
        try {
            replaceTables(file, secrets) {
                // S017b/RF-28: las fotos se intercambian dentro de la transacción, que todavía se puede deshacer.
                swapped = swapPhotos(incoming)
                if (withHooks) hooks.beforeCommit()
            }
        } catch (e: Exception) {
            swapped?.undo()
            incoming.deleteRecursively()
            throw e
        }
        swapped?.finish()
    }

    private fun replaceTables(file: File, secrets: Map<String, Secret>, beforeCommit: () -> Unit) {
        SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).use { from ->
            db.runInTransaction(
                Runnable {
                    val sink = db.openHelper.writableDatabase
                    DatabaseSnapshot.TABLES.asReversed().forEach { sink.execSQL("DELETE FROM ${it.name}") }
                    DatabaseSnapshot.TABLES.forEach { DatabaseSnapshot.copyRows(from, sink, it.name) }
                    secrets.forEach { (id, secret) ->
                        sink.execSQL(
                            "UPDATE ntrip_profiles SET password_iv = ?, password_ct = ? WHERE id = ?",
                            arrayOf<Any>(secret.iv, secret.ciphertext, id),
                        )
                    }
                    beforeCommit()
                },
            )
        }
    }

    /** Copia las fotos extraídas de una copia a `photos.incoming`, al lado de la carpeta de fotos (S017b plan A5). */
    private fun stagePhotos(photos: Map<String, File>): File {
        val incoming = File(photosDir.parentFile, PHOTOS_INCOMING)
        incoming.deleteRecursively()
        if (!incoming.mkdirs()) throw IOException("No se puede crear la carpeta de fotos")
        photos.forEach { (id, file) -> file.copyTo(File(incoming, "$id.jpg"), overwrite = true) }
        return incoming
    }

    /** Las fotos de antes, apartadas en `photos.old` mientras la restauración no ha terminado (S017b plan A5). */
    private inner class PhotoSwap(private val old: File, private val hadPhotos: Boolean) {
        fun undo() {
            photosDir.deleteRecursively()
            if (hadPhotos) old.renameTo(photosDir)
        }

        fun finish() {
            old.deleteRecursively()
        }
    }

    private fun swapPhotos(incoming: File): PhotoSwap {
        val old = File(photosDir.parentFile, PHOTOS_OLD)
        old.deleteRecursively()
        val hadPhotos = photosDir.exists()
        if (hadPhotos && !photosDir.renameTo(old)) throw IOException("No se pueden apartar las fotos")
        if (!incoming.renameTo(photosDir)) {
            if (hadPhotos) old.renameTo(photosDir)
            throw IOException("No se pueden poner las fotos de la copia")
        }
        return PhotoSwap(old, hadPhotos)
    }

    /** Una copia completa y sus contraseñas cifradas guardadas en el móvil. */
    private inner class SavedState(val zip: File, val secretsFile: File) {
        fun delete() {
            zip.delete()
            secretsFile.delete()
        }

        /** Sustituye a [target] solo cuando la restauración ha ido bien. */
        fun moveTo(target: SavedState) {
            Files.move(secretsFile.toPath(), target.secretsFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
            Files.move(zip.toPath(), target.zip.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }

        /** Extraída en la carpeta de trabajo [dirName]; null si no está o no se puede leer. */
        fun open(dirName: String): Opened? {
            if (!zip.isFile || !secretsFile.isFile) return null
            val valid = zip.inputStream().use { BackupArchive.read(it, BackupKind.BACKUP, TopoPhoneDatabase.VERSION, dir(dirName)) }
                as? BackupReadResult.Valid ?: return null
            val savedSettings = valid.files[BackupArchive.SETTINGS]?.let { BackupSettingsJson.decode(it.readText()) } ?: return null
            return Opened(
                valid.manifest,
                valid.files.getValue(BackupArchive.DATABASE),
                savedSettings,
                decode(secretsFile.readText()),
                photosOf(valid.files),
            )
        }
    }

    private class Opened(
        val manifest: BackupManifest,
        val database: File,
        val settings: BackupSettings,
        val secrets: Map<String, Secret>,
        val photos: Map<String, File>,
    )

    private class Secret(val iv: ByteArray, val ciphertext: ByteArray)

    private fun encode(secrets: Map<String, Secret>): String = JSONObject().apply {
        val base64 = Base64.getEncoder()
        secrets.forEach { (id, secret) ->
            put(id, JSONObject().put("iv", base64.encodeToString(secret.iv)).put("ct", base64.encodeToString(secret.ciphertext)))
        }
    }.toString()

    private fun decode(text: String): Map<String, Secret> {
        val json = JSONObject(text)
        val base64 = Base64.getDecoder()
        return json.keys().asSequence().associateWith { id ->
            val secret = json.getJSONObject(id)
            Secret(base64.decode(secret.getString("iv")), base64.decode(secret.getString("ct")))
        }
    }

    /** Ejecuta [block]; null si falla (salvo la cancelación, que sigue su camino). */
    @Suppress("TooGenericExceptionCaught") // S013/RF-10: cualquier fallo deja los datos como estaban.
    private inline fun <T> attempt(block: () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }

    private fun logRejected(kind: BackupKind, result: BackupRead) {
        val what = if (kind == BackupKind.BACKUP) "copia de seguridad" else "paquete"
        val reason = when (result) {
            BackupRead.NotTopoPhone -> "no es de TopoPhone"
            is BackupRead.WrongKind -> if (result.actual == BackupKind.PACKAGE) "es un paquete" else "es una copia de seguridad"
            BackupRead.Damaged -> "está dañado o incompleto"
            BackupRead.Newer -> "es de una versión más nueva de la app"
            is BackupRead.Valid -> return
        }
        log.warning(LogCategory.BACKUPS, "Fichero rechazado como $what: $reason")
    }

    private fun counts(projects: Int, points: Int): String =
        "${if (projects == 1) "1 proyecto" else "$projects proyectos"} y ${if (points == 1) "1 punto" else "$points puntos"}"

    private fun checked(valid: BackupReadResult.Valid): BackupRead {
        val database = valid.files.getValue(BackupArchive.DATABASE)
        checkDatabase(database, valid.manifest.schemaVersion)?.let { return it }
        val backupSettings = valid.files[BackupArchive.SETTINGS]?.let {
            BackupSettingsJson.decode(it.readText())
                ?: return BackupRead.Damaged
        }
        if (valid.manifest.kind == BackupKind.BACKUP && backupSettings == null) return BackupRead.Damaged
        return BackupRead.Valid(valid.manifest, database, backupSettings, photosOf(valid.files))
    }

    /** Las fotos de los ficheros extraídos de un ZIP, por id de foto. */
    private fun photosOf(files: Map<String, File>): Map<String, File> = files
        .filterKeys { it.startsWith("${BackupArchive.PHOTOS_DIR}/") }
        .mapKeys { (name, _) -> name.removePrefix("${BackupArchive.PHOTOS_DIR}/").removeSuffix(".jpg") }

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

    private class FetchedPhotos(val photos: Map<String, File>, val missing: Int)

    /**
     * Trae de [external] las fotos que el manifiesto de una copia automática nombra (S017b/RF-32, plan A7): cada una se guarda en una
     * carpeta de trabajo y se comprueba contra su suma. Una que falta, que no se puede leer o cuya suma no coincide se cuenta como no
     * recuperada (RF-33); sin [external] cuentan todas.
     */
    private suspend fun fetchExternalPhotos(manifest: BackupManifest, external: ExternalPhotos?): FetchedPhotos {
        if (manifest.externalPhotos.isEmpty()) return FetchedPhotos(emptyMap(), 0)
        val dir = dir(EXTERNAL)
        val got = mutableMapOf<String, File>()
        var missing = 0
        for ((name, sum) in manifest.externalPhotos) {
            val file = File(dir, name)
            val ok = attempt {
                external?.open(name)?.use { input -> file.outputStream().use { out -> sha256(input, out) } == sum } == true
            } == true
            if (ok) got[name.removeSuffix(".jpg")] = file else missing++.also { file.delete() }
        }
        return FetchedPhotos(got, missing)
    }

    private fun sha256(input: java.io.InputStream, out: OutputStream?): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
            out?.write(buffer, 0, read)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /** Sumas SHA-256 de las fotos ya calculadas: una foto guardada no cambia, así que la suma sirve mientras no cambie su fichero. */
    private val photoSums = HashMap<String, Triple<Long, Long, String>>()

    private fun photoSum(name: String, file: File): String {
        val known = photoSums[name]
        if (known != null && known.first == file.length() && known.second == file.lastModified()) return known.third
        return file.inputStream().use { sha256(it, null) }
            .also { photoSums[name] = Triple(file.length(), file.lastModified(), it) }
    }

    private suspend fun createBackupFileLocked(target: File, externalPhotos: Map<String, File>? = null): BackupManifest {
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
                externalPhotos = externalPhotos?.mapValues { (name, file) -> photoSum(name, file) } ?: emptyMap(),
            )
            target.parentFile?.mkdirs()
            target.outputStream().use {
                BackupArchive.write(
                    manifest,
                    mapOf(
                        BackupArchive.DATABASE to database,
                        BackupArchive.SETTINGS to settingsFile,
                    ) + if (externalPhotos == null) photoFiles(database) else emptyMap(),
                    it,
                )
            }
            return manifest
        } finally {
            dir.deleteRecursively()
        }
    }

    /**
     * Los ficheros de foto de todas las filas de `point_photos` de la instantánea [database], también los de los puntos de la papelera
     * (S017b/RF-28). Una fila sin fichero no lleva nada: esa foto ya no se podía abrir (RF-17).
     */
    private fun photoFiles(database: File): Map<String, File> =
        SQLiteDatabase.openDatabase(database.path, null, SQLiteDatabase.OPEN_READONLY)
            .use { sqlite ->
                sqlite.rawQuery("SELECT id FROM point_photos ORDER BY id", null).use { cursor ->
                    buildMap {
                        while (cursor.moveToNext()) {
                            val id = cursor.getString(0)
                            File(photosDir, "$id.jpg").takeIf { it.isFile }?.let { put(BackupArchive.photoEntry(id), it) }
                        }
                    }
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

    companion object {
        /** La copia completa del estado anterior a la última restauración, en [undoDir]. */
        const val BEFORE_RESTORE = "before-restore.zip"

        /** Las contraseñas cifradas de ese estado, que la copia no lleva (RNF-T11). */
        const val NTRIP_SECRETS = "ntrip-secrets.json"

        private const val BUILD = "build"
        private const val READ = "read"
        private const val SNAPSHOT = "snapshot"
        private const val UNDO = "undo"
        private const val CURRENT = "current"
        private const val ROLLBACK = "rollback"
        private const val EXTERNAL = "external-photos"
        private const val PHOTOS_INCOMING = "photos.incoming"
        private const val PHOTOS_OLD = "photos.old"
    }
}
