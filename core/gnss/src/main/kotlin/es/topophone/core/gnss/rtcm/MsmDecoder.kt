package es.topophone.core.gnss.rtcm

/** CNR de una celda de un MSM: [satellite] y [signal] son sus posiciones en las máscaras, desde 1. */
data class MsmCell(val satellite: Int, val signal: Int, val cnrDbHz: Double)

/** La SNR de la base que trae un MSM de un sistema (S015/RF-8). */
data class MsmCnr(val system: RtcmSystem, val stationId: Int, val cells: List<MsmCell>)

/**
 * Lee la CNR de los mensajes MSM 4 a 7 (RTCM 10403.3, DF393 a DF420): 6 bits de 1 dB-Hz en MSM4 y MSM5, y 10 bits de
 * 1/16 dB-Hz en MSM6 y MSM7. Los MSM 1 a 3 no la traen (S015/RF-10).
 */
object MsmDecoder {
    private const val HEADER_BITS = 12 + 12 + 30 + 1 + 3 + 7 + 2 + 2 + 1 + 3 + 64 + 32
    private const val MAX_CELLS = 64
    private const val FINE_RESOLUTION = 0.0625

    /** `null` si no es un MSM 4 a 7 completo. */
    fun decode(payload: ByteArray): MsmCnr? {
        val bits = RtcmBits(payload)
        if (bits.remaining < HEADER_BITS) return null
        val kind = RtcmMessages.kind(bits.unsigned(12).toInt()) as? RtcmKind.Msm ?: return null
        if (kind.level < 4) return null
        val station = bits.unsigned(12).toInt()
        bits.skip(30 + 1 + 3 + 7 + 2 + 2 + 1 + 3) // hora, varios mensajes, IODS, reloj y suavizado
        val satMask = bits.unsigned(64)
        val sigMask = bits.unsigned(32)
        val sats = (1..64).filter { (satMask ushr (64 - it)) and 1L == 1L }
        val sigs = (1..32).filter { (sigMask ushr (32 - it)) and 1L == 1L }
        val cellMaskBits = sats.size * sigs.size
        if (cellMaskBits > MAX_CELLS || bits.remaining < cellMaskBits) return null
        val cells = ArrayList<Pair<Int, Int>>(cellMaskBits)
        for (s in sats) for (g in sigs) if (bits.unsigned(1) == 1L) cells += s to g
        val coarse = kind.level == 4 || kind.level == 5
        val satelliteBits = if (kind.level == 4 || kind.level == 6) 18 * sats.size else 36 * sats.size
        val beforeCnrBits = (if (coarse) 42 else 55) * cells.size
        val cnrBits = if (coarse) 6 else 10
        val rateBits = if (kind.level == 5 || kind.level == 7) 15 * cells.size else 0
        // Un mensaje más corto de lo que dicen sus máscaras se descarta entero (plan, A2).
        if (bits.remaining < satelliteBits + beforeCnrBits + cnrBits * cells.size + rateBits) return null
        bits.skip(satelliteBits) // distancias aproximadas (y velocidades en MSM5 y MSM7)
        bits.skip(beforeCnrBits) // pseudodistancias, fases, tiempo de enganche y medio ciclo
        val resolution = if (coarse) 1.0 else FINE_RESOLUTION
        val values = cells.map { bits.unsigned(cnrBits) }
        return MsmCnr(
            kind.system,
            station,
            cells.zip(values).filter { it.second != 0L }.map { (cell, raw) -> MsmCell(cell.first, cell.second, raw * resolution) },
        )
    }
}
