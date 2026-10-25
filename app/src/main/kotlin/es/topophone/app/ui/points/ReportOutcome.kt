package es.topophone.app.ui.points

import android.content.ContentResolver
import android.net.Uri
import es.topophone.app.ui.export.writeToUri
import java.io.OutputStream

/** Cómo acabó guardar el informe de errores de una importación (S028/RF-24). */
enum class ReportOutcome { SAVED, FAILED }

/**
 * Escribe el informe en el sitio que el usuario eligió con el selector de ficheros. Con [uri] null, el usuario canceló el
 * selector: no se escribe nada y no hay nada que decir (null).
 */
suspend fun saveReportTo(resolver: ContentResolver, uri: Uri?, write: suspend (OutputStream) -> Boolean): ReportOutcome? {
    if (uri == null) return null
    return if (writeToUri(resolver, uri, write)) ReportOutcome.SAVED else ReportOutcome.FAILED
}
