package es.topophone.core.gnss.scenario

import es.topophone.core.gnss.nmea.EbpParser
import es.topophone.core.gnss.nmea.EtcParser
import es.topophone.core.gnss.nmea.GgaParser
import es.topophone.core.gnss.nmea.NmeaFramer
import es.topophone.core.gnss.nmea.NmeaSentence
import es.topophone.core.gnss.nmea.SolutionStatus
import es.topophone.core.gnss.nmea.TiltNotification
import es.topophone.core.gnss.nmea.TiltState
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sqrt
import org.junit.jupiter.api.Test

/** Los instantes de los eventos de cada escenario del receptor virtual (S022/RF-14, RF-15). */
class ScenarioEventsTest {

    private val origin = ScenarioOrigin(43.2630126, -2.9349852, 40.0)
    private val start = 1_794_736_800_000L // 2026-11-15 10:00:00 UTC

    private fun player(scenario: Scenario) = ScenarioPlayer(scenario, origin, start)

    private fun sentences(player: ScenarioPlayer, second: Int): List<NmeaSentence> = NmeaFramer().feed(player.epochBytes(second))

    private fun status(player: ScenarioPlayer, second: Int) = sentences(player, second).firstNotNullOf { GgaParser.parse(it) }.status

    private fun statuses(scenario: Scenario, seconds: IntRange) = player(scenario).let { p -> seconds.map { status(p, it) } }

    private fun metresPerDegree(): Pair<Double, Double> {
        val phi = Math.toRadians(origin.latitudeDeg)
        val east = 111_412.84 * cos(phi) - 93.5 * cos(3 * phi) + 0.118 * cos(5 * phi)
        val north = 111_132.92 - 559.82 * cos(2 * phi) + 1.175 * cos(4 * phi)
        return east to north
    }

    @Test
    fun `S022 RF-14 el catalogo trae los siete escenarios en su orden y con su duracion`() {
        Scenarios.all.map { it.id } shouldBe listOf(
            ScenarioId.STABLE_FIX,
            ScenarioId.FIX_LOSS_AND_RECOVERY,
            ScenarioId.FLOAT_AND_SINGLE,
            ScenarioId.DATA_GAP,
            ScenarioId.BASE_CHANGE,
            ScenarioId.TILT_FIGURE_EIGHT,
            ScenarioId.STRAIGHT_WALK,
        )
        Scenarios.all.map { it.durationS } shouldBe listOf(120, 180, 150, 120, 150, 120, 120)
        ScenarioId.entries.all { Scenarios.byId(it) != null } shouldBe true
    }

    @Test
    fun `S022 RF-14 perdida de FIX y recuperacion cambia de solucion a los 40, 60, 120 y 140 s`() {
        val all = statuses(Scenarios.fixLossAndRecovery, 0 until 180)

        all.slice(0..39).toSet() shouldBe setOf(SolutionStatus.FIX)
        all.slice(40..59).toSet() shouldBe setOf(SolutionStatus.FLOAT)
        all.slice(60..119).toSet() shouldBe setOf(SolutionStatus.SINGLE)
        all.slice(120..139).toSet() shouldBe setOf(SolutionStatus.FLOAT)
        all.slice(140..179).toSet() shouldBe setOf(SolutionStatus.FIX)
    }

    @Test
    fun `S022 RF-14 con SINGLE hay menos satelites, peor precision y ninguna edad de correcciones`() {
        val p = player(Scenarios.fixLossAndRecovery)

        fun gga(second: Int) = sentences(p, second).firstNotNullOf { GgaParser.parse(it) }

        gga(10).satellites shouldBe 22
        gga(10).correctionAgeS.shouldNotBeNull()
        gga(50).satellites shouldBe 14
        gga(80).satellites shouldBe 7
        gga(80).correctionAgeS.shouldBeNull()
        (gga(80).hdop!! > gga(10).hdop!!) shouldBe true
    }

    @Test
    fun `S022 RF-14 FLOAT y SINGLE no llega nunca a FIX y cambia a los 60 y a los 100 s`() {
        val all = statuses(Scenarios.floatAndSingle, 0 until 150)

        all.slice(0..59).toSet() shouldBe setOf(SolutionStatus.FLOAT)
        all.slice(60..99).toSet() shouldBe setOf(SolutionStatus.SINGLE)
        all.slice(100..149).toSet() shouldBe setOf(SolutionStatus.FLOAT)
        all.contains(SolutionStatus.FIX) shouldBe false
    }

    @Test
    fun `S022 RF-14 el corte de datos no emite ninguna frase de los 50 a los 64 s y sigue a los 65 s`() {
        val p = player(Scenarios.dataGap)

        (0 until 120).filter { p.epoch(it).isEmpty() } shouldBe (50..64).toList()
        // El tiempo del escenario no se detiene: tras el corte la hora es la de ese segundo, no la del anterior.
        val resumed = sentences(p, 65).firstNotNullOf { GgaParser.parse(it) }
        resumed.utcTime shouldBe "100105.00"
        resumed.status shouldBe SolutionStatus.FIX
    }

