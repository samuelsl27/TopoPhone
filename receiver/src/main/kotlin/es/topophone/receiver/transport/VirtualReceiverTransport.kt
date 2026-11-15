package es.topophone.receiver.transport

import es.topophone.core.gnss.nmea.ReplaySpeed
import es.topophone.core.gnss.scenario.Scenario
import es.topophone.core.gnss.scenario.ScenarioPlayer
import es.topophone.core.model.point.PositionSource
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Receptor virtual (S022/RF-12, RF-14, RF-16, plan D6): entrega una época del escenario por segundo a ×1 (cada 200 ms a ×5) con
 * el reloj monótono, como lo haría un receptor real a 1 Hz, y termina al acabar el escenario. Lo que se le escribe se descarta.
 * Es una fuente simulada: ni graba sesión ni admite NTRIP (S022/RF-18). Al cerrarlo, una lectura que espera la siguiente época
 * termina al momento.
 *
 * [clock] y [sleep] se pueden sustituir en los tests; por defecto son el reloj monótono y una espera que el cierre interrumpe.
 */
class VirtualReceiverTransport(
    override val description: String,
    private val player: ScenarioPlayer,
    speed: ReplaySpeed = ReplaySpeed.X1,
    private val clock: () -> Long = { System.nanoTime() / NANOS_PER_MILLI },
    sleep: ((Long) -> Unit)? = null,
) : ReceiverTransport {

    /** Velocidad ×1 o ×5; se puede cambiar con el escenario en marcha y vale desde la siguiente espera (S022/RF-16). */
    @Volatile var speed: ReplaySpeed = speed

    override val simulated: Boolean = true

    override val positionSource: PositionSource = PositionSource.VIRTUAL

    /** El escenario en marcha. */
    val scenario: Scenario get() = player.scenario

    /** Duración del escenario en segundos de escenario (no de reloj: a ×5 pasa cinco veces más deprisa). */
    val durationS: Int get() = player.durationS

    /** El segundo del escenario de la última época entregada; −1 antes de la primera. */
    @Volatile var elapsedS: Int = -1
        private set

    private val closed = CountDownLatch(1)
    private val wait: (Long) -> Unit = sleep ?: { ms -> closed.await(ms, TimeUnit.MILLISECONDS) }

    // Instante (reloj monótono) en que tocó la última época; se fija al leer la primera.
    private var lastDueMs = Long.MIN_VALUE
    private var nextSecond = 0

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
                if (isClosed() || nextSecond >= player.durationS) return -1 // S022/RF-17: fin del escenario
                if (!waitForNextEpoch()) return -1
                val second = nextSecond++
                elapsedS = second
                pending = player.epochBytes(second) // vacía en un corte de datos: no sale nada ese segundo
                position = 0
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

    /**
     * Espera a que toque la siguiente época. El instante se calcula con la velocidad de cada momento, así que un cambio de ×1 a ×5
     * (o al revés) vale en la espera en curso; las esperas son de 200 ms como mucho. `false` si se cerró mientras tanto.
     */
    private fun waitForNextEpoch(): Boolean {
        if (lastDueMs == Long.MIN_VALUE) lastDueMs = clock()
        while (true) {
            if (isClosed()) return false
            val now = clock()
            val due = lastDueMs + periodMs()
            if (nextSecond == 0) return true // la primera época sale en cuanto se lee
            if (now >= due) {
                // Si el lector va tarde no se acumulan épocas: se sigue desde ahora.
                lastDueMs = maxOf(due, now - periodMs())
                return true
            }
            wait(minOf(due - now, MAX_WAIT_MS))
        }
    }

    private fun periodMs(): Long = MILLIS_PER_SECOND / speed.factor

    private companion object {
        const val NANOS_PER_MILLI = 1_000_000L
        const val MILLIS_PER_SECOND = 1000L
        const val MAX_WAIT_MS = 200L
        const val BYTE_MASK = 0xFF
    }
}
