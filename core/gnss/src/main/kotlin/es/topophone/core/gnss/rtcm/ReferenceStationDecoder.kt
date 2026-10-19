package es.topophone.core.gnss.rtcm

/**
 * Estación de referencia de un mensaje 1005/1006: su identificador, el punto de referencia de la antena (ARP) en
 * geocéntricas, en metros, y, en el 1006, la altura de antena sobre la marca (S015/RF-12, RF-13).
 */
data class ReferenceStation(val stationId: Int, val x: Double, val y: Double, val z: Double, val antennaHeightM: Double?)

/** Lee los mensajes 1005 y 1006 (RTCM 10403.3, DF002 a DF028). */
object ReferenceStationDecoder {
    private const val BITS_1005 = 152
    private const val BITS_1006 = 168
    private const val TYPE_BITS = 12
    private const val COORDINATE_BITS = 38
    private const val RESOLUTION_M = 1e-4

    /** `null` si los datos no son un 1005 o un 1006 completo. */
    fun decode(payload: ByteArray): ReferenceStation? {
        val bits = RtcmBits(payload)
        if (bits.remaining < TYPE_BITS) return null
        val type = bits.unsigned(TYPE_BITS).toInt()
        val needed = when (type) {
            1005 -> BITS_1005
            1006 -> BITS_1006
            else -> return null
        }
        if (payload.size * 8 < needed) return null
        val station = bits.unsigned(12).toInt()
        bits.skip(6 + 4) // año ITRF e indicadores de sistemas y de estación
        val x = bits.signed(COORDINATE_BITS) * RESOLUTION_M
        bits.skip(2) // oscilador único y reservado
        val y = bits.signed(COORDINATE_BITS) * RESOLUTION_M
        bits.skip(2) // indicador de cuarto de ciclo
        val z = bits.signed(COORDINATE_BITS) * RESOLUTION_M
        val antennaHeight = if (type == 1006) bits.unsigned(16) * RESOLUTION_M else null
        return ReferenceStation(station, x, y, z, antennaHeight)
    }
}
