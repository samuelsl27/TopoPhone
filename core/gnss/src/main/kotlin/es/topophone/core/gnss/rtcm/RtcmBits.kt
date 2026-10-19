package es.topophone.core.gnss.rtcm

/**
 * Lee los campos de bits de los datos de una trama RTCM 3, de más a menos significativo (S015/RF-8, RF-12). Los
 * decodificadores comprueban antes que hay bits suficientes; leer más allá del final es un error de programa.
 */
class RtcmBits(private val data: ByteArray) {
    private var position = 0

    /** Bits que quedan por leer. */
    val remaining: Int get() = data.size * 8 - position

    /** Entero sin signo de [bits] bits (hasta 64; con 64, los bits tal cual). */
    fun unsigned(bits: Int): Long {
        require(bits in 0..64) { "Ancho no válido: $bits" }
        check(bits <= remaining) { "Faltan bits: se piden $bits y quedan $remaining" }
        var value = 0L
        repeat(bits) {
            val bit = (data[position ushr 3].toInt() ushr (7 - (position and 7))) and 1
            value = (value shl 1) or bit.toLong()
            position++
        }
        return value
    }

    /** Entero con signo de [bits] bits en complemento a dos. */
    fun signed(bits: Int): Long {
        val value = unsigned(bits)
        return if (bits in 1..63 && (value ushr (bits - 1)) and 1L == 1L) value - (1L shl bits) else value
    }

    fun skip(bits: Int) {
        check(bits in 0..remaining) { "Faltan bits: se saltan $bits y quedan $remaining" }
        position += bits
    }
}
