package es.topophone.core.survey

import es.topophone.core.gnss.nmea.Epoch
import es.topophone.core.gnss.nmea.GgaFix
import es.topophone.core.gnss.nmea.GstAccuracy
import es.topophone.core.gnss.nmea.SolutionStatus
import es.topophone.core.gnss.nmea.TiltAccuracyCoefficients
import es.topophone.core.gnss.nmea.TiltData
import es.topophone.core.gnss.nmea.TiltNotification
import es.topophone.core.gnss.nmea.TiltState
import es.topophone.core.model.point.MeasurementMode
import es.topophone.core.model.point.TiltMode
import es.topophone.core.model.project.HeightSystem
import es.topophone.core.model.project.ProjectCrs
import io.kotest.matchers.longs.shouldBeLessThan
import org.junit.jupiter.api.Test

/** La punta en directo y la medida con la inclinación, a 10 Hz, sin cargar el hilo principal (S016, RNF-T13). */
class TiltPerformanceTest {

    private fun tilt(i: Int) = TiltData(
        utcMillisOfDay = i * 100,
        state = TiltState.COMPENSATING,
        notification = TiltNotification.NONE,
        headingRad = 2.0,
        tiltDirectionRad = Math.toRadians((i * 7 % 360).toDouble()),
        tiltRad = Math.toRadians((i % 40).toDouble()),
        accuracyCoefficients = TiltAccuracyCoefficients(1.013, 0.993, 1.021),
    )

    private fun fix(i: Int) = GgaFix("t", 40.4168 + i * 1e-9, -3.7038, SolutionStatus.FIX, 24, 0.6, 650.123, 51.234, 1.0, "")

    private fun epoch(i: Int) =
        Epoch(i * 100, null, fix(i), GstAccuracy("t", 0.008, 0.006, 0.015), null, null, null, null, tilt(i), emptyMap(), emptyMap(), 0)

    @Test
    fun `S016 RNF-T13 la punta en directo cuesta menos de 1 ms por epoca`() {
        repeat(2_000) { LiveTip.of(fix(it), tilt(it), TiltMode.APP, 1.8) } // calentamiento del JIT

        val count = 10_000
        val started = System.nanoTime()
        repeat(count) { LiveTip.of(fix(it), tilt(it), TiltMode.APP, 1.8) }

        ((System.nanoTime() - started) / count) shouldBeLessThan 1_000_000L
    }

    @Test
    fun `S016 RNF-T13 un promediado de 60 s a 10 Hz cuesta menos de 1 ms por epoca`() {
        val count = 600
        val measurement = PointMeasurement(
            MeasurementMode.AVERAGED,
            averagingSeconds = 60,
            fixOnly = true,
            poleHeightM = 1.8,
            crs = ProjectCrs.ETRS89_UTM30,
            heightSystem = HeightSystem.ORTHOMETRIC,
            epochIntervalS = 0.1,
            startedAtMs = 0,
            tilt = TiltSetup(TiltMode.APP, verified = true),
        )

        val started = System.nanoTime()
        (0 until count).forEach { measurement.onEpoch(epoch(it), it * 100L) }

        ((System.nanoTime() - started) / count) shouldBeLessThan 1_000_000L
    }
}
