package es.topophone.core.gnss.nmea

import io.kotest.matchers.doubles.shouldBeLessThan
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlin.math.abs
import kotlin.random.Random
import org.junit.jupiter.api.Test

/** Ritmo de la reproducción por la hora UTC de las épocas (S003/RF-36 a S003/RF-39). */
class ReplayPacerTest {

    private var now = 0L
    private var speed = ReplaySpeed.X1
    private var loop = false
    private var restarts = 0
    private var sleepJitter: () -> Long = { 0 }

    private fun nmea(body: String) = "$" + body + "*" + "%02X".format(NmeaChecksum.compute(body)) + "\r\n"

    private fun gga(time: String) = nmea("GNGGA,$time,4025.0080000,N,00342.2228000,W,4,24,0.6,650.123,M,51.234,M,1.0,0001")

    private fun pacer(lines: List<String>) = ReplayPacer(
        source = { lines.map { it.toByteArray(Charsets.US_ASCII) }.iterator() },
        clock = { now },
        sleep = { ms -> now += ms + sleepJitter() },
        speed = { speed },
        loop = { loop },
        onRestart = { restarts++ },
    )

    /** Instante (ms) en que se entrega cada línea, hasta el final o [limit] líneas. */
    private fun ReplayPacer.deliveries(limit: Int = Int.MAX_VALUE): List<Pair<String, Long>> {
        val result = mutableListOf<Pair<String, Long>>()
        while (result.size < limit) {
            val line = next() ?: break
            result += String(line, Charsets.US_ASCII) to now
        }
        return result
    }

    @Test
    fun `S003 RF-36 a x1 las epocas se entregan con los intervalos de hora UTC del fichero`() {
        val lines = listOf(gga("101530.00"), nmea("GPGSV,1,1,01,05,62,045,48,1"), gga("101531.00"), gga("101532.50"))

        pacer(lines).deliveries().map { it.second } shouldBe listOf(0L, 0L, 1_000L, 2_500L)
    }

    @Test
    fun `S003 RF-36 a x5 los intervalos son cinco veces menores`() {
        speed = ReplaySpeed.X5

        pacer(listOf(gga("101530.00"), gga("101531.00"), gga("101532.00"))).deliveries().map { it.second } shouldBe listOf(0L, 200L, 400L)
    }

    @Test
    fun `S003 RF-37 un cambio de velocidad se aplica desde la epoca siguiente`() {
        val pacer = pacer((0..5).map { gga("10153$it.00") })
        val times = mutableListOf<Long>()

        repeat(2) {
            pacer.next()
            times += now
        }
        speed = ReplaySpeed.X5
        repeat(4) {
            pacer.next()
            times += now
        }

        times shouldBe listOf(0L, 1_000L, 2_000L, 2_200L, 2_400L, 2_600L)
    }

    @Test
    fun `S003 RF-38 un hueco de mas de 10 s se reproduce como de 10 s`() {
        pacer(listOf(gga("101530.00"), gga("101600.00"), gga("101601.00"))).deliveries().map { it.second } shouldBe
            listOf(0L, 10_000L, 11_000L)
    }

    @Test
    fun `S003 RF-36 el paso de medianoche no detiene la reproduccion`() {
        pacer(listOf(gga("235959.00"), gga("000001.00"))).deliveries().map { it.second } shouldBe listOf(0L, 2_000L)
    }

    @Test
    fun `S003 RF-36 una hora que retrocede o una frase corrupta no hacen esperar`() {
        val corrupt = gga("101545.00").replaceAfterLast('*', "00\r\n")

        pacer(listOf(gga("101530.00"), gga("101529.00"), corrupt, gga("101531.00"))).deliveries().map { it.second } shouldBe
            listOf(0L, 0L, 0L, 1_000L)
    }

    @Test
    fun `S003 RF-39 con el bucle activado al terminar vuelve a empezar y avisa del reinicio`() {
        loop = true

        val delivered = pacer(listOf(gga("101530.00"), gga("101531.00"))).deliveries(limit = 5)

        delivered.map { it.first.substring(7, 16) } shouldBe listOf("101530.00", "101531.00", "101530.00", "101531.00", "101530.00")
        delivered.map { it.second } shouldBe listOf(0L, 1_000L, 1_000L, 2_000L, 2_000L)
        restarts shouldBe 2
    }

    @Test
    fun `S003 RF-39 sin bucle al terminar no hay mas lineas`() {
        val pacer = pacer(listOf(gga("101530.00")))

        pacer.next()

        pacer.next().shouldBeNull()
        restarts shouldBe 0
    }

    @Test
    fun `S003 RF-36 a x1 la duracion de la reproduccion difiere menos de un 1 por ciento aunque el sistema se retrase al dormir`() {
        val random = Random(20261015)
        sleepJitter = { random.nextLong(0, 6) }
        val lines = (0 until 600).map { i ->
            val totalCentis = 10 * 3_600 * 100 + i * 20
            val h = totalCentis / 360_000
            val m = totalCentis / 6_000 % 60
            val s = totalCentis / 100 % 60
            val c = totalCentis % 100
            gga("%02d%02d%02d.%02d".format(h, m, s, c))
        }

        val last = pacer(lines).deliveries().last().second
        val expected = 599 * 200.0

        (abs(last - expected) / expected) shouldBeLessThan 0.01
    }
}
