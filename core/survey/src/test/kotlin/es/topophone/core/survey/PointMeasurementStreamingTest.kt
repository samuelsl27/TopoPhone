package es.topophone.core.survey

import es.topophone.core.gnss.nmea.Epoch
import es.topophone.core.gnss.nmea.GgaFix
import es.topophone.core.gnss.nmea.GstAccuracy
import es.topophone.core.gnss.nmea.SolutionStatus
import es.topophone.core.model.point.MeasurementMode
import es.topophone.core.model.project.HeightSystem
import es.topophone.core.model.project.ProjectCrs
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import java.lang.ref.WeakReference
import kotlin.random.Random
import org.junit.jupiter.api.Test

/**
 * Un promediado largo no retiene las épocas usadas y da la misma media, la misma peor solución y las mismas frases que
 * si se hubieran retenido (S017/RF-12; plan A1), y con ellas su tiempo promediado y sus desviaciones típicas (RF-3, RF-12).
 */
class PointMeasurementStreamingTest {

    private val calculator = ProjectPositionCalculator()

    private fun epoch(
        index: Int,
        lat: Double,
        lon: Double,
        altitude: Double?,
        separation: Double?,
        status: SolutionStatus = SolutionStatus.FIX,
        satellites: Int = 24,
    ): Epoch {
        val utc = 36_930_000 + index * 100
        return Epoch(
            utcMillisOfDay = utc,
            date = null,
            gga = GgaFix(
                "t$index",
                lat,
                lon,
                status,
                satellites,
                0.5 + index * 1e-4,
                altitude,
                separation,
                1.0 + index * 1e-3,
                "\$GNGGA,$index*00",
            ),
            gst = GstAccuracy("t$index", 0.008, 0.006, 0.016, "\$GNGST,$index*00"),
            rmc = null,
            vtg = null,
            zda = null,
            basePosition = null,
            tilt = null,
            gsa = emptyMap(),
            gsv = emptyMap(),
            completedAtMs = 7L,
        )
    }

    private fun measurement(
        seconds: Int,
        fixOnly: Boolean,
        mode: MeasurementMode = MeasurementMode.AVERAGED,
        crs: ProjectCrs = ProjectCrs.ETRS89_UTM30,
    ) = PointMeasurement(
        mode,
        seconds,
        fixOnly,
        poleHeightM = 1.8,
        crs = crs,
        heightSystem = HeightSystem.ORTHOMETRIC,
        epochIntervalS = 0.1,
        startedAtMs = 1_000_000L,
        calculator = calculator,
    )

    private fun receivedAt(epoch: Epoch) = 1_000_000L + (epoch.utcMillisOfDay - 36_930_000)

    private fun PointMeasurement.feed(epoch: Epoch) = onEpoch(epoch, receivedAt(epoch))

    private fun List<MeasurementUpdate>.result() = filterIsInstance<MeasurementUpdate.Done>().single().result

    @Test
    fun `S017 RF-12 la media de 3000 epocas es la aritmetica de siempre, con la peor solucion y los menos satelites`() {
        val random = Random(7)
        val lats = List(3000) { 40.4168 + random.nextDouble(-2e-8, 2e-8) }
        val lons = List(3000) { -3.7038 + random.nextDouble(-2e-8, 2e-8) }
        val alts = List(3000) { 650.123 + random.nextDouble(-0.01, 0.01) }
        val seps = List(3000) { 51.234 + random.nextDouble(-1e-4, 1e-4) }
        val m = measurement(seconds = 300, fixOnly = false)

        val updates = (0 until 3000).flatMap { i ->
            val status = if (i == 1500) SolutionStatus.FLOAT else SolutionStatus.FIX
            m.feed(epoch(i, lats[i], lons[i], alts[i], seps[i], status, satellites = if (i == 2000) 17 else 24))
        }
        val result = updates.result()

        result.antenna.latitudeDeg shouldBe lats.average()
        result.antenna.longitudeDeg shouldBe lons.average()
        result.antenna.ellipsoidalHeightM.shouldNotBeNull() shouldBe (alts.average() + seps.average())
        result.epochsUsed shouldBe 3000
        result.solution shouldBe SolutionStatus.FLOAT
        result.satellites shouldBe 17
        // De la última época: HDOP y edad de las correcciones.
        result.hdop shouldBe (0.5 + 2999 * 1e-4)
        result.correctionAgeS shouldBe (1.0 + 2999 * 1e-3)
        result.rawNmea.lines().size shouldBe 6000
        result.rawNmea.lines().first() shouldBe "\$GNGGA,0*00"
        result.rawNmea.lines().last() shouldBe "\$GNGST,2999*00"
    }

    @Test
    fun `S017 RF-12 una epoca usada no queda retenida por la medida`() {
        val m = measurement(seconds = 1000, fixOnly = true)
        val first = firstEpoch(m)

        repeat(3) { i -> m.feed(epoch(i + 1, 40.4168, -3.7038, 650.123, 51.234)) }
        repeat(20) {
            if (first.get() == null) return@repeat
            System.gc()
            Thread.sleep(25)
        }

        first.get().shouldBeNull()
    }

    /** La primera época, que solo la referencia el resultado de esta función: fuera de ella nadie la guarda. */
    private fun firstEpoch(m: PointMeasurement): WeakReference<Epoch> {
        val e = epoch(0, 40.4168, -3.7038, 650.123, 51.234)
        m.feed(e)
        return WeakReference(e)
    }

