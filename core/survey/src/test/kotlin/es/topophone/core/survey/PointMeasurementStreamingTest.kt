package es.topophone.core.survey

import es.topophone.core.gnss.nmea.Epoch
import es.topophone.core.gnss.nmea.GgaFix
import es.topophone.core.gnss.nmea.GstAccuracy
import es.topophone.core.gnss.nmea.SolutionStatus
import es.topophone.core.model.point.MeasurementMode
import es.topophone.core.model.project.HeightSystem
import es.topophone.core.model.project.ProjectCrs
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import java.lang.ref.WeakReference
import kotlin.random.Random
import org.junit.jupiter.api.Test

/**
 * Un promediado largo no retiene las épocas usadas y da la misma media, la misma peor solución y las mismas frases que
 * si se hubieran retenido (S017/RF-12; plan A1).
 */
class PointMeasurementStreamingTest {

    private val calculator = ProjectPositionCalculator()

    private fun epoch(
        index: Int,
        lat: Double,
        lon: Double,
        altitude: Double,
        separation: Double,
        status: SolutionStatus,
        satellites: Int,
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

    private fun measurement(seconds: Int, fixOnly: Boolean) = PointMeasurement(
        MeasurementMode.AVERAGED,
        seconds,
        fixOnly,
        poleHeightM = 1.8,
        crs = ProjectCrs.ETRS89_UTM30,
        heightSystem = HeightSystem.ORTHOMETRIC,
        epochIntervalS = 0.1,
        startedAtMs = 1_000_000L,
        calculator = calculator,
    )

    private fun receivedAt(epoch: Epoch) = 1_000_000L + (epoch.utcMillisOfDay - 36_930_000)

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
            val e = epoch(i, lats[i], lons[i], alts[i], seps[i], status, satellites = if (i == 2000) 17 else 24)
            m.onEpoch(e, receivedAt(e))
        }
        val result = updates.filterIsInstance<MeasurementUpdate.Done>().single().result

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

        repeat(3) { i ->
            val e = epoch(i + 1, 40.4168, -3.7038, 650.123, 51.234, SolutionStatus.FIX, 24)
            m.onEpoch(e, receivedAt(e))
        }
        repeat(20) {
            if (first.get() == null) return@repeat
            System.gc()
            Thread.sleep(25)
        }

        first.get().shouldBeNull()
    }

    /** La primera época, que solo la referencia el resultado de esta función: fuera de ella nadie la guarda. */
    private fun firstEpoch(m: PointMeasurement): WeakReference<Epoch> {
        val e = epoch(0, 40.4168, -3.7038, 650.123, 51.234, SolutionStatus.FIX, 24)
        m.onEpoch(e, receivedAt(e))
        return WeakReference(e)
    }
}
