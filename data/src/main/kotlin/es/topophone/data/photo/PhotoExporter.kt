package es.topophone.data.photo

import es.topophone.core.formats.photo.PhotoArchiveNames
import es.topophone.core.formats.photo.PhotoIndexRow
import es.topophone.core.formats.photo.PhotoNameRef
import es.topophone.core.formats.photo.PhotoZipEntry
import es.topophone.core.formats.photo.PhotoZipResult
import es.topophone.core.formats.photo.PhotoZipWriter
import es.topophone.core.model.point.PointPhoto
import java.io.BufferedOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Un punto con las fotos que se le exportan, en el orden en que se añadieron (S056/RF-1, RF-2); [id] distingue nombres repetidos. */
class PhotoExportPoint(val id: String, val name: String, val photos: List<PointPhoto>)

/**
 * Lo que va a tener el ZIP de fotos (S056/RF-3): cuántas fotos, de cuántos puntos y cuánto ocupan sus ficheros guardados. [zipBytes] es una
 * cota por arriba de lo que pesará el ZIP: esos bytes y [ENTRY_BYTES] por foto, para su entrada y su fila del índice (RF-10, RF-11).
 */
data class PhotoExportEstimate(val photos: Int, val points: Int, val photoBytes: Long) {
    val zipBytes: Long get() = photoBytes + photos * ENTRY_BYTES + HEADER_BYTES

    /** El ZIP va a pasar de 1 GB: hay que avisar antes de escribirlo (RF-10). */
    val exceedsWarning: Boolean get() = zipBytes > WARNING_BYTES

    companion object {
        /** Aviso de tamaño del ZIP (S056/RF-10): 1 GB. */
        const val WARNING_BYTES = 1L shl 30

        /** Lo que añaden a un ZIP la cabecera de una entrada, su registro en el directorio central y su fila del índice, con holgura. */
        const val ENTRY_BYTES = 400L
        private const val HEADER_BYTES = 1_024L
    }
}

/** Cómo acabó escribir el ZIP de fotos (S056/RF-9, RF-11). */
sealed interface PhotoExportOutcome {
    /** El ZIP está entero en el fichero. */
    data class Written(val result: PhotoZipResult) : PhotoExportOutcome

    /** No había sitio para el ZIP: no se ha escrito nada (RF-11). */
    data object NoSpace : PhotoExportOutcome

    /** Ninguna de las fotos se pudo leer ([skipped]): no hay ZIP (RF-9). */
    data class NothingReadable(val skipped: Int) : PhotoExportOutcome

    /** No se pudo escribir: no queda ningún fichero (RF-11). */
    data object Failed : PhotoExportOutcome
}

/**
 * Escribe el ZIP de fotos de unos puntos (S056/RF-3, RF-4, RF-9 a RF-11, RF-14): las fotos tal como están guardadas, con los nombres de
 * [PhotoArchiveNames] y el índice `fotos.csv` al final. Solo lee los ficheros de [photos]; no cambia ninguna foto ni el proyecto (RF-14).
 * [freeSpace] da el espacio libre de una carpeta y [open] abre el fichero de una foto; los dos cambian en los tests.
 */
class PhotoExporter(
    private val photos: PhotoRepository,
    private val freeSpace: (File) -> Long = { it.usableSpace },
    private val open: (File) -> InputStream = { it.inputStream() },
) {

    /** Las fotos, los puntos con alguna foto y lo que ocupan sus ficheros guardados (RF-3). */
    fun estimate(points: List<PhotoExportPoint>): PhotoExportEstimate {
        val withPhotos = points.filter { it.photos.isNotEmpty() }
        val photoCount = withPhotos.sumOf { it.photos.size }
        return PhotoExportEstimate(photoCount, withPhotos.size, withPhotos.sumOf { p -> p.photos.sumOf { it.sizeBytes } })
    }

    /**
     * Escribe el ZIP entero en [into]: antes mira que haya sitio para su tamaño previsto y un 5 por ciento más (RF-11) y, si falla,
     * borra lo escrito (RNF-T6). Una foto cuyo fichero falta o no se lee se omite y se cuenta (RF-9).
     */
    suspend fun writeFile(points: List<PhotoExportPoint>, into: File): PhotoExportOutcome {
        val estimate = estimate(points)
        val needed = estimate.zipBytes + estimate.zipBytes / SPACE_MARGIN_DIVISOR
        if (freeSpace(into.absoluteFile.parentFile ?: into) < needed) return PhotoExportOutcome.NoSpace
        return withContext(Dispatchers.IO) {
            try {
                val written = BufferedOutputStream(into.outputStream(), BUFFER_BYTES).use { PhotoZipWriter.write(entries(points), it) }
                if (written.photosWritten == 0) {
                    into.delete()
                    PhotoExportOutcome.NothingReadable(written.photosSkipped)
                } else {
                    PhotoExportOutcome.Written(written)
                }
            } catch (_: IOException) {
                into.delete()
                PhotoExportOutcome.Failed
            } catch (_: SecurityException) {
                into.delete()
                PhotoExportOutcome.Failed
            }
        }
    }

    /** Las entradas del ZIP en el orden de los puntos y de sus fotos, con el nombre de cada una y su fichero (RF-5). */
    private fun entries(points: List<PhotoExportPoint>): List<PhotoZipEntry> {
        val withPhotos = points.filter { it.photos.isNotEmpty() }
        val names = PhotoArchiveNames.of(withPhotos.map { PhotoNameRef(it.name, it.photos.size) })
        return withPhotos.flatMapIndexed { index, point ->
            point.photos.mapIndexed { i, photo ->
                val row = PhotoIndexRow(names[index][i], point.name, i + 1, photo.takenAtMs, photo.origin, photo.sizeBytes)
                PhotoZipEntry(row, point.id) { photos.file(photo.id)?.let(open) }
            }
        }
    }

    private companion object {
        const val BUFFER_BYTES = 64 * 1024
        const val SPACE_MARGIN_DIVISOR = 20L
    }
}
