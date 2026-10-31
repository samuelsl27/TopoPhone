package es.topophone.data.photo

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.ExifInterface
import es.topophone.core.model.point.PhotoOrigin
import es.topophone.core.model.point.PhotoPolicy
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

/** Por qué no se pudo añadir una foto (S017b/RF-15). */
enum class PhotoFailure { UNREADABLE, NO_SPACE, WRITE_FAILED }

/** Una foto ya reducida y escrita en `photos/pending/`, a la espera de su punto (S017b plan A3). */
data class StagedPhoto(val id: String, val origin: PhotoOrigin, val takenAtMs: Long, val sizeBytes: Long, val width: Int, val height: Int)

sealed interface StageResult {
    data class Staged(val photo: StagedPhoto) : StageResult

    data class Failed(val reason: PhotoFailure) : StageResult
}

/**
 * Reduce y guarda las fotos en `[root]/photos` (S017b plan A1 y A4). Una foto nueva queda en `photos/pending/<id>.jpg` hasta que se
 * guarda su punto; el repositorio la mueve a `photos/<id>.jpg`. [open] da el contenido de la fuente (la cámara o la galería) y solo se
 * lee: nunca se escribe en la foto original (RF-19). Todo se escribe en un fichero temporal y se renombra al terminar (RF-20).
 */
