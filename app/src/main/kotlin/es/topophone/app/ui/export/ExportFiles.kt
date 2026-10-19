package es.topophone.app.ui.export

import android.content.ClipData
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import es.topophone.app.R
import es.topophone.app.ui.receiver.fileProviderAuthority
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.io.OutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Carpeta de la caché desde la que se comparten los CSV exportados (S012/RF-18; `res/xml/file_paths.xml`). */
object ExportFiles {
    const val DIR = "exports"
    private const val MAX_AGE_MS = 24L * 60 * 60 * 1000

    /** El fichero [fileName] en `cache/exports/`, tras borrar los que llevan más de un día; Android puede vaciar la caché. */
    fun prepare(cacheDir: File, fileName: String, nowMs: Long): File {
        val folder = File(cacheDir, DIR).apply { mkdirs() }
        folder.listFiles()?.filter { nowMs - it.lastModified() > MAX_AGE_MS }?.forEach { it.delete() }
        return File(folder, fileName)
    }
}

/** Intención para compartir un CSV exportado con las apps del móvil (S012/RF-18). */
fun shareExportIntent(
    context: Context,
    file: File,
    uri: Uri = FileProvider.getUriForFile(context, fileProviderAuthority(context), file),
): Intent {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/csv"
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_SUBJECT, file.name)
        // Con ClipData el permiso de lectura llega también a través del selector.
        clipData = ClipData.newRawUri(file.name, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    return Intent.createChooser(send, context.getString(R.string.export_share_chooser))
}

/**
 * Abre el sitio que el usuario eligió con el selector de ficheros y escribe en él (S012/RF-17); false si no se ha
 * podido abrir o escribir (S012/RF-20).
 */
suspend fun writeToUri(resolver: ContentResolver, uri: Uri, write: suspend (OutputStream) -> Boolean): Boolean {
    val out = withContext(Dispatchers.IO) {
        try {
            resolver.openOutputStream(uri, "wt")
        } catch (_: FileNotFoundException) {
            null
        } catch (_: SecurityException) {
            null
        } catch (_: IOException) {
            null
        }
    } ?: return false
    return out.use { write(it) }
}
