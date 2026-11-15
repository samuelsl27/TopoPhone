package es.topophone.core.gnss.scenario

import es.topophone.core.gnss.nmea.Constellation
import es.topophone.core.gnss.nmea.EpochAssembler
import es.topophone.core.gnss.nmea.GgaFix
import es.topophone.core.gnss.nmea.GgaParser
import es.topophone.core.gnss.nmea.NmeaChecksum
import es.topophone.core.gnss.nmea.NmeaFramer
import es.topophone.core.gnss.nmea.SolutionStatus
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import java.time.LocalDate
import kotlin.math.cos
import kotlin.math.sqrt
import org.junit.jupiter.api.Test

/** El receptor virtual genera las épocas de un escenario, siempre iguales para el mismo origen y la misma hora (S022/RF-14, RF-15). */
class ScenarioPlayerTest {

    private val bilbao = ScenarioOrigin(43.2630126, -2.9349852, 40.0)
    private val madrid = ScenarioOrigin(40.4168, -3.7038, 700.0)

    // 2026-11-15 10:00:00 UTC
    private val start = 1_794_736_800_000L

    private fun player(scenario: Scenario, origin: ScenarioOrigin = bilbao, at: Long = start) = ScenarioPlayer(scenario, origin, at)

    private fun sentences(lines: List<String>) = NmeaFramer().feed(lines.joinToString("").toByteArray(Charsets.US_ASCII))

    private fun gga(player: ScenarioPlayer, second: Int): GgaFix = sentences(player.epoch(second)).firstNotNullOf { GgaParser.parse(it) }

    /** Metros al este y al norte de [point] respecto a [origin] con las longitudes de grado de la latitud del origen. */
    private fun metres(origin: ScenarioOrigin, fix: GgaFix): Pair<Double, Double> {
        val phi = Math.toRadians(origin.latitudeDeg)
        val metresPerDegreeLatitude = 111_132.92 - 559.82 * cos(2 * phi) + 1.175 * cos(4 * phi)
        val metresPerDegreeLongitude = 111_412.84 * cos(phi) - 93.5 * cos(3 * phi) + 0.118 * cos(5 * phi)
        return (fix.longitudeDeg - origin.longitudeDeg) * metresPerDegreeLongitude to
            (fix.latitudeDeg - origin.latitudeDeg) * metresPerDegreeLatitude
    }

    @Test
    fun `S022 RF-14 dos ejecuciones con el mismo origen y hora dan los mismos bytes`() {
        for (scenario in Scenarios.all) {
            val first = player(scenario)
            val second = player(scenario)
            for (s in 0 until scenario.durationS) {
                first.epochBytes(s).toList() shouldBe second.epochBytes(s).toList()
            }
        }
    }

    @Test
    fun `S022 RF-14 otro origen o otra hora cambian los bytes`() {
        val base = player(Scenarios.stableFix).epochBytes(10).toList()

        (player(Scenarios.stableFix, madrid).epochBytes(10).toList() == base) shouldBe false
        (player(Scenarios.stableFix, at = start + 5_000).epochBytes(10).toList() == base) shouldBe false
    }

    @Test
    fun `S022 RF-15 la hora UTC de las epocas crece de un segundo en un segundo desde la de inicio`() {
        val p = player(Scenarios.stableFix)

        val times = (0 until 5).map { gga(p, it).utcTime }

        times shouldBe listOf("100000.00", "100001.00", "100002.00", "100003.00", "100004.00")
        p.utcMillis(3) shouldBe start + 3_000
    }

    @Test
    fun `S022 RF-15 la hora de inicio se trunca al segundo y la fecha pasa de dia a medianoche`() {
        val p = player(Scenarios.stableFix, at = 1_794_787_199_400L) // 2026-11-15 23:59:59.400

        gga(p, 0).utcTime shouldBe "235959.00"
        gga(p, 1).utcTime shouldBe "000000.00"
        val zda = sentences(p.epoch(1)).first { it.type == "ZDA" }
        zda.fields.slice(1..3) shouldBe listOf("16", "11", "2026")
    }

    @Test
    fun `S022 RF-15 una epoca lleva GGA primero, luego GST, GSA, GSV y ZDA, y ninguna frase de base ni de inclinacion`() {
        val types = sentences(player(Scenarios.stableFix).epoch(0)).map { it.type }

        types.first() shouldBe "GGA"
        types shouldContain "GST"
        types shouldContain "GSA"
        types shouldContain "GSV"
        types.last() shouldBe "ZDA"
        types.contains("EBP") shouldBe false
        types.contains("ETC") shouldBe false
    }