// El ExifInterface del framework basta con minSdk 26 y evita una dependencia nueva (S017b plan, decisiones técnicas).
@SuppressLint("ExifInterface")
class PhotoStore(
    private val root: File,
    private val open: (String) -> InputStream?,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    val photosDir: File get() = File(root, "photos")
    val pendingDir: File get() = File(photosDir, "pending")

    fun photoFile(id: String) = File(photosDir, "$id.jpg")

    fun pendingFile(id: String) = File(pendingDir, "$id.jpg")

    /** Reduce la foto de [source] y la deja en espera; ante cualquier fallo no queda nada escrito (RF-15, RF-20). */
    fun stage(source: String, origin: PhotoOrigin): StageResult {
        val id = UUID.randomUUID().toString()
        val raw = File(pendingDir, "$id.src.tmp")
        val out = File(pendingDir, "$id.jpg.tmp")
        val final = pendingFile(id)
        return try {
            check(pendingDir.isDirectory || pendingDir.mkdirs()) { "No se puede crear ${pendingDir.name}" }
            val input = open(source) ?: return StageResult.Failed(PhotoFailure.UNREADABLE)
            input.use { stream -> raw.outputStream().use { stream.copyTo(it) } }
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            decode(raw, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return StageResult.Failed(PhotoFailure.UNREADABLE)

            val target = PhotoPolicy.reducedSize(bounds.outWidth, bounds.outHeight)
            val sourceExif = readExif(raw)
            val asIs = bounds.outMimeType == "image/jpeg" && target.width == bounds.outWidth && target.height == bounds.outHeight
            if (asIs) {
                raw.copyTo(out, overwrite = true)
            } else {
                if (!writeReduced(raw, out, bounds.outWidth, bounds.outHeight, target.width, target.height)) {
                    return StageResult.Failed(PhotoFailure.UNREADABLE)
                }
                copyMetadata(sourceExif, out, target.width, target.height)
            }
            val takenAt = takenAt(sourceExif) ?: clock()
            if (!out.renameTo(final)) throw IOException("No se pudo renombrar la foto")
            StageResult.Staged(StagedPhoto(id, origin, takenAt, final.length(), target.width, target.height))
        } catch (e: IOException) {
            StageResult.Failed(
                if (e.message.orEmpty().contains("space", ignoreCase = true) ||
                    e.message.orEmpty().contains("ENOSPC")
                ) {
                    PhotoFailure.NO_SPACE
                } else {
                    PhotoFailure.WRITE_FAILED
                },
            )
        } catch (e: IllegalStateException) {
            StageResult.Failed(PhotoFailure.WRITE_FAILED)
        } finally {
            raw.delete()
            out.delete()
        }
    }

    /**
     * Borra los ficheros sin fila: los de `photos/` cuyo id no está en [keepIds] y los de `photos/pending/` que no son de las fotos en
     * espera [keepPending]; también los temporales sueltos. Devuelve cuántos borró (plan A4).
     */
    fun sweep(keepIds: Set<String>, keepPending: Set<String> = emptySet()): Int {
        var deleted = 0
        photosDir.listFiles().orEmpty().filter { it.isFile }.forEach {
            if (it.name.removeSuffix(".jpg") !in keepIds || !it.name.endsWith(".jpg")) if (it.delete()) deleted++
        }
        pendingDir.listFiles().orEmpty().filter { it.isFile }.forEach {
            if (it.name.removeSuffix(".jpg") !in keepPending || !it.name.endsWith(".jpg")) if (it.delete()) deleted++
        }
        return deleted
    }

    /** Descarta una foto en espera (RF-4). */
    fun discard(id: String) {
        pendingFile(id).delete()
    }

    private fun writeReduced(raw: File, out: File, width: Int, height: Int, targetW: Int, targetH: Int): Boolean {
        var sample = 1
        while (width / (sample * 2) >= targetW && height / (sample * 2) >= targetH) sample *= 2
        val decoded = decode(raw, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return false
        val scaled = if (decoded.width == targetW &&
            decoded.height == targetH
        ) {
            decoded
        } else {
            Bitmap.createScaledBitmap(decoded, targetW, targetH, true)
        }
        try {
            out.outputStream().use {
                check(scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it)) { "No se pudo escribir la foto" }
            }
        } finally {
            if (scaled !== decoded) scaled.recycle()
            decoded.recycle()
        }
        return true
    }

    private fun decode(file: File, options: BitmapFactory.Options): Bitmap? =
        file.inputStream().use { BitmapFactory.decodeStream(it, null, options) }

    private fun readExif(file: File): ExifInterface? = try {
        ExifInterface(file.path)
    } catch (e: IOException) {
        null
    }

    /** Copia los metadatos de la original (fecha, orientación, ubicación, cámara…); la orientación no se aplica a los píxeles (RF-18). */
    private fun copyMetadata(from: ExifInterface?, to: File, width: Int, height: Int) {
        if (from == null) return
        try {
            val target = ExifInterface(to.path)
            for (tag in TAGS) from.getAttribute(tag)?.let { target.setAttribute(tag, it) }
            target.setAttribute(ExifInterface.TAG_IMAGE_WIDTH, width.toString())
            target.setAttribute(ExifInterface.TAG_IMAGE_LENGTH, height.toString())
            target.saveAttributes()
        } catch (e: IOException) {
            // Una foto sin metadatos sigue siendo una foto: solo se pierden los metadatos.
        }
    }

    /** La fecha de la foto: `DateTimeOriginal`, y si no la tiene o no se entiende, nada (la pone quien llama) (RF-11). */
    private fun takenAt(exif: ExifInterface?): Long? {
        val text = exif?.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL) ?: return null
        val offset = exif.getAttribute(OFFSET_TIME_ORIGINAL)
        return try {
            val format = SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.ROOT)
            format.timeZone = if (offset != null) TimeZone.getTimeZone("GMT$offset") else TimeZone.getDefault()
            format.parse(text)?.time
        } catch (e: java.text.ParseException) {
            null
        }
    }

    private companion object {
        const val JPEG_QUALITY = 85
        const val OFFSET_TIME_ORIGINAL = "OffsetTimeOriginal"
        val TAGS = listOf(
            ExifInterface.TAG_DATETIME,
            ExifInterface.TAG_DATETIME_ORIGINAL,
            ExifInterface.TAG_DATETIME_DIGITIZED,
            ExifInterface.TAG_ORIENTATION,
            ExifInterface.TAG_MAKE,
            ExifInterface.TAG_MODEL,
            ExifInterface.TAG_SOFTWARE,
            ExifInterface.TAG_GPS_LATITUDE,
            ExifInterface.TAG_GPS_LATITUDE_REF,
            ExifInterface.TAG_GPS_LONGITUDE,
            ExifInterface.TAG_GPS_LONGITUDE_REF,
            ExifInterface.TAG_GPS_ALTITUDE,
            ExifInterface.TAG_GPS_ALTITUDE_REF,
            ExifInterface.TAG_GPS_TIMESTAMP,
            ExifInterface.TAG_GPS_DATESTAMP,
            ExifInterface.TAG_EXPOSURE_TIME,
            ExifInterface.TAG_F_NUMBER,
            ExifInterface.TAG_FOCAL_LENGTH,
            ExifInterface.TAG_WHITE_BALANCE,
            OFFSET_TIME_ORIGINAL,
        )
    }
}
