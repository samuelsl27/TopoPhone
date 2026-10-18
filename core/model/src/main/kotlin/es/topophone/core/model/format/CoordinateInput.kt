package es.topophone.core.model.format

import es.topophone.core.model.project.AngularUnit
import java.math.BigDecimal

/**
 * Números escritos por el usuario, con coma o punto decimal (S011/RF-8, RF-17). Si aparecen los dos signos, el
 * último es el decimal y el otro separa los miles; un mismo signo repetido, o los espacios, separan los miles.
 * Así se lee también lo que escribe la interfaz en cualquier idioma (`LengthFormat`).
 */
object NumberInput {
    private val SPACES = Regex("""[\s  ]""")
    private val DIGITS_AND_SEPARATORS = Regex("""[0-9.,]*[0-9][0-9.,]*""")
    private val GROUPED = Regex("""\d{1,3}(\x00\d{3})*""")

    /** El número de [text]; null si no lo es. */
    fun parse(text: String): Double? {
        val compact = text.replace(SPACES, "")
        val negative = compact.startsWith('-')
        val unsigned = if (compact.startsWith('-') || compact.startsWith('+')) compact.substring(1) else compact
        if (!DIGITS_AND_SEPARATORS.matches(unsigned)) return null
        val decimal = decimalSeparator(unsigned) ?: return null
        val (integer, fraction) = if (decimal == NONE) {
            unsigned to ""
        } else {
            unsigned.substringBeforeLast(decimal) to unsigned.substringAfterLast(decimal)
        }
        val grouping = when (decimal) {
            '.' -> ','
            ',' -> '.'
            else -> if (',' in unsigned) ',' else '.'
        }
        val digits = if (grouping in integer) {
            // Los grupos de miles son de tres cifras: "4.47.244" no es un número.
            if (!GROUPED.matches(integer.replace(grouping, '\u0000'))) return null
            integer.replace(grouping.toString(), "")
        } else {
            integer
        }
        if (fraction.any { !it.isDigit() } || digits.any { !it.isDigit() }) return null
        val value = BigDecimal((digits.ifEmpty { "0" }) + if (fraction.isEmpty()) "" else ".$fraction").toDouble()
        return if (negative) -value else value
    }

    /** El signo decimal, [NONE] si no hay, o null si los signos no tienen sentido. */
    private fun decimalSeparator(text: String): Char? {
        val dots = text.count { it == '.' }
        val commas = text.count { it == ',' }
        return when {
            dots > 0 && commas > 0 -> {
                val last = if (text.lastIndexOf('.') > text.lastIndexOf(',')) '.' else ','
                if ((if (last == '.') dots else commas) == 1) last else null
            }
            dots == 1 -> '.'
            commas == 1 -> ','
            else -> NONE // sin signos, o un mismo signo repetido: miles
        }
    }

    private const val NONE = '\u0000'
}

/** Si un ángulo es una latitud o una longitud: decide sus letras de hemisferio y su rango. */
enum class AngleAxis(val positive: Set<Char>, val negative: Set<Char>, val maxDeg: Double) {
    LATITUDE(setOf('N'), setOf('S'), 90.0),

    /** Oeste es W u O (oeste). */
    LONGITUDE(setOf('E'), setOf('W', 'O'), 180.0),
}

/**
 * Latitud o longitud escrita en la unidad angular del proyecto (S011/RF-17): grados decimales, grados, minutos y
 * segundos ("40 25 7,24", "40°25'7.24\" N", lo que escribe `AngleFormat`) o gon. Un signo menos, o la letra S, W
 * u O, al principio o al final, dan un valor negativo.
 */
object AngleInput {
    private const val DEGREES_PER_GON = 0.9
    private const val MINUTES_PER_DEGREE = 60.0
    private val DMS_SYMBOLS = Regex("""[°º˚′'’″"]""")
    private val SPACES = Regex("""[\s  ]+""")
    private val GON_SUFFIX = Regex("""\s*(gon|g)$""", RegexOption.IGNORE_CASE)
    private val NUMBER = Regex("""\d+([.,]\d+)?|[.,]\d+""")
    private val INTEGER = Regex("""\d+""")

    /** El ángulo de [text] en grados; null si no es válido o se sale de ±90° o ±180°. */
    fun parse(text: String, unit: AngularUnit, axis: AngleAxis): Double? {
        var body = text.trim()
        if (unit == AngularUnit.GON) body = body.replace(GON_SUFFIX, "")
        if (unit == AngularUnit.DECIMAL_DEGREES) body = body.removeSuffix("°").removeSuffix("º").trim()
        // "º" es una letra para Unicode: los símbolos de grados, minutos y segundos se quitan antes de buscar el hemisferio.
        if (unit == AngularUnit.DMS) body = body.replace(DMS_SYMBOLS, " ").trim()
        val (letterSign, withoutLetter) = hemisphere(body, axis) ?: return null
        var rest = withoutLetter.trim()
        val minus = rest.startsWith('-')
        if (minus || rest.startsWith('+')) rest = rest.substring(1).trim()
        if (minus && letterSign < 0) return null // "-40 S": dos signos
        val sign = if (minus || letterSign < 0) -1.0 else 1.0
        val magnitude = when (unit) {
            AngularUnit.DMS -> sexagesimal(rest)
            AngularUnit.DECIMAL_DEGREES -> number(rest)
            AngularUnit.GON -> number(rest)?.times(DEGREES_PER_GON)
        } ?: return null
        return (sign * magnitude).takeIf { magnitude <= axis.maxDeg }
    }

    /** El signo de la letra de hemisferio (0 si no hay) y el texto sin ella; null si sobra alguna letra. */
    private fun hemisphere(text: String, axis: AngleAxis): Pair<Int, String>? {
        val letters = axis.positive + axis.negative
        var sign = 0
        var rest = text
        val first = rest.firstOrNull()?.uppercaseChar()
        if (first != null && first in letters) {
            sign = if (first in axis.negative) -1 else 1
            rest = rest.substring(1)
        }
        val last = rest.trimEnd().lastOrNull()?.uppercaseChar()
        if (last != null && last in letters) {
            if (sign != 0) return null // una letra al principio y otra al final
            sign = if (last in axis.negative) -1 else 1
            rest = rest.trimEnd().dropLast(1)
        }
        return if (rest.any { it.isLetter() }) null else sign to rest
    }

    /** Grados decimales o gon: un solo número. */
    private fun number(text: String): Double? = text.trim().takeIf { NUMBER.matches(it) }?.replace(',', '.')?.toDouble()

    /** "D", "D M" o "D M S", con decimales solo en el último; minutos y segundos por debajo de 60. */
    private fun sexagesimal(text: String): Double? {
        val parts = text.trim().split(SPACES).filter { it.isNotEmpty() }
        if (parts.isEmpty() || parts.size > 3) return null
        if (parts.dropLast(1).any { !INTEGER.matches(it) } || !NUMBER.matches(parts.last())) return null
        val values = parts.map { it.replace(',', '.').toDouble() }
        if (values.drop(1).any { it >= MINUTES_PER_DEGREE }) return null
        return values.foldIndexed(0.0) { index, total, value -> total + value / Math.pow(MINUTES_PER_DEGREE, index.toDouble()) }
    }
}
