package es.topophone.core.gnss.nmea

import java.time.LocalDate

/** Frase RMC: hora y fecha UTC, validez, velocidad (m/s) y rumbo (rad) sobre el suelo (S003/RF-3, S003/RF-4). */
data class RmcData(val utcMillisOfDay: Int?, val date: LocalDate?, val valid: Boolean, val speedMps: Double?, val courseRad: Double?)

/** Frase VTG: rumbo (rad) y velocidad (m/s) sobre el suelo (S003/RF-4). */
data class VtgData(val courseRad: Double?, val speedMps: Double?)

/** Frase ZDA: fecha y hora UTC (S003/RF-3). */
data class ZdaData(val utcMillisOfDay: Int, val date: LocalDate)

private const val METRES_PER_SECOND_PER_KNOT = 1852.0 / 3600.0
private const val KMH_PER_METRE_PER_SECOND = 3.6
private const val CENTURY = 2000

object RmcParser {
    /** `null` si la frase no es RMC o tiene un campo que no se puede interpretar. */
    fun parse(sentence: NmeaSentence): RmcData? {
        if (sentence.type != "RMC" || sentence.fields.size < 9) return null
        val f = sentence.fields
        return parseFields {
            RmcData(
                utcMillisOfDay = f[0].optionalTime(),
                date = rmcDate(f[8]),
                valid = f[1] == "A",
                speedMps = f[6].optionalDouble()?.times(METRES_PER_SECOND_PER_KNOT),
                courseRad = f[7].optionalRadians(),
            )
        }
    }

    /** `ddmmyy`; los años de dos cifras se cuentan desde el 2000. */
    private fun rmcDate(text: String): LocalDate? {
        if (text.isEmpty()) return null
        if (text.length != 6) throw CorruptField()
        val day = text.substring(0, 2).optionalInt() ?: throw CorruptField()
        val month = text.substring(2, 4).optionalInt() ?: throw CorruptField()
        val year = text.substring(4, 6).optionalInt() ?: throw CorruptField()
        return date(CENTURY + year, month, day)
    }
}

object VtgParser {
    /** `null` si la frase no es VTG o tiene un campo que no se puede interpretar. */
    fun parse(sentence: NmeaSentence): VtgData? {
        if (sentence.type != "VTG" || sentence.fields.size < 8) return null
        val f = sentence.fields
        return parseFields {
            val kmh = f[6].optionalDouble()
            VtgData(
                courseRad = f[0].optionalRadians(),
                speedMps = kmh?.div(KMH_PER_METRE_PER_SECOND) ?: f[4].optionalDouble()?.times(METRES_PER_SECOND_PER_KNOT),
            )
        }
    }
}

object ZdaParser {
    /** `null` si la frase no es ZDA, si aún no trae fecha y hora o si alguna es imposible. */
    fun parse(sentence: NmeaSentence): ZdaData? {
        if (sentence.type != "ZDA" || sentence.fields.size < 4) return null
        val f = sentence.fields
        return parseFields {
            val time = f[0].optionalTime() ?: return@parseFields null
            val day = f[1].optionalInt() ?: return@parseFields null
            val month = f[2].optionalInt() ?: return@parseFields null
            val year = f[3].optionalInt() ?: return@parseFields null
            ZdaData(time, date(year, month, day))
        }
    }
}
