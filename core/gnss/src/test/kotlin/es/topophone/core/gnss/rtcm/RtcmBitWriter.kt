package es.topophone.core.gnss.rtcm

import java.io.ByteArrayOutputStream

/** Escribe campos de bits de más a menos significativo, para montar cargas RTCM sintéticas en los tests. */
class RtcmBitWriter {
    private val bits = ArrayList<Boolean>()

    fun unsigned(value: Long, width: Int): RtcmBitWriter {
        for (i in width - 1 downTo 0) bits += (value ushr i) and 1L == 1L
        return this
    }

    fun unsigned(value: Int, width: Int): RtcmBitWriter = unsigned(value.toLong(), width)

    /** En complemento a dos. */
    fun signed(value: Long, width: Int): RtcmBitWriter = unsigned(value and if (width == 64) -1L else (1L shl width) - 1, width)

    val size: Int get() = bits.size

    /** Los bits, completados con ceros hasta el byte. */
    fun bytes(): ByteArray {
        val out = ByteArrayOutputStream()
        for (start in bits.indices step 8) {
            var byte = 0
            for (i in 0 until 8) byte = (byte shl 1) or if (start + i < bits.size && bits[start + i]) 1 else 0
            out.write(byte)
        }
        return out.toByteArray()
    }
}
