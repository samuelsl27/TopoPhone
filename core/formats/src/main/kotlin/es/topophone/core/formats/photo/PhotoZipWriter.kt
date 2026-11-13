package es.topophone.core.formats.photo

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Una foto del ZIP: su [row] del índice (con el nombre que lleva en el ZIP, y el tamaño que dice el registro, que el escritor sustituye
 * por el de los bytes que escribe), el [pointKey] del punto al que pertenece (dos puntos con el mismo nombre son dos puntos) y cómo se abre
 * su fichero; `null` si no existe (S056/RF-9).
 */
class PhotoZipEntry(val row: PhotoIndexRow, val pointKey: String, val open: () -> InputStream?)

/**
 * Lo que entró en un ZIP de fotos (S056/RF-13): las fotos y los puntos con alguna foto escritos, las fotos omitidas por no poder leerse
 * (RF-9) y los bytes del ZIP.
 */
data class PhotoZipResult(val photosWritten: Int, val pointsWritten: Int, val photosSkipped: Int, val zipBytes: Long)

/**
 * Escribe el ZIP de fotos (S056/RF-4, RF-6, RF-9): cada foto byte a byte como está guardada y sin recomprimir (un JPEG ya está comprimido),
 * en el orden dado, y el índice `fotos.csv` el último, con solo las fotos que se escribieron y los bytes de cada una. Una foto que no se puede
 * abrir, está vacía o falla en su primera lectura se omite antes de su primer byte y se cuenta; si falla a mitad de la copia, el ZIP queda
 * inservible y la excepción sale para que quien llama borre lo escrito (RF-11). No cierra [out] ni toca ningún fichero de foto (RF-14).
 */
object PhotoZipWriter {
    private const val COPY_BUFFER = 64 * 1024

    fun write(entries: List<PhotoZipEntry>, out: OutputStream): PhotoZipResult {
        val counting = CountingOutputStream(out)
        val zip = ZipOutputStream(counting)
        zip.setLevel(Deflater.NO_COMPRESSION)
        val written = ArrayList<PhotoIndexRow>(entries.size)
        val points = HashSet<String>()
        var skipped = 0
        val buffer = ByteArray(COPY_BUFFER)
        for (entry in entries) {
            val copied = copy(entry, zip, buffer)
            if (copied == null) {
                skipped++
                continue
            }
            val row = entry.row
            written += PhotoIndexRow(row.photoName, row.pointName, row.number, row.takenAtMs, row.origin, copied)
            points += entry.pointKey
        }
        zip.setLevel(Deflater.DEFAULT_COMPRESSION)
        zip.putNextEntry(ZipEntry(PhotoIndexCsv.FILE_NAME))
        val writer = OutputStreamWriter(zip, Charsets.UTF_8)
        PhotoIndexCsv.write(written, writer)
        writer.flush()
        zip.closeEntry()
        zip.finish()
        return PhotoZipResult(written.size, points.size, skipped, counting.count)
    }

    /** Los bytes que se copiaron a [zip], o `null` si la foto no se pudo leer y no se escribió nada. */
    private fun copy(entry: PhotoZipEntry, zip: ZipOutputStream, buffer: ByteArray): Long? {
        val input = try {
            entry.open()
        } catch (_: IOException) {
            null
        } ?: return null
        return input.use {
            val first = try {
                it.read(buffer)
            } catch (_: IOException) {
                -1
            }
            if (first <= 0) return@use null
            zip.putNextEntry(ZipEntry(entry.row.photoName))
            zip.write(buffer, 0, first)
            var total = first.toLong()
            while (true) {
                val n = it.read(buffer)
                if (n < 0) break
                zip.write(buffer, 0, n)
                total += n
            }
            zip.closeEntry()
            total
        }
    }

    private class CountingOutputStream(private val out: OutputStream) : OutputStream() {
        var count = 0L
            private set

        override fun write(b: Int) {
            out.write(b)
            count++
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            out.write(b, off, len)
            count += len
        }

        override fun flush() = out.flush()
    }
}
