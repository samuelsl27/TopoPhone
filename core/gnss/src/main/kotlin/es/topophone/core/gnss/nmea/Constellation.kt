package es.topophone.core.gnss.nmea

/** Constelación de una frase, por su identificador (talker) o por el *system ID* de NMEA 4.11 (S003/RF-1). */
enum class Constellation {
    GPS,
    GLONASS,
    GALILEO,
    BEIDOU,
    QZSS,
    NAVIC,

    /** `GN`: frase que combina varias constelaciones. */
    MULTIPLE,
    UNKNOWN,
    ;

    companion object {
        fun fromTalker(talker: String): Constellation = when (talker) {
            "GP" -> GPS
            "GL" -> GLONASS
            "GA" -> GALILEO
            "GB", "BD" -> BEIDOU
            "GQ", "QZ" -> QZSS
            "GI" -> NAVIC
            "GN" -> MULTIPLE
            else -> UNKNOWN
        }

        fun fromSystemId(systemId: Int): Constellation = when (systemId) {
            1 -> GPS
            2 -> GLONASS
            3 -> GALILEO
            4 -> BEIDOU
            5 -> QZSS
            6 -> NAVIC
            else -> UNKNOWN
        }
    }
}
