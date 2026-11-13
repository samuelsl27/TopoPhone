package es.topophone.core.formats.kml.export

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Una foto del KMZ: su [name] en `files/` (el que cita la descripción de su marca) y cómo se abre su fichero (S031/RF-28). */
class KmzPhoto(val name: String, val open: () -> InputStream)

/** Lo que entró en un KMZ: cuántas fotos se escribieron y cuántas se omitieron por no poder leerse (S031/RF-30). */
data class KmzResult(val photosWritten: Int, val photosSkipped: Int)

/**
 * Escribe un KMZ (S031/RF-28 a RF-30): un ZIP con `doc.kml` como primera entrada y las fotos de los puntos en `files/`, byte a byte como
 * están guardadas y sin recomprimir (un JPEG ya está comprimido). Antes de escribir se comprueba que cada foto se puede abrir: la que no se
 * puede se omite, se cuenta y su marca no lleva `<img>` (RF-30), así que `doc.kml` no cita una foto que falta. Si una foto se pierde justo
 * entre la comprobación y su escritura, se omite igual antes de su primer byte y solo queda citada en `doc.kml`.
 */
object KmzWriter {
    const val MAIN_ENTRY = "doc.kml"
    private const val TEXT_BUFFER = 64 * 1024
    private const val COPY_BUFFER = 64 * 1024

    /** Escribe [document] y [photos] en [out] sin cerrarlo; [photos] repetidas por nombre cuentan una vez. */
    fun write(document: KmlDocument, photos: List<KmzPhoto>, out: OutputStream): KmzResult {
        val unique = photos.distinctBy { it.name }
        val readable = unique.filter { canOpen(it) }
        var skipped = unique.size - readable.size
        val zip = ZipOutputStream(out)
        zip.setLevel(Deflater.DEFAULT_COMPRESSION)
        zip.putNextEntry(ZipEntry(MAIN_ENTRY))
        val writer = OutputStreamWriter(zip, Charsets.UTF_8).buffered(TEXT_BUFFER)
        KmlWriter.write(onlyPhotos(document, readable.mapTo(HashSet()) { it.name }), writer)
        writer.flush()
        zip.closeEntry()
        zip.setLevel(Deflater.NO_COMPRESSION)
        var written = 0
        for (photo in readable) {
            val input = try {
                photo.open()
            } catch (_: IOException) {
                skipped++
                continue
            }
            input.use {
                zip.putNextEntry(ZipEntry(KmlWriter.PHOTO_FOLDER + photo.name))
                it.copyTo(zip, COPY_BUFFER)
                zip.closeEntry()
            }
            written++
        }
        zip.finish()
        return KmzResult(written, skipped)
    }

    private fun canOpen(photo: KmzPhoto): Boolean = try {
        photo.open().close()
        true
    } catch (_: IOException) {
        false
    }

    /** [document] con, en cada marca, solo las fotos de [names]: las demás no se escriben y no dejan su `<img>`. */
    private fun onlyPhotos(document: KmlDocument, names: Set<String>): KmlDocument {
        val folders = document.folders.map { folder ->
            val marks = folder.marks.map { mark ->
                if (mark.photos.all { it in names }) mark else mark.withPhotos(mark.photos.filter { it in names })
            }
            KmlFolder(folder.name, marks)
        }
        return KmlDocument(document.name, document.styles, folders)
    }

    private fun KmlMark.withPhotos(photos: List<String>) = KmlMark(name, description, styleId, data, geometry, absolute, photos)
}
