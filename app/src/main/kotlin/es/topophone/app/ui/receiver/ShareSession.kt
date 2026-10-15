package es.topophone.app.ui.receiver

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import es.topophone.app.R
import java.io.File

/** Autoridad del FileProvider de la app (manifiesto y `res/xml/file_paths.xml`). */
fun fileProviderAuthority(context: Context) = "${context.packageName}.fileprovider"

/** Intención para compartir el fichero de una sesión con las apps del móvil (S002/RF-43). */
fun shareSessionIntent(context: Context, file: File): Intent {
    val uri = FileProvider.getUriForFile(context, fileProviderAuthority(context), file)
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_SUBJECT, file.name)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    return Intent.createChooser(send, context.getString(R.string.receiver_share_session_chooser))
}

fun shareSession(context: Context, file: File) {
    context.startActivity(shareSessionIntent(context, file))
}
