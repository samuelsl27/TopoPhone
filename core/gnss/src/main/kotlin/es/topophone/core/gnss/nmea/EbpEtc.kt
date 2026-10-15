package es.topophone.core.gnss.nmea

/** Posición de la base que da la frase EBP de Emlid, en grados y metros sobre el elipsoide (S003/RF-5). */
data class BasePosition(val latitudeDeg: Double, val longitudeDeg: Double, val ellipsoidalHeightM: Double)

/** Estado de la inclinación según ETC_rev_3 (vacío = apagada). */
enum class TiltState { OFF, FATAL, SETUP, ALIGNMENT, COMPENSATING }

/** Aviso de la inclinación según ETC_rev_3 (vacío = apagada). */
enum class TiltNotification { OFF, NONE, FAST_MOTION, BAD_GNSS, FILTER_FAULT }

/** Coeficientes de precisión de la inclinación por eje (ETC_rev_3). */
data class TiltAccuracyCoefficients(val north: Double, val east: Double, val up: Double)

/** Frase ETC: estado, aviso, rumbo, dirección e inclinación (rad) y coeficientes de precisión (S003/RF-6). */
data class TiltData(
    val utcMillisOfDay: Int?,
    val state: TiltState,
    val notification: TiltNotification,
    val headingRad: Double?,
    val tiltDirectionRad: Double?,
    val tiltRad: Double?,
    val accuracyCoefficients: TiltAccuracyCoefficients?,
)

object EbpParser {
    /** `null` si la frase no es EBP, si aún no hay base o si algún campo es imposible. */
    fun parse(sentence: NmeaSentence): BasePosition? {
        if (sentence.type != "EBP" || sentence.fields.size < 5) return null
        val f = sentence.fields
        return parseFields {
            if (f[0].isEmpty() || f[2].isEmpty()) return@parseFields null
            val height = f[4].optionalDouble() ?: return@parseFields null
            BasePosition(coordinate(f[0], f[1], degreeDigits = 2), coordinate(f[2], f[3], degreeDigits = 3), height)
        }
    }
}

object EtcParser {
    private const val FULL_CIRCLE_DEG = 360.0
    private const val HALF_CIRCLE_DEG = 180.0

    /** `null` si la frase no es ETC o tiene un campo que no se puede interpretar o fuera de rango. */
    fun parse(sentence: NmeaSentence): TiltData? {
        if (sentence.type != "ETC" || sentence.fields.size < 9) return null
        val f = sentence.fields
        return parseFields {
            val north = f[6].optionalDouble()
            val east = f[7].optionalDouble()
            val up = f[8].optionalDouble()
            TiltData(
                utcMillisOfDay = f[0].optionalTime(),
                state = when (f[1]) {
                    "" -> TiltState.OFF
                    "0" -> TiltState.FATAL
                    "10" -> TiltState.SETUP
                    "20" -> TiltState.ALIGNMENT
                    "30" -> TiltState.COMPENSATING
                    else -> throw CorruptField()
                },
                notification = when (f[2]) {
                    "" -> TiltNotification.OFF
                    "0" -> TiltNotification.NONE
                    "10" -> TiltNotification.FAST_MOTION
                    "20" -> TiltNotification.BAD_GNSS
                    "30" -> TiltNotification.FILTER_FAULT
                    else -> throw CorruptField()
                },
                headingRad = f[3].angle(FULL_CIRCLE_DEG),
                tiltDirectionRad = f[4].angle(FULL_CIRCLE_DEG),
                tiltRad = f[5].angle(HALF_CIRCLE_DEG),
                accuracyCoefficients = if (north != null && east != null && up != null) TiltAccuracyCoefficients(north, east, up) else null,
            )
        }
    }

    /** Grados en `[0, max]` → radianes; vacío → `null`; fuera de rango → [CorruptField]. */
    private fun String.angle(maxDeg: Double): Double? {
        val degrees = optionalDouble() ?: return null
        if (degrees !in 0.0..maxDeg) throw CorruptField()
        return Math.toRadians(degrees)
    }
}
