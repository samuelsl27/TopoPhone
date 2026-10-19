package es.topophone.core.gnss.signal

import es.topophone.core.gnss.nmea.Constellation
import es.topophone.core.gnss.rtcm.RtcmSystem

/**
 * Banda de frecuencia de una señal: agrupa las barras de SNR y une la del rover con la de la base (S015/RF-7, RF-8).
 * [label] es el nombre técnico, igual en todos los idiomas.
 */
enum class Band(val label: String) {
    L1("L1"),
    L2("L2"),
    L5("L5"),
    G1("G1"),
    G2("G2"),
    E1("E1"),
    E5A("E5a"),
    E5B("E5b"),
    E5("E5"),
    E6("E6"),
    B1("B1"),
    B1C("B1C"),
    B2("B2"),
    B2A("B2a"),
    B2B("B2b"),
    B3("B3"),
    ;

    companion object {
        /** Por el identificador de señal de la GSV (NMEA 4.11); `null` sin identificador o si no está en la tabla. */
        fun fromNmea(constellation: Constellation, signalId: Int?): Band? = when (constellation) {
            Constellation.GPS -> when (signalId) {
                in 1..3 -> L1
                in 4..6 -> L2
                7, 8 -> L5
                else -> null
            }
            Constellation.GLONASS -> when (signalId) {
                1, 2 -> G1
                3, 4 -> G2
                else -> null
            }
            Constellation.GALILEO -> when (signalId) {
                1 -> E5A
                2 -> E5B
                3 -> E5
                4, 5 -> E6
                6, 7 -> E1
                else -> null
            }
            Constellation.BEIDOU -> when (signalId) {
                1, 2 -> B1
                3, 4 -> B1C
                5 -> B2A
                6 -> B2B
                in 8..10 -> B3
                11, 12 -> B2
                else -> null
            }
            Constellation.QZSS -> when (signalId) {
                in 1..4 -> L1
                5, 6 -> L2
                7, 8 -> L5
                else -> null
            }
            Constellation.NAVIC -> if (signalId == 1) L5 else null
            Constellation.MULTIPLE, Constellation.UNKNOWN -> null
        }

        /** Por la posición de la señal en la máscara del MSM (RTCM 10403.3); `null` si no está en la tabla. */
        fun fromRtcm(system: RtcmSystem, signal: Int): Band? = when (system) {
            RtcmSystem.GPS -> when (signal) {
                in 2..4, in 30..32 -> L1
                in 8..10, in 15..17 -> L2
                in 22..24 -> L5
                else -> null
            }
            RtcmSystem.GLONASS -> when (signal) {
                2, 3 -> G1
                8, 9 -> G2
                else -> null
            }
            RtcmSystem.GALILEO -> when (signal) {
                in 2..6 -> E1
                in 8..12 -> E6
                in 14..16 -> E5B
                in 18..20 -> E5
                in 22..24 -> E5A
                else -> null
            }
            RtcmSystem.BEIDOU -> when (signal) {
                in 2..4 -> B1
                in 8..10 -> B3
                in 14..16 -> B2
                in 22..24 -> B2A
                25 -> B2B
                in 30..32 -> B1C
                else -> null
            }
            RtcmSystem.QZSS -> when (signal) {
                2, in 30..32 -> L1
                in 15..17 -> L2
                in 22..24 -> L5
                else -> null
            }
            RtcmSystem.NAVIC -> if (signal == 22) L5 else null
            RtcmSystem.SBAS -> null
        }
    }
}
