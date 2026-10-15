package es.topophone.core.gnss.nmea

import java.time.DateTimeException
import java.time.LocalDate

/** Campo que no se puede interpretar: la frase entera se descarta (S002/RF-11, S003/RF-7). */
internal class CorruptField : IllegalArgumentException()

/** Campo vacío → `null`; campo no numérico → [CorruptField]. */
internal fun String.optionalDouble(): Double? = if (isEmpty()) null else toDoubleOrNull() ?: throw CorruptField()

internal fun String.optionalInt(): Int? = if (isEmpty()) null else toIntOrNull() ?: throw CorruptField()

/** Grados → radianes; vacío → `null`. */
internal fun String.optionalRadians(): Double? = optionalDouble()?.let(Math::toRadians)

/** Hora `hhmmss[.sss]` → milisegundos del día; vacía → `null`; imposible → [CorruptField]. */
internal fun String.optionalTime(): Int? = if (isEmpty()) null else NmeaTime.parse(this) ?: throw CorruptField()

/** Fecha a partir de día, mes y año; imposible → [CorruptField]. */
internal fun date(year: Int, month: Int, day: Int): LocalDate = try {
    LocalDate.of(year, month, day)
} catch (_: DateTimeException) {
    throw CorruptField()
}

/** `ddmm.mmmm` / `dddmm.mmmm` y hemisferio → grados decimales; vacío → NaN. */
internal fun coordinate(value: String, hemisphere: String, degreeDigits: Int): Double {
    if (value.isEmpty()) return Double.NaN
    if (value.length < degreeDigits + 2) throw CorruptField()
    val degrees = value.substring(0, degreeDigits).optionalInt() ?: throw CorruptField()
    val minutes = value.substring(degreeDigits).optionalDouble() ?: throw CorruptField()
    val decimal = degrees + minutes / 60.0
    return when (hemisphere) {
        "N", "E" -> decimal
        "S", "W" -> -decimal
        else -> throw CorruptField()
    }
}

/** Interpreta los campos con [block]; si alguno no se puede interpretar, `null` (S003/RF-7). */
internal inline fun <T> parseFields(block: () -> T?): T? = try {
    block()
} catch (_: CorruptField) {
    null
}

/** Hora UTC de las frases NMEA (S003/RF-3). */
object NmeaTime {
    private val PATTERN = Regex("""^(\d{2})(\d{2})(\d{2})(?:\.(\d{1,3})\d*)?$""")
    const val MILLIS_PER_DAY = 86_400_000

    /** `hhmmss[.sss]` → milisegundos desde las 00:00 UTC; `null` si está vacía o es imposible. */
    fun parse(text: String): Int? {
        val match = PATTERN.matchEntire(text) ?: return null
        val (h, m, s, fraction) = match.destructured
        val hours = h.toInt()
        val minutes = m.toInt()
        val seconds = s.toInt()
        if (hours > 23 || minutes > 59 || seconds > 59) return null
        val millis = if (fraction.isEmpty()) 0 else fraction.padEnd(3, '0').toInt()
        return ((hours * 60 + minutes) * 60 + seconds) * 1000 + millis
    }
}
