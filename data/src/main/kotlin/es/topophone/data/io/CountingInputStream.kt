package es.topophone.data.io

import java.io.FilterInputStream
import java.io.InputStream

/**
 * Cuenta los bytes leídos, los avisa y comprueba la cancelación cada vez que se leen [REPORT_EVERY] bytes más (S040/RF-11, S042/RF-10).
 * Al cerrar avisa del tamaño leído entero.
 */
internal class CountingInputStream(input: InputStream, private val onProgress: (Long) -> Unit, private val check: () -> Unit) :
    FilterInputStream(input) {
    private var total = 0L
    private var lastReported = 0L

    override fun read(): Int = super.read().also { if (it >= 0) advance(1) }

    override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, len).also { if (it > 0) advance(it.toLong()) }

    private fun advance(n: Long) {
        total += n
        if (total - lastReported >= REPORT_EVERY || n < 0) {
            lastReported = total
            check()
            onProgress(total)
        }
    }

    override fun close() {
        onProgress(total) // el último aviso es siempre el tamaño leído entero
        super.close()
    }

    private companion object {
        const val REPORT_EVERY = 64 * 1024
    }
}
