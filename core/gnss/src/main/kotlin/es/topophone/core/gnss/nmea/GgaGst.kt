package es.topophone.core.gnss.nmea

import kotlin.math.sqrt

/** Estado de la solución GNSS según el indicador de calidad de la GGA (S002/RF-12). */
enum class SolutionStatus {
    NONE,
    SINGLE,
    DGPS,
    FLOAT,
    FIX,
    ESTIMATED,
    ;

    companion object {
        fun fromGgaQuality(quality: Int): SolutionStatus = when (quality) {
            1 -> SINGLE
            2 -> DGPS
            4 -> FIX
            5 -> FLOAT
            6 -> ESTIMATED
            else -> NONE
        }
    }
}

/**
 * Posición de una frase GGA. Latitud y longitud en grados (negativas al sur y al oeste);
 * la altitud es sobre el geoide del receptor y la separación es la ondulación que usa (ADR-6).
 */
data class GgaFix(
    val utcTime: String,
    val latitudeDeg: Double,
    val longitudeDeg: Double,
    val status: SolutionStatus,
    val satellites: Int,
    val hdop: Double?,
    val altitudeM: Double?,
    val geoidSeparationM: Double?,
    val correctionAgeS: Double?,
    val rawSentence: String,
) {
    val hasPosition: Boolean get() = status != SolutionStatus.NONE && !latitudeDeg.isNaN() && !longitudeDeg.isNaN()
}

/** Precisión estimada (1σ) de una frase GST, en metros. */
data class GstAccuracy(val utcTime: String, val sigmaLatitudeM: Double?, val sigmaLongitudeM: Double?, val sigmaAltitudeM: Double?) {

    /** √(σlat² + σlon²) (S002/RF-14). */
    val horizontalM: Double?
        get() = if (sigmaLatitudeM != null && sigmaLongitudeM != null) {
            sqrt(sigmaLatitudeM * sigmaLatitudeM + sigmaLongitudeM * sigmaLongitudeM)
        } else {
            null
        }

    /** σ de la altura (S002/RF-15). */
    val verticalM: Double? get() = sigmaAltitudeM
}

object GgaParser {
    /** `null` si la frase no es GGA o tiene campos corruptos. */
    fun parse(sentence: NmeaSentence): GgaFix? {
        if (sentence.type != "GGA" || sentence.fields.size < 14) return null
        val f = sentence.fields
        return try {
            val quality = f[5].optionalInt() ?: 0
            GgaFix(
                utcTime = f[0],
                latitudeDeg = coordinate(f[1], f[2], degreeDigits = 2),
                longitudeDeg = coordinate(f[3], f[4], degreeDigits = 3),
                status = SolutionStatus.fromGgaQuality(quality),
                satellites = f[6].optionalInt() ?: 0,
                hdop = f[7].optionalDouble(),
                altitudeM = f[8].optionalDouble(),
                geoidSeparationM = f[10].optionalDouble(),
                correctionAgeS = f[12].optionalDouble(),
                rawSentence = sentence.raw,
            )
        } catch (_: CorruptField) {
            null
        }
    }
}

object GstParser {
    /** `null` si la frase no es GST o tiene campos corruptos. */
    fun parse(sentence: NmeaSentence): GstAccuracy? {
        if (sentence.type != "GST" || sentence.fields.size < 8) return null
        val f = sentence.fields
        return try {
            GstAccuracy(
                utcTime = f[0],
                sigmaLatitudeM = f[5].optionalDouble(),
                sigmaLongitudeM = f[6].optionalDouble(),
                sigmaAltitudeM = f[7].optionalDouble(),
            )
        } catch (_: CorruptField) {
            null
        }
    }
}