    private class Scenario(val name: String, val crs: ProjectCrs, val seconds: Int)

    private val scenarios = listOf(
        Scenario("promediado-60min", ProjectCrs.ETRS89_UTM30, 3600),
        Scenario("promediado-canarias", ProjectCrs.REGCAN95_UTM28, 600),
    )

    /** Las épocas de un escenario como las daría el receptor: la altitud de la GGA es la elipsoidal menos la separación. */
    private fun epochs(scenario: Scenario) = SurveyFixtures.rows("${scenario.name}.csv", "promediado").mapIndexed { i, row ->
        epoch(i, row[1].toDouble(), row[2].toDouble(), row[3].toDouble() - SEPARATION, SEPARATION)
    }

    private fun expected(scenario: Scenario) = SurveyFixtures.rows("${scenario.name}.esperado.csv", "promediado")

    @Test
    fun `S017 RF-12 el tiempo promediado y las desviaciones tipicas de 36000 epocas coinciden con pyproj a menos de 0,1 mm`() {
        for (scenario in scenarios) {
            val m = measurement(scenario.seconds, fixOnly = true, crs = scenario.crs)
            val last = expected(scenario).last()

            val result = epochs(scenario).flatMap { m.feed(it) }.result()

            result.averagedTimeS.shouldNotBeNull() shouldBe (scenario.seconds.toDouble() plusOrMinus 1e-6)
            result.deviationHorizontalM.shouldNotBeNull() shouldBe (last[5].toDouble() plusOrMinus 1e-4)
            result.deviationVerticalM.shouldNotBeNull() shouldBe (last[6].toDouble() plusOrMinus 1e-4)
            result.position.easting.shouldNotBeNull() shouldBe (last[2].toDouble() plusOrMinus 1e-4)
            result.position.northing.shouldNotBeNull() shouldBe (last[3].toDouble() plusOrMinus 1e-4)
        }
    }

    @Test
    fun `S017 RF-4 durante el promediado se piden las estadisticas y el grafico`() {
        val scenario = scenarios.first()
        val m = measurement(scenario.seconds, fixOnly = true, crs = scenario.crs)
        m.stats().shouldBeNull()
        val checkpoint = expected(scenario).first { it[0] == "12000" }

        epochs(scenario).take(12_000).forEach { m.feed(it) }
        val stats = m.stats().shouldNotBeNull()

        stats.used shouldBe 12_000
        stats.timeS shouldBe (1200.0 plusOrMinus 1e-6)
        stats.deviationHorizontalM.shouldNotBeNull() shouldBe (checkpoint[5].toDouble() plusOrMinus 1e-4)
        stats.stabilityHorizontalM.shouldNotBeNull() shouldBe (checkpoint[7].toDouble() plusOrMinus 1e-4)
        stats.stabilityVerticalM.shouldNotBeNull() shouldBe (checkpoint[8].toDouble() plusOrMinus 1e-4)
        m.dispersion().shouldNotBeNull().count shouldBe 2000
    }

    @Test
    fun `S017 RF-12 una medida instantanea no lleva tiempo promediado ni desviaciones`() {
        val m = measurement(seconds = 5, fixOnly = true, mode = MeasurementMode.INSTANT)

        val result = m.feed(epoch(0, 40.4168, -3.7038, 650.123, 51.234)).result()

        result.averagedTimeS.shouldBeNull()
        result.deviationHorizontalM.shouldBeNull()
        result.deviationVerticalM.shouldBeNull()
        m.stats().shouldBeNull()
        m.dispersion().shouldBeNull()
    }

    @Test
    fun `S017 RF-3 con una sola epoca usada no hay desviaciones tipicas`() {
        val m = measurement(seconds = 1, fixOnly = true).also { it.feed(epoch(0, 40.4168, -3.7038, 650.123, 51.234)) }

        // 1 s con épocas de 0,1 s son 10: con una sola no se ha completado.
        m.stats().shouldNotBeNull().deviationHorizontalM.shouldBeNull()
    }

    @Test
    fun `S017 RF-3 sin altitud hay desviacion horizontal y no vertical`() {
        val m = measurement(seconds = 1, fixOnly = true)

        val result = (0 until 10).flatMap { i ->
            m.feed(epoch(i, 40.4168 + i * 1e-8, -3.7038, altitude = null, separation = null))
        }.result()

        result.deviationHorizontalM.shouldNotBeNull()
        result.deviationVerticalM.shouldBeNull()
    }

    @Test
    fun `S017 RF-3 en un SC geografico las desviaciones salen en el plano local`() {
        val scenario = scenarios.first()
        val m = measurement(scenario.seconds, fixOnly = true, crs = ProjectCrs.ETRS89_GEOGRAPHIC)
        val last = expected(scenario).last()

        val result = epochs(scenario).flatMap { m.feed(it) }.result()

        // El plano local y la UTM difieren en el factor de escala (4·10⁻⁴): unos 10 µm con 2 cm de dispersión.
        result.deviationHorizontalM.shouldNotBeNull() shouldBe (last[5].toDouble() plusOrMinus 1e-4)
        result.deviationVerticalM.shouldNotBeNull() shouldBe (last[6].toDouble() plusOrMinus 1e-4)
    }

    private companion object {
        const val SEPARATION = 51.234
    }
}
