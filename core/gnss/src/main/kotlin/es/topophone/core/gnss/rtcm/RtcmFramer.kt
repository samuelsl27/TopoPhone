package es.topophone.core.gnss.rtcm

/** CRC-24Q de RTCM 3 (polinomio 0x1864CFB, sin reflejar, valor inicial 0). */
object Crc24q {
    private const val POLYNOMIAL = 0x1864CFB
    private const val MASK = 0xFFFFFF

    private val table = IntArray(256) { byte ->
        var crc = byte shl 16
        repeat(8) {
            crc = crc shl 1
            if (crc and 0x1000000 != 0) crc = crc xor POLYNOMIAL
        }
        crc and MASK
    }

    fun compute(bytes: ByteArray, offset: Int, length: Int): Int {
        var crc = 0
        for (i in offset until offset + length) {
            crc = ((crc shl 8) xor table[((crc shr 16) xor bytes[i].toInt()) and 0xFF]) and MASK
        }
        return crc
    }
}

/** Lo que encuentra [RtcmFramer] en el flujo de correcciones. */
sealed interface RtcmEvent {
    /** Trama con el CRC correcto; [type] son los 12 primeros bits de sus datos. */
    data class Message(val type: Int) : RtcmEvent

    /** Trama dañada o perdida: CRC erróneo o inicio de trama donde no tocaba (S007/RF-33). */
    data object CrcError : RtcmEvent
}

/** Cuántos mensajes RTCM 3 de cada tipo han llegado y cuántos con el CRC erróneo (S007/RF-32, RF-33). */
data class RtcmStats(val counts: Map<Int, Long> = emptyMap(), val crcErrors: Long = 0) {
    operator fun plus(events: List<RtcmEvent>): RtcmStats {
        if (events.isEmpty()) return this
        val counts = counts.toMutableMap()
        var errors = crcErrors
        for (event in events) {
            when (event) {
                is RtcmEvent.Message -> counts.merge(event.type, 1L, Long::plus)
                RtcmEvent.CrcError -> errors++
            }
        }
        return RtcmStats(counts, errors)
    }
}

/**
 * Separa las tramas RTCM 3 del flujo de correcciones, aunque lleguen partidas en bloques
 * (S007/RF-32). Trama: `0xD3`, 6 bits reservados a 0, 10 bits de longitud, los datos y su CRC24Q.
 *
 * Solo cuenta como error (S007/RF-33) una trama dañada cuando ya se estaba sincronizado: el
 * principio cortado de un flujo no es un error. Tras un error busca el siguiente `0xD3` válido.
 */
class RtcmFramer {
    private var buffer = ByteArray(INITIAL_CAPACITY)
    private var size = 0
    private var synced = false

    fun feed(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size - offset): List<RtcmEvent> {
        append(bytes, offset, length)
        val events = ArrayList<RtcmEvent>()
        var position = 0
        while (position < size) {
            if (buffer[position] != PREAMBLE) {
                lostSync(events)
                position = nextPreamble(position + 1)
                continue
            }
            if (size - position < HEADER) break
            val second = buffer[position + 1].toInt() and 0xFF
            if (second and RESERVED_BITS != 0) {
                lostSync(events)
                position++
                continue
            }
            val dataLength = ((second and 0x03) shl 8) or (buffer[position + 2].toInt() and 0xFF)
            val frameLength = HEADER + dataLength + CRC_BYTES
            if (size - position < frameLength) break
            if (Crc24q.compute(buffer, position, HEADER + dataLength) == crcAt(position + HEADER + dataLength)) {
                if (dataLength >= 2) events += RtcmEvent.Message(typeAt(position + HEADER))
                synced = true
                position += frameLength
            } else {
                lostSync(events)
                position++
            }
        }
        compact(position)
        return events
    }

    private fun lostSync(events: MutableList<RtcmEvent>) {
        if (synced) events += RtcmEvent.CrcError
        synced = false
    }

    private fun nextPreamble(from: Int): Int {
        for (i in from until size) if (buffer[i] == PREAMBLE) return i
        return size
    }

    private fun crcAt(i: Int): Int =
        ((buffer[i].toInt() and 0xFF) shl 16) or ((buffer[i + 1].toInt() and 0xFF) shl 8) or (buffer[i + 2].toInt() and 0xFF)

    private fun typeAt(i: Int): Int = ((buffer[i].toInt() and 0xFF) shl 4) or ((buffer[i + 1].toInt() and 0xFF) shr 4)

    private fun append(bytes: ByteArray, offset: Int, length: Int) {
        if (size + length > buffer.size) buffer = buffer.copyOf(maxOf(buffer.size * 2, size + length))
        System.arraycopy(bytes, offset, buffer, size, length)
        size += length
    }

    private fun compact(consumed: Int) {
        if (consumed == 0) return
        System.arraycopy(buffer, consumed, buffer, 0, size - consumed)
        size -= consumed
    }

    private companion object {
        const val PREAMBLE = 0xD3.toByte()
        const val RESERVED_BITS = 0xFC
        const val HEADER = 3
        const val CRC_BYTES = 3
        const val INITIAL_CAPACITY = 2048
    }
}
