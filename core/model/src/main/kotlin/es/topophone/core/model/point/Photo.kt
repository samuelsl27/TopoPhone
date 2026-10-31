package es.topophone.core.model.point

import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/** De dónde viene una foto de un punto (S017b/RF-1, RF-11). */
enum class PhotoOrigin { CAMERA, GALLERY }

/**
 * Una foto de un punto, medido o manual (S017b). El contenido está en un fichero aparte; aquí solo sus datos.
 *
 * @property name el nombre de la foto, único en su punto (véase [PhotoNames]).
 * @property takenAtMs la fecha de la foto: la de sus metadatos y, si no los tiene, la de cuando se añadió (UTC, ms).
 * @property addedAtMs cuándo se añadió (UTC, ms); da el orden de las fotos de un punto.
 * @property sizeBytes lo que ocupa el fichero guardado.
 */
data class PointPhoto(
    val id: String,
    val pointId: String,
    val name: String,
    val origin: PhotoOrigin,
    val takenAtMs: Long,
    val addedAtMs: Long,
    val sizeBytes: Long,
    val width: Int,
    val height: Int,
)

/** El tamaño en píxeles de una imagen. */
data class PhotoSize(val width: Int, val height: Int)

/** Qué fotos de una selección caben en un punto (S017b/RF-13). */
data class PhotoFit(val accepted: Int, val rejected: Int)

/** Los límites fijos de las fotos y cuántas caben (S017b/RF-13, RF-14, RF-18). No se eligen en Ajustes. */
object PhotoPolicy {
    const val MAX_PER_POINT = 10
    const val MAX_LONG_SIDE_PX = 2_048

    /** Cuántas fotos más admite un punto que ya tiene [existing]. */
    fun remaining(existing: Int): Int = (MAX_PER_POINT - existing).coerceAtLeast(0)

    /** De [offered] fotos elegidas, las que caben en un punto con [existing] y las que se quedan fuera (RF-13, RF-14). */
    fun fit(existing: Int, offered: Int): PhotoFit {
        val accepted = minOf(offered.coerceAtLeast(0), remaining(existing))
        return PhotoFit(accepted, offered.coerceAtLeast(0) - accepted)
    }

    /** El tamaño con el que se guarda una foto de [width] × [height]: lado largo a 2.048 px, sin ampliar las menores (RF-18). */
    fun reducedSize(width: Int, height: Int): PhotoSize {
        require(width > 0 && height > 0) { "El tamaño de la foto debe ser positivo" }
        val long = maxOf(width, height)
        if (long <= MAX_LONG_SIDE_PX) return PhotoSize(width, height)
        val scale = MAX_LONG_SIDE_PX.toDouble() / long
        fun scaled(side: Int): Int = if (side == long) MAX_LONG_SIDE_PX else Math.round(side * scale).toInt().coerceAtLeast(1)
        return PhotoSize(scaled(width), scaled(height))
    }
}

/** El nombre de cada foto: `<punto>_<AAAAMMDD-HHMMSS>.jpg`, único en el punto (S017b, plan: modelo de datos). */
object PhotoNames {
    private val stamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss", Locale.ROOT).withZone(ZoneOffset.UTC)

    /** El nombre de una foto de [pointName] hecha en [takenAtMs]; si [existing] ya lo tiene, añade `_2`, `_3`… antes de la extensión. */
    fun of(pointName: String, takenAtMs: Long, existing: Collection<String>): String {
        val base = "${pointName}_${stamp.format(Instant.ofEpochMilli(takenAtMs))}"
        var candidate = "$base.jpg"
        var n = 2
        while (candidate in existing) candidate = "${base}_${n++}.jpg"
        return candidate
    }
}

/** Cuántas fotos tiene un proyecto y cuánto ocupan (S017b/RF-27). */
data class PhotoStats(val count: Int, val bytes: Long) {
    companion object {
        val NONE = PhotoStats(0, 0L)
    }
}
