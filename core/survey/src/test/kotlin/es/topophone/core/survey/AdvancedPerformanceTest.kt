package es.topophone.core.survey

import es.topophone.core.gnss.nmea.Epoch
import es.topophone.core.gnss.nmea.GgaFix
import es.topophone.core.gnss.nmea.GstAccuracy
import es.topophone.core.gnss.nmea.SolutionStatus
import es.topophone.core.model.point.MeasurementMode
import es.topophone.core.model.project.HeightSystem
import es.topophone.core.model.project.ProjectCrs
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.longs.shouldBeLessThan
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlin.random.Random
import org.junit.jupiter.api.Test

/** Un promediado de 60 min a 10 Hz con sus estadísticas, sin cargar el hilo principal (S017, RNF-T13). */
class AdvancedPerformanceTest {

    private val random = Random(17)

    private fun epoch(i: Int): Epoch {
        val fix = GgaFix(
            "t$i",
            40.4168 + random.nextDouble(-4e-8, 4e-8),
            -3.7038 + random.nextDouble(-4e-8, 4e-8),
            SolutionStatus.FIX,
            24,
            0.6,
            650.123 + random.nextDouble(-0.01, 0.01),
            51.234,
            1.0,
            "\$GNGGA,$i*00",
        )
        return Epoch(
            i * 100,
            null,
            fix,
            GstAccuracy("t$i", 0.008, 0.006, 0.015, "\$GNGST,$i*00"),
            null,
            null,
            null,
            null,
            null,
            emptyMap(),
            emptyMap(),
            0,
        )
    }

    private fun measurement() = PointMeasurement(
        MeasurementMode.AVERAGED,
        averagingSeconds = 3_600,
        fixOnly = true,
        poleHeightM = 1.8,
        crs = ProjectCrs.ETRS89_UTM30,
        heightSystem = HeightSystem.ORTHOMETRIC,
        epochIntervalS = 0.1,
        startedAtMs = 0,
    )

    @Test
    fun `S017 RNF-T13 un promediado de 60 min a 10 Hz cuesta menos de 1 ms por epoca`() {
        val count = 36_000
        val epochs = List(count) { epoch(it) } // el coste de fabricar las épocas no es de la medida
        val warmUp = measurement()
        epochs.take(2_000).forEachIndexed { i, e -> warmUp.onEpoch(e, i * 100L) }

        val measurement = measurement()
        val started = System.nanoTime()
        epochs.forEachIndexed { i, e -> measurement.onEpoch(e, i * 100L) }
        val perEpochNs = (System.nanoTime() - started) / count

        perEpochNs shouldBeLessThan 1_000_000L
        measurement.stats().shouldNotBeNull().used shouldBe count
    }

    @Test
    fun `S017 RNF-T13 las estadisticas y el grafico de 36000 epocas se piden a 5 Hz en menos de 16 ms`() {
        val measurement = measurement()
        (0 until 36_000).forEach { measurement.onEpoch(epoch(it), it * 100L) }
        // Calentamiento del JIT.
        repeat(50) {
            measurement.stats()
            measurement.dispersion()
        }

        val requests = 200
        var points = 0
        val started = System.nanoTime()
        repeat(requests) {
            measurement.stats().shouldNotBeNull()
            points = measurement.dispersion().shouldNotBeNull().count
        }
        val perRequestNs = (System.nanoTime() - started) / requests

        perRequestNs shouldBeLessThan 16_000_000L
        points shouldBeGreaterThanOrEqual 1_000 // el gráfico no pierde la nube: como mucho 2.000 puntos de 36.000
    }
}
