package es.topophone.core.gnss.nmea

/** Modo de posición de la frase GSA. */
enum class FixMode { NONE, FIX_2D, FIX_3D }

/** Frase GSA: modo, satélites usados de una constelación y DOP (S003/RF-1). */
data class GsaData(
    val mode: FixMode,
    val constellation: Constellation,
    val satellitesUsed: List<Int>,
    val pdop: Double?,
    val hdop: Double?,
    val vdop: Double?,
)

/** Satélite en vista: elevación y azimut en radianes, relación señal/ruido en dB-Hz. */
data class SatelliteView(val prn: Int, val elevationRad: Double?, val azimuthRad: Double?, val snrDbHz: Int?)

/**
 * Una frase GSV de un grupo de [totalMessages]: satélites en vista de una constelación y, desde
 * NMEA 4.10, de una señal (S003/RF-2).
 */
data class GsvData(
    val constellation: Constellation,
    val signalId: Int?,
    val totalMessages: Int,
    val messageNumber: Int,
    val satellitesInView: Int,
    val satellites: List<SatelliteView>,
)

object GsaParser {
    private const val SATELLITE_FIELDS = 12
    private const val FIRST_SATELLITE = 2
    private const val PDOP = FIRST_SATELLITE + SATELLITE_FIELDS
    private const val SYSTEM_ID = PDOP + 3

    /** `null` si la frase no es GSA o tiene un campo que no se puede interpretar. */
    fun parse(sentence: NmeaSentence): GsaData? {
        if (sentence.type != "GSA" || sentence.fields.size < SYSTEM_ID) return null
        val f = sentence.fields
        return parseFields {
            val mode = when (f[1]) {
                "", "1" -> FixMode.NONE
                "2" -> FixMode.FIX_2D
                "3" -> FixMode.FIX_3D
                else -> throw CorruptField()
            }
            val systemId = f.getOrNull(SYSTEM_ID)?.optionalInt()
            GsaData(
                mode = mode,
                constellation = systemId?.let(Constellation::fromSystemId) ?: Constellation.fromTalker(sentence.talker),
                satellitesUsed = f.subList(FIRST_SATELLITE, PDOP).mapNotNull { it.optionalInt() },
                pdop = f[PDOP].optionalDouble(),
                hdop = f[PDOP + 1].optionalDouble(),
                vdop = f[PDOP + 2].optionalDouble(),
            )
        }
    }
}

object GsvParser {
    private const val HEADER = 3
    private const val GROUP = 4
    private const val HEX = 16

    /** `null` si la frase no es GSV o tiene un campo que no se puede interpretar. */
    fun parse(sentence: NmeaSentence): GsvData? {
        val f = sentence.fields
        if (sentence.type != "GSV" || f.size < HEADER) return null
        val rest = f.size - HEADER
        // Desde NMEA 4.10 un campo final con el identificador de señal (hexadecimal).
        val hasSignal = rest % GROUP == 1
        if (!hasSignal && rest % GROUP != 0) return null
        return parseFields {
            val total = f[0].optionalInt() ?: throw CorruptField()
            val number = f[1].optionalInt() ?: throw CorruptField()
            if (total < 1 || number !in 1..total) throw CorruptField()
            val groups = f.subList(HEADER, f.size - if (hasSignal) 1 else 0).chunked(GROUP)
            GsvData(
                constellation = Constellation.fromTalker(sentence.talker),
                signalId = if (hasSignal) signalId(f.last()) else null,
                totalMessages = total,
                messageNumber = number,
                satellitesInView = f[2].optionalInt() ?: 0,
                satellites = groups.mapNotNull { group ->
                    val prn = group[0].optionalInt() ?: return@mapNotNull null
                    SatelliteView(prn, group[1].optionalRadians(), group[2].optionalRadians(), group[3].optionalInt())
                },
            )
        }
    }

    private fun signalId(text: String): Int? = if (text.isEmpty()) null else text.toIntOrNull(HEX) ?: throw CorruptField()
}