    @Test
    fun `S022 RF-14 el cambio de base manda la EBP de la base A y luego la de la B, a 2 km, con 5 s de FLOAT`() {
        val p = player(Scenarios.baseChange)
        val bases = (0 until 150).map { second ->
            sentences(p, second).firstNotNullOf { EbpParser.parse(it) }
        }
        val (metresEast, metresNorth) = metresPerDegree()

        bases.slice(0..69).toSet().size shouldBe 1
        bases.slice(70..149).toSet().size shouldBe 1
        val a = bases[0]
        val b = bases[70]
        val east = (b.longitudeDeg - a.longitudeDeg) * metresEast
        val north = (b.latitudeDeg - a.latitudeDeg) * metresNorth
        sqrt(east * east + north * north) shouldBe (2000.0 plusOrMinus 1.0)
        a.ellipsoidalHeightM shouldBe (origin.ellipsoidalHeightM + 12.0 plusOrMinus 1e-3)
        b.ellipsoidalHeightM shouldBe (origin.ellipsoidalHeightM + 15.0 plusOrMinus 1e-3)

        val all = statuses(Scenarios.baseChange, 0 until 150)
        all.slice(0..69).toSet() shouldBe setOf(SolutionStatus.FIX)
        all.slice(70..74).toSet() shouldBe setOf(SolutionStatus.FLOAT)
        all.slice(75..149).toSet() shouldBe setOf(SolutionStatus.FIX)
    }

    @Test
    fun `S022 RF-14 la inclinacion pasa de SETUP a ALIGNMENT a los 20 s y a COMPENSATING a los 40 s`() {
        val p = player(Scenarios.tiltFigureEight)
        val tilt = (0 until 120).map { second -> sentences(p, second).firstNotNullOf { EtcParser.parse(it) } }

        tilt.slice(0..19).map { it.state }.toSet() shouldBe setOf(TiltState.SETUP)
        tilt.slice(20..39).map { it.state }.toSet() shouldBe setOf(TiltState.ALIGNMENT)
        tilt.slice(40..119).map { it.state }.toSet() shouldBe setOf(TiltState.COMPENSATING)
        tilt[10].headingRad.shouldBeNull()
        tilt[30].headingRad.shouldNotBeNull()
        tilt[30].tiltRad.shouldBeNull()
        tilt[60].tiltRad.shouldNotBeNull()
        tilt[60].accuracyCoefficients.shouldNotBeNull()
    }

    @Test
    fun `S022 RF-14 el aviso de movimiento rapido solo suena de los 90 a los 94 s`() {
        val p = player(Scenarios.tiltFigureEight)

        val notifications = (0 until 120).map { second -> sentences(p, second).firstNotNullOf { EtcParser.parse(it) }.notification }

        notifications.indices.filter { notifications[it] == TiltNotification.FAST_MOTION } shouldBe (90..94).toList()
        notifications.toSet() shouldBe setOf(TiltNotification.NONE, TiltNotification.FAST_MOTION)
    }

    @Test
    fun `S022 RF-14 el ocho mide 1,2 m de este a oeste y 0,6 m de norte a sur y se cierra cada 20 s`() {
        val p = player(Scenarios.tiltFigureEight)
        val (metresEast, metresNorth) = metresPerDegree()
        val offsets = (0 until 120).map { second ->
            val fix = sentences(p, second).firstNotNullOf { GgaParser.parse(it) }
            (fix.longitudeDeg - origin.longitudeDeg) * metresEast to (fix.latitudeDeg - origin.latitudeDeg) * metresNorth
        }

        offsets.maxOf { abs(it.first) } shouldBe (1.2 plusOrMinus 0.02)
        // Muestreado cada segundo, el máximo norte del ocho (0,6 m) cae en 0,6 · sen(72º) = 0,57 m.
        offsets.maxOf { abs(it.second) } shouldBe (0.57 plusOrMinus 0.02)
        for (second in 0 until 100) {
            offsets[second + 20].first shouldBe (offsets[second].first plusOrMinus 0.002)
            offsets[second + 20].second shouldBe (offsets[second].second plusOrMinus 0.002)
        }
    }

    @Test
    fun `S022 RF-14 solo el cambio de base lleva EBP y solo la inclinacion lleva ETC`() {
        for (scenario in Scenarios.all) {
            val types = sentences(player(scenario), 0).map { it.type }.toSet()

            (types.contains("EBP")) shouldBe (scenario.id == ScenarioId.BASE_CHANGE)
            (types.contains("ETC")) shouldBe (scenario.id == ScenarioId.TILT_FIGURE_EIGHT)
        }
    }

    @Test
    fun `S022 RNF generar un escenario de 180 s tarda menos de 100 ms`() {
        val p = player(Scenarios.fixLossAndRecovery)
        fun generate(): Long {
            val from = System.nanoTime()
            for (second in 0 until p.durationS) p.epochBytes(second)
            return (System.nanoTime() - from) / 1_000_000
        }
        repeat(3) { generate() } // calentamiento de la JVM

        val best = (1..5).minOf { generate() }

        (best < 100) shouldBe true
    }
}
