package es.topophone.app.ui.receiver

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import es.topophone.app.R
import java.io.File

/** Autoridad del FileProvider de la app (manifiesto y `res/xml/file_paths.xml`). */
fun fileProviderAuthority(context: Context) = "${context.packageName}.fileprovider"

/** Intención para compartir el fichero de una sesión con las apps del móvil (S002/RF-43). */
fun shareSessionIntent(
    context: Context,
    file: File,
    uri: Uri = FileProvider.getUriForFile(context, fileProviderAuthority(context), file),
): Intent {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_SUBJECT, file.name)
        // Con ClipData el permiso de lectura llega también a través del selector.
        clipData = ClipData.newRawUri(file.name, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    return Intent.createChooser(send, context.getString(R.string.receiver_share_session_chooser))
}

/**
 * Intención para compartir todos los ficheros de una sesión: las partes de los datos del receptor y
 * las de las correcciones (S002/RF-43, S003/RF-30). Con un solo fichero, como [shareSessionIntent].
 */
fun shareSessionFilesIntent(
    context: Context,
    files: List<File>,
    uris: List<Uri> = files.map { FileProvider.getUriForFile(context, fileProviderAuthority(context), it) },
): Intent {
    require(files.isNotEmpty() && files.size == uris.size)
    if (files.size == 1) return shareSessionIntent(context, files.single(), uris.single())
    val send = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
        type = "*/*"
        putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
        putExtra(Intent.EXTRA_SUBJECT, files.first().parentFile?.name ?: files.first().name)
        clipData = ClipData.newRawUri(files.first().name, uris.first()).apply { uris.drop(1).forEach { addItem(ClipData.Item(it)) } }
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    return Intent.createChooser(send, context.getString(R.string.receiver_share_session_chooser))
}

fun shareSession(context: Context, files: List<File>) {
    context.startActivity(shareSessionFilesIntent(context, files))
}
