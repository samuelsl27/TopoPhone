package es.topophone.core.gnss.signal

import es.topophone.core.gnss.nmea.Constellation
import es.topophone.core.gnss.rtcm.RtcmSystem
import java.util.Locale

/** Un satélite por su constelación y su número propio en ella (el de la máscara de los MSM). */
data class SatelliteId(val constellation: Constellation, val number: Int) {
    /** Letra de la constelación y número: "G05", "R06" (S015/RF-5). */
    val label: String get() = String.format(Locale.ROOT, "%c%02d", SatelliteNumber.letter(constellation), number)
}

/**
 * Las GSA y las GSV numeran los satélites como el receptor, y los MSM por su máscara. Todos se pasan al número propio
 * de su constelación para unirlos (S015/RF-6, RF-8).
 */
object SatelliteNumber {

    /** `null` si el número no corresponde a la constelación (por ejemplo, SBAS en una GSV de GPS). */
    fun native(constellation: Constellation, prn: Int): Int? = when (constellation) {
        Constellation.GPS -> prn.takeIf { it in 1..32 }
        Constellation.GLONASS -> when (prn) {
            in 65..96 -> prn - 64
            in 1..32 -> prn
            else -> null
        }
        Constellation.GALILEO -> when (prn) {
            in 1..36 -> prn
            in 301..336 -> prn - 300
            else -> null
        }
        Constellation.BEIDOU -> when (prn) {
            in 1..63 -> prn
            in 201..263 -> prn - 200
            in 401..463 -> prn - 400
            else -> null
        }
        Constellation.QZSS -> when (prn) {
            in 1..10 -> prn
            in 193..202 -> prn - 192
            else -> null
        }
        Constellation.NAVIC -> prn.takeIf { it in 1..14 }
        Constellation.MULTIPLE, Constellation.UNKNOWN -> null
    }

    /** La constelación de un sistema RTCM; SBAS no tiene (sus MSM se ignoran). */
    fun constellation(system: RtcmSystem): Constellation? = when (system) {
        RtcmSystem.GPS -> Constellation.GPS
        RtcmSystem.GLONASS -> Constellation.GLONASS
        RtcmSystem.GALILEO -> Constellation.GALILEO
        RtcmSystem.BEIDOU -> Constellation.BEIDOU
        RtcmSystem.QZSS -> Constellation.QZSS
        RtcmSystem.NAVIC -> Constellation.NAVIC
        RtcmSystem.SBAS -> null
    }

    /** Letra de la constelación, como en RINEX (S015/RF-5). */
    fun letter(constellation: Constellation): Char = when (constellation) {
        Constellation.GPS -> 'G'
        Constellation.GLONASS -> 'R'
        Constellation.GALILEO -> 'E'
        Constellation.BEIDOU -> 'C'
        Constellation.QZSS -> 'J'
        Constellation.NAVIC -> 'I'
        Constellation.MULTIPLE, Constellation.UNKNOWN -> '?'
    }
}
