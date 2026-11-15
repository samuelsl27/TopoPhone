package es.topophone.receiver.transport

import es.topophone.core.gnss.nmea.ReplaySpeed
import es.topophone.core.gnss.scenario.Scenario
import es.topophone.core.gnss.scenario.ScenarioOrigin
import es.topophone.core.gnss.scenario.ScenarioPlayer
import es.topophone.core.gnss.scenario.Scenarios
import es.topophone.core.model.point.PositionSource
import io.kotest.matchers.shouldBe
import java.io.BufferedReader
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import org.junit.Test

/** El receptor virtual entrega las épocas de un escenario a 1 Hz, a ×1 y a ×5, y termina con el escenario (S022/RF-12, RF-16, RF-17). */
class VirtualReceiverTransportTest {

    private val origin = ScenarioOrigin(43.2630126, -2.9349852, 40.0)
    private val start = 1_794_736_800_000L // 2026-11-15 10:00:00 UTC

    /** Reloj falso: las esperas lo adelantan, así que el test no tarda nada y el ritmo es exacto. */
    private class FakeTime {
        var now = 5_000L
        val clock: () -> Long = { now }
        val sleep: (Long) -> Unit = { ms -> now += ms }
    }

    private fun transport(time: FakeTime, scenario: Scenario = Scenarios.stableFix, speed: ReplaySpeed = ReplaySpeed.X1) =
        VirtualReceiverTransport("Receptor virtual", ScenarioPlayer(scenario, origin, start), speed, time.clock, time.sleep)

    /** Lee GGA tras GGA; devuelve, de cada una, la hora UTC que lleva y el instante (del reloj falso) en que llegó. */
    private fun readGga(transport: VirtualReceiverTransport, time: FakeTime, count: Int): List<Pair<String, Long>> {
        val reader = BufferedReader(transport.input.reader(Charsets.US_ASCII))
        val result = mutableListOf<Pair<String, Long>>()
        while (result.size < count) {
            val line = reader.readLine() ?: break
            if (line.startsWith("\$GNGGA")) result += line.split(',')[1] to time.now
        }
        return result
    }

    @Test
    fun `S022 RF-12 a x1 llega una epoca cada segundo y la primera enseguida`() {
        val time = FakeTime()
        val epochs = readGga(transport(time), time, 4)

        epochs.map { it.first } shouldBe listOf("100000.00", "100001.00", "100002.00", "100003.00")
        epochs.map { it.second - 5_000 } shouldBe listOf(0L, 1_000L, 2_000L, 3_000L)
    }

    @Test
    fun `S022 RF-16 a x5 llega una epoca cada 200 ms`() {
        val time = FakeTime()
        val epochs = readGga(transport(time, speed = ReplaySpeed.X5), time, 4)

        epochs.map { it.second - 5_000 } shouldBe listOf(0L, 200L, 400L, 600L)
        epochs.map { it.first } shouldBe listOf("100000.00", "100001.00", "100002.00", "100003.00")
    }

    @Test
    fun `S022 RF-16 cambiar la velocidad con el escenario en marcha vale desde la siguiente espera`() {
        val time = FakeTime()
        val virtual = transport(time)
        val reader = BufferedReader(virtual.input.reader(Charsets.US_ASCII))
        fun nextGgaAt(): Long {
            while (true) if (reader.readLine()!!.startsWith("\$GNGGA")) return time.now - 5_000
        }

        val atX1 = listOf(nextGgaAt(), nextGgaAt())
        virtual.speed = ReplaySpeed.X5
        val atX5 = listOf(nextGgaAt(), nextGgaAt())
        virtual.speed = ReplaySpeed.X1
        val backToX1 = nextGgaAt()

        atX1 shouldBe listOf(0L, 1_000L)
        atX5 shouldBe listOf(1_200L, 1_400L)
        backToX1 shouldBe 2_400L
    }

    @Test
    fun `S022 RF-17 al acabar el escenario la lectura termina y todas las epocas salieron`() {
        val time = FakeTime()
        val virtual = transport(time, Scenarios.stableFix, ReplaySpeed.X5)

        val epochs = readGga(virtual, time, Int.MAX_VALUE)

        epochs.size shouldBe Scenarios.stableFix.durationS
        epochs.last().first shouldBe "100159.00"
        virtual.elapsedS shouldBe 119
        virtual.input.read() shouldBe -1
        // 119 intervalos de 200 ms desde la primera.
        epochs.last().second - 5_000 shouldBe 119 * 200L
    }

    @Test
    fun `S022 RF-16 antes de leer nada el segundo transcurrido es menos uno y despues sigue a la ultima epoca`() {
        val time = FakeTime()
        val virtual = transport(time)

        virtual.elapsedS shouldBe -1
        readGga(virtual, time, 3)
        virtual.elapsedS shouldBe 2
        virtual.durationS shouldBe 120
        virtual.scenario shouldBe Scenarios.stableFix
    }

    @Test
    fun `S022 RF-14 en el corte de datos no sale nada durante 15 s y la hora no se detiene`() {
        val time = FakeTime()
        val virtual = transport(time, Scenarios.dataGap, ReplaySpeed.X5)
        val epochs = readGga(virtual, time, Int.MAX_VALUE)

        epochs.size shouldBe 105 // 120 s menos los 15 s del corte
        val before = epochs.indexOfFirst { it.first == "100049.00" }
        epochs[before + 1].first shouldBe "100105.00"
        // 16 intervalos de 200 ms entre la época 49 y la 65.
        epochs[before + 1].second - epochs[before].second shouldBe 16 * 200L
    }

    @Test
    fun `S022 RF-18 es una fuente simulada virtual que descarta lo que se le escribe`() {
        val virtual = transport(FakeTime())

        virtual.simulated shouldBe true
        virtual.positionSource shouldBe PositionSource.VIRTUAL
        virtual.noDataTimeoutMs shouldBe null
        virtual.recordsSession shouldBe false
        virtual.open()
        virtual.output.write(byteArrayOf(1, 2, 3)) // no falla
    }

    @Test
    fun `S022 RF-12 los transportes de siempre siguen siendo del receptor y la reproduccion de una reproduccion`() {
        val stream = StreamTransport("x", ByteArrayInputStream(ByteArray(0)), ByteArrayOutputStream())

        stream.positionSource shouldBe PositionSource.RECEIVER
        stream.noDataTimeoutMs shouldBe null
        stream.recordsSession shouldBe true
        val replay = ReplayTransport("sesion", emptyList(), ReplaySpeed.X1, loop = false)
        replay.positionSource shouldBe PositionSource.REPLAY
        replay.recordsSession shouldBe false
    }

    @Test
    fun `S022 RF-12 cerrar el transporte interrumpe una lectura que espera la siguiente epoca`() {
        // Reloj y espera reales: tras la primera época, la siguiente tarda un segundo y el cierre la deja en nada.
        val virtual = VirtualReceiverTransport("Receptor virtual", ScenarioPlayer(Scenarios.stableFix, origin, start))
        var end = 0L
        val reader = thread {
            val stream = virtual.input
            val buffer = ByteArray(4096)
            stream.read(buffer) // la primera época, enseguida
            val from = System.nanoTime()
            var count = stream.read(buffer) // el resto de la época y luego la espera de la siguiente
            while (count >= 0) count = stream.read(buffer)
            end = (System.nanoTime() - from) / 1_000_000
        }
        Thread.sleep(300)
        virtual.close()
        reader.join(TimeUnit.SECONDS.toMillis(3))

        reader.isAlive shouldBe false
        (end < 900) shouldBe true
    }
}