    @Test
    fun `S022 RF-15 todas las frases llevan suma de control valida`() {
        val lines = player(Scenarios.straightWalk).epoch(30)

        (lines.size > 8) shouldBe true
        lines.all { NmeaChecksum.isValid(it.trim()) } shouldBe true
        lines.all { it.endsWith("\r\n") } shouldBe true
    }

    @Test
    fun `S022 RF-14 la primera epoca esta en el origen y el origen cambia las coordenadas`() {
        for (origin in listOf(bilbao, madrid)) {
            val fix = gga(player(Scenarios.stableFix, origin), 0)

            fix.latitudeDeg shouldBe (origin.latitudeDeg plusOrMinus 1e-6)
            fix.longitudeDeg shouldBe (origin.longitudeDeg plusOrMinus 1e-6)
            fix.altitudeM.shouldNotBeNull() + fix.geoidSeparationM.shouldNotBeNull() shouldBe (origin.ellipsoidalHeightM plusOrMinus 1e-3)
        }
    }

    @Test
    fun `S022 RF-14 FIX estable queda quieto en FIX con 22 satelites y un temblor de pocos milimetros`() {
        val p = player(Scenarios.stableFix)

        val points = (0 until Scenarios.stableFix.durationS).map { gga(p, it) }

        points.all { it.status == SolutionStatus.FIX && it.satellites == 22 } shouldBe true
        val offsets = points.map { metres(bilbao, it) }
        offsets.all { (e, n) -> sqrt(e * e + n * n) < 0.006 } shouldBe true
        (offsets.maxOf { it.first } - offsets.minOf { it.first } > 0.002) shouldBe true // se mueve algo: no es una constante
        Scenarios.stableFix.durationS shouldBe 120
    }

    @Test
    fun `S022 RF-14 el paseo avanza un metro por segundo al este y luego al norte`() {
        val p = player(Scenarios.straightWalk)

        val expectedPoints = listOf(0 to (0.0 to 0.0), 30 to (30.0 to 0.0), 59 to (59.0 to 0.0), 60 to (60.0 to 0.0), 90 to (60.0 to 30.0))
        for ((second, expected) in expectedPoints) {
            val (east, north) = metres(bilbao, gga(p, second))
            east shouldBe (expected.first plusOrMinus 0.01)
            north shouldBe (expected.second plusOrMinus 0.01)
        }
        val (_, northAt119) = metres(bilbao, gga(p, 119))
        northAt119 shouldBe (59.0 plusOrMinus 0.01)
        Scenarios.straightWalk.durationS shouldBe 120
    }

    @Test
    fun `S022 RF-15 el ensamblador de epocas del receptor lee la sesion completa del escenario`() {
        val p = player(Scenarios.stableFix)
        val assembler = EpochAssembler()
        val epochs = mutableListOf<es.topophone.core.gnss.nmea.Epoch>()
        var now = 0L
        for (second in 0 until 6) {
            for (sentence in sentences(p.epoch(second))) assembler.feed(sentence, now)?.let { epochs += it }
            now += 1_000
        }
        assembler.flush(now)?.let { epochs += it }

        epochs shouldHaveSize 6
        epochs.forEach { epoch ->
            epoch.gga.shouldNotBeNull().status shouldBe SolutionStatus.FIX
            epoch.gst.shouldNotBeNull().horizontalM.shouldNotBeNull() shouldBe (sqrt(0.008 * 0.008 + 0.007 * 0.007) plusOrMinus 1e-9)
            epoch.satellitesUsed shouldBe 22
            epoch.satellitesInView shouldBe 22
            epoch.date shouldBe LocalDate.of(2026, 11, 15)
            epoch.gsa.keys shouldBe setOf(Constellation.GPS, Constellation.GLONASS, Constellation.GALILEO, Constellation.BEIDOU)
        }
    }

    @Test
    fun `S022 RF-14 pedir un segundo fuera del escenario es un error`() {
        val p = player(Scenarios.stableFix)

        shouldThrow<IllegalArgumentException> { p.epoch(-1) }
        shouldThrow<IllegalArgumentException> { p.epoch(120) }
        p.epoch(119).isEmpty() shouldBe false
    }

    @Test
    fun `S022 RF-14 el catalogo se busca por identificador y no repite ninguno`() {
        Scenarios.byId(ScenarioId.STABLE_FIX) shouldBe Scenarios.stableFix
        Scenarios.byId(ScenarioId.STRAIGHT_WALK) shouldBe Scenarios.straightWalk
        Scenarios.all.map { it.id }.toSet() shouldHaveSize Scenarios.all.size
        Scenarios.all.filter { it.durationS <= 0 }.shouldBeEmpty()
    }
}
