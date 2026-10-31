package es.topophone.app.ui.photos

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.util.LruCache
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Carga las fotos de los puntos para mostrarlas, sin librerías de imágenes (S017b plan A8): las miniaturas decodificadas a unos 256 px
 * con `inSampleSize`, en segundo plano y con una caché, y la foto entera a la medida de la pantalla. La orientación de los metadatos se
 * aplica al mostrar, nunca a los píxeles del fichero. Un fichero que no se puede decodificar da null (RF-17) y no se guarda en la caché.
 *
 * @property file el fichero de la foto por su id, o null si no existe.
 */
@Suppress("TooGenericExceptionCaught") // Un fichero dañado no debe cerrar la app: es «no se puede abrir la foto» (RF-17).
@SuppressLint("ExifInterface") // El del framework basta con minSdk 26 y evita una dependencia nueva (plan, decisiones técnicas).
class PhotoImages(private val file: (String) -> File?, private val cache: LruCache<String, Bitmap> = defaultCache()) {

    /** La miniatura de la foto [photoId] (RF-9), o null si no se puede abrir (RF-17). */
    suspend fun thumbnail(photoId: String): Bitmap? = load(photoId, THUMBNAIL_PX, key = "t:$photoId")

    /** La foto entera, con su mayor lado a [maxSidePx] como mucho, para el visor (RF-10). */
    suspend fun full(photoId: String, maxSidePx: Int): Bitmap? = load(photoId, maxSidePx, key = "f:$maxSidePx:$photoId")

    /** Olvida lo cargado de una foto, p. ej. al borrarla. */
    fun forget(photoId: String) {
        cache.snapshot().keys.filter { it.endsWith(":$photoId") }.forEach(cache::remove)
    }

    private suspend fun load(photoId: String, maxSidePx: Int, key: String): Bitmap? {
        cache.get(key)?.let { return it }
        val source = file(photoId) ?: return null
        val bitmap = withContext(Dispatchers.IO) {
            try {
                decode(source, maxSidePx)
            } catch (e: Exception) {
                null
            }
        }
        if (bitmap != null) cache.put(key, bitmap)
        return bitmap
    }

    private fun decode(source: File, maxSidePx: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        source.inputStream().use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSidePx) sample *= 2
        val decoded = source.inputStream().use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: return null
        val matrix = orientation(source)
        return if (matrix == null) {
            decoded
        } else {
            Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true).also { if (it !== decoded) decoded.recycle() }
        }
    }

    /** La matriz de la orientación de los metadatos, o null si la foto ya está derecha (plan A8). */
    private fun orientation(source: File): Matrix? {
        val value = try {
            ExifInterface(source.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        } catch (e: IOException) {
            ExifInterface.ORIENTATION_NORMAL
        }
        val matrix = Matrix()
        when (value) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                matrix.postRotate(90f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                matrix.postRotate(270f)
                matrix.postScale(-1f, 1f)
            }
            else -> return null
        }
        return matrix
    }

    companion object {
        /** El lado de una miniatura: unos 256 px (plan A8). */
        const val THUMBNAIL_PX = 256

        /** Sin fotos: para los tests y las pantallas que no las usan. */
        val NONE = PhotoImages({ null })

        private fun defaultCache(): LruCache<String, Bitmap> {
            // Un octavo de la memoria de la app, medido en KiB.
            val kib = (Runtime.getRuntime().maxMemory() / 1024 / 8).toInt()
            return object : LruCache<String, Bitmap>(kib) {
                override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
            }
        }
    }
}
