package es.topophone.core.gnss.rtcm

/** Sistemas GNSS que distinguen los mensajes RTCM 3 (los MSM incluyen SBAS). */
enum class RtcmSystem { GPS, GLONASS, GALILEO, SBAS, QZSS, BEIDOU, NAVIC }

/** Qué contiene un tipo de mensaje RTCM 3, para nombrarlo en la interfaz (S007/RF-32). */
sealed interface RtcmKind {
    /** Observables MSM1 a MSM7 de un sistema. */
    data class Msm(val system: RtcmSystem, val level: Int) : RtcmKind

    /** Observables anteriores a los MSM (1001–1004 GPS, 1009–1012 GLONASS). */
    data class LegacyObservables(val system: RtcmSystem) : RtcmKind

    /** Posición de la estación de referencia (1005, 1006). */
    data object ReferenceStation : RtcmKind

    /** Descriptor de la antena (1007, 1008). */
    data object AntennaDescriptor : RtcmKind

    /** Descriptor del receptor y de la antena (1033). */
    data object ReceiverAntennaDescriptor : RtcmKind

    data class Ephemeris(val system: RtcmSystem) : RtcmKind

    /** Sesgos de código y fase de GLONASS (1230). */
    data object GlonassBiases : RtcmKind

    data object Unknown : RtcmKind
}

object RtcmMessages {
    private const val FIRST_MSM = 1071
    private const val LAST_MSM = 1137
    private const val MAX_MSM_LEVEL = 7

    /** Sistemas de los MSM, de 10 en 10 desde 1071. */
    private val msmSystems = listOf(
        RtcmSystem.GPS,
        RtcmSystem.GLONASS,
        RtcmSystem.GALILEO,
        RtcmSystem.SBAS,
        RtcmSystem.QZSS,
        RtcmSystem.BEIDOU,
        RtcmSystem.NAVIC,
    )

    fun kind(type: Int): RtcmKind = when (type) {
        in 1001..1004 -> RtcmKind.LegacyObservables(RtcmSystem.GPS)
        in 1009..1012 -> RtcmKind.LegacyObservables(RtcmSystem.GLONASS)
        1005, 1006 -> RtcmKind.ReferenceStation
        1007, 1008 -> RtcmKind.AntennaDescriptor
        1033 -> RtcmKind.ReceiverAntennaDescriptor
        1019 -> RtcmKind.Ephemeris(RtcmSystem.GPS)
        1020 -> RtcmKind.Ephemeris(RtcmSystem.GLONASS)
        1042 -> RtcmKind.Ephemeris(RtcmSystem.BEIDOU)
        1044 -> RtcmKind.Ephemeris(RtcmSystem.QZSS)
        1045, 1046 -> RtcmKind.Ephemeris(RtcmSystem.GALILEO)
        1230 -> RtcmKind.GlonassBiases
        in FIRST_MSM..LAST_MSM -> msm(type)
        else -> RtcmKind.Unknown
    }

    private fun msm(type: Int): RtcmKind {
        val level = (type - FIRST_MSM) % 10 + 1
        return if (level <= MAX_MSM_LEVEL) RtcmKind.Msm(msmSystems[(type - FIRST_MSM) / 10], level) else RtcmKind.Unknown
    }
}
