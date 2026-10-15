package es.topophone.receiver.transport

import es.topophone.core.gnss.nmea.ReplayPacer
import es.topophone.core.gnss.nmea.ReplaySpeed
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Receptor simulado: entrega los datos de una sesión grabada o importada al ritmo de sus horas UTC
 * (S003/RF-32, S003/RF-36 a RF-39). Lo que se le escribe se descarta. Al cerrarlo, una lectura que
 * espera la siguiente época termina al momento (S003/RF-41).
 */
class ReplayTransport(
    override val description: String,
    private val files: List<File>,
    speed: ReplaySpeed,
    loop: Boolean,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
) : ReceiverTransport {

    @Volatile var speed: ReplaySpeed = speed

    @Volatile var loop: Boolean = loop

    /** Lo fija la sesión: al volver al principio del bucle se reinician las épocas (S003/RF-10). */
    @Volatile var onRestart: () -> Unit = {}

    override val simulated: Boolean = true

    private val closed = CountDownLatch(1)

    private val pacer = ReplayPacer(
        source = { lines() },
        clock = clock,
        sleep = { ms -> closed.await(ms, TimeUnit.MILLISECONDS) },
        speed = { this.speed },
        loop = { this.loop },
        onRestart = { onRestart() },
    )

    override fun open() = Unit

    override val input: InputStream = object : InputStream() {
        private var pending: ByteArray = ByteArray(0)
        private var position = 0

        override fun read(): Int {
            val one = ByteArray(1)
            return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and BYTE_MASK
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            while (position >= pending.size) {
                if (isClosed()) return -1
                pending = pacer.next() ?: return -1 // S003/RF-40: fin sin bucle
                position = 0
                if (isClosed()) return -1
            }
            val count = minOf(len, pending.size - position)
            System.arraycopy(pending, position, b, off, count)
            position += count
            return count
        }
    }

    override val output: OutputStream = object : OutputStream() {
        override fun write(b: Int) = Unit
    }

    override fun close() = closed.countDown()

    private fun isClosed() = closed.count == 0L

    /** Las partes de la sesión, en orden, cortadas por líneas (o cada [MAX_LINE] bytes si no hay fin de línea). */
    private fun lines(): Iterator<ByteArray> = iterator {
        for (file in files) {
            file.inputStream().buffered().use { stream ->
                val line = java.io.ByteArrayOutputStream()
                while (true) {
                    val value = stream.read()
                    if (value < 0) break
                    line.write(value)
                    if (value == NEW_LINE || line.size() >= MAX_LINE) {
                        yield(line.toByteArray())
                        line.reset()
                    }
                }
                if (line.size() > 0) yield(line.toByteArray())
            }
        }
    }

    private companion object {
        const val NEW_LINE = '\n'.code
        const val MAX_LINE = 4096
        const val BYTE_MASK = 0xFF
    }
}
