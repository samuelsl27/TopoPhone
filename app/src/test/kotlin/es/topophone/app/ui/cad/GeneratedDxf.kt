package es.topophone.app.ui.cad

import java.io.InputStream

/** Un DXF de [size] bytes que no se guarda: se genera al leerlo, entidad a entidad, en una malla de 20 × 20 km sobre Madrid. */
class GeneratedDxf(private val size: Long) : InputStream() {
    private val chunk = StringBuilder()
    private var pending = ByteArray(0)
    private var offset = 0
    private var produced = 0L
    private var index = 0
    var entities = 0
        private set
    private var closing = false

    init {
        chunk.append("0\nSECTION\n2\nENTITIES\n")
    }

    override fun read(): Int {
        val one = ByteArray(1)
        return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xFF
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (offset >= pending.size) {
            if (!refill()) return -1
        }
        val n = minOf(len, pending.size - offset)
        System.arraycopy(pending, offset, b, off, n)
        offset += n
        return n
    }

    private fun refill(): Boolean {
        if (closing && chunk.isEmpty()) return false
        while (chunk.length < BLOCK && produced + chunk.length < size) {
            val i = index++
            val e = 400_000.0 + (i % 2000) * 10.0
            val n = 4_460_000.0 + (i / 2000 % 2000) * 10.0
            if (i % 5 == 0) {
                chunk.append(
                    "0\nPOINT\n8\nPuntos\n10\n",
                ).append(e).append("\n20\n").append(n).append("\n30\n").append(600.0 + i % 50).append('\n')
            } else {
                chunk.append("0\nLINE\n8\nEjes\n10\n").append(e).append("\n20\n").append(n).append("\n30\n").append(600.0)
                    .append("\n11\n").append(e + 8.0).append("\n21\n").append(n + 6.0).append("\n31\n").append(601.0).append('\n')
            }
            entities++
        }
        if (produced + chunk.length >= size) {
            chunk.append("0\nENDSEC\n0\nEOF\n")
            closing = true
        }
        pending = chunk.toString().toByteArray()
        produced += pending.size
        chunk.setLength(0)
        offset = 0
        return pending.isNotEmpty()
    }

    private companion object {
        const val BLOCK = 64 * 1024
    }
}
