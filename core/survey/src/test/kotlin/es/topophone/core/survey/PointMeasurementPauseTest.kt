package es.topophone.core.survey

import es.topophone.core.gnss.nmea.Epoch
import es.topophone.core.gnss.nmea.GgaFix
import es.topophone.core.gnss.nmea.GstAccuracy
import es.topophone.core.gnss.nmea.SolutionStatus
import es.topophone.core.model.point.MeasurementMode
import es.topophone.core.model.project.HeightSystem
import es.topophone.core.model.project.ProjectCrs
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.longs.shouldBeGreaterThan
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test

/** Un promediado se pausa ante un corte, reanuda solo y pregunta a los 5 min (S017/RF-8, RF-9, RF-10). */
class PointMeasurementPauseTest {

    private val calculator = ProjectPositionCalculator()
    private val start = 1_000_000L

    private fun epoch(index: Int, status: SolutionStatus = SolutionStatus.FIX): Epoch {
        val utc = 36_930_000 + index * 1_000
        return Epoch(
            utcMillisOfDay = utc,
            date = null,
            gga = GgaFix("t$index", 40.41680, -3.70371, status, 24, 0.6, 650.123, 51.234, 1.0, "\$GNGGA,$index*00"),
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

    private fun measurement(mode: MeasurementMode = MeasurementMode.AVERAGED, seconds: Int = 10) = PointMeasurement(
        mode,
        seconds,
        fixOnly = true,
        poleHeightM = 1.8,
        crs = ProjectCrs.ETRS89_UTM30,
        heightSystem = HeightSystem.ORTHOMETRIC,
        epochIntervalS = 1.0,
        startedAtMs = start,
        calculator = calculator,
    )

    /** La época `index` llega a su hora, con un [gap] de más: lo que dura un corte antes de ella. */
    private fun PointMeasurement.feed(index: Int, gapMs: Long = 0, status: SolutionStatus = SolutionStatus.FIX) =
        onEpoch(epoch(index, status), start + index * 1_000L + gapMs)

    @Test
    fun `S017 RF-8 sin epocas en 2 s un promediado se pausa una sola vez y conserva lo reunido`() {
        val m = measurement()
        (0 until 3).forEach { m.feed(it) }
        val lastAt = start + 2_000L

        m.onTick(lastAt + 1_999).shouldBeNull()
        m.onTick(lastAt + 2_000) shouldBe MeasurementUpdate.Paused
        m.paused shouldBe true
        m.onTick(lastAt + 2_250).shouldBeNull()
        m.onTick(lastAt + 60_000).shouldBeNull()

        val progress = m.feed(3, gapMs = 60_000).filterIsInstance<MeasurementUpdate.Progress>().single()
        progress.epochsUsed shouldBe 4
    }

    @Test
    fun `S017 RF-9 al volver los datos se reanuda sola y el tiempo del corte no cuenta`() {
        val m = measurement(seconds = 10)
        (0 until 3).forEach { m.feed(it) }
        m.onTick(start + 2_000L + 2_000) shouldBe MeasurementUpdate.Paused

        val resumed = m.feed(3, gapMs = 90_000)
        resumed.first() shouldBe MeasurementUpdate.Resumed
        m.paused shouldBe false
        val done = (4 until 10).flatMap { m.feed(it, gapMs = 90_000) }.filterIsInstance<MeasurementUpdate.Done>().single().result

        // 10 épocas de 1 s: el corte de 90 s no suma.
        done.epochsUsed shouldBe 10
        done.averagedTimeS!! shouldBe (10.0 plusOrMinus 1e-9)
        (done.measuredToMs - done.measuredFromMs) shouldBeGreaterThan 90_000L
    }

    @Test
    fun `S017 RF-9 una epoca sin posicion tambien reanuda`() {
        val m = measurement()
        (0 until 3).forEach { m.feed(it) }
        m.onTick(start + 2_000L + 2_000) shouldBe MeasurementUpdate.Paused

        val updates = m.feed(3, gapMs = 10_000, status = SolutionStatus.FLOAT)

        updates shouldContain MeasurementUpdate.Resumed
    }

    @Test
    fun `S017 RF-9 sin corte no hay pausa ni reanudacion`() {
        val m = measurement()

        val updates = (0 until 10).flatMap { m.feed(it) }

        updates shouldNotContain MeasurementUpdate.Paused
        updates shouldNotContain MeasurementUpdate.Resumed
    }

    @Test
    fun `S017 RF-10 a los 5 min de corte se pregunta una vez y seguir esperando pregunta 5 min mas tarde`() {
        val m = measurement()
        (0 until 3).forEach { m.feed(it) }
        val lastAt = start + 2_000L
        m.onTick(lastAt + 2_000) shouldBe MeasurementUpdate.Paused

        m.onTick(lastAt + 299_999).shouldBeNull()
        m.onTick(lastAt + 300_000) shouldBe MeasurementUpdate.LongPause
        m.onTick(lastAt + 300_250).shouldBeNull()

        m.keepWaiting(lastAt + 310_000)
        m.onTick(lastAt + 310_000 + 299_999).shouldBeNull()
        m.onTick(lastAt + 310_000 + 300_000) shouldBe MeasurementUpdate.LongPause
    }

    @Test
    fun `S017 RF-10 si vuelven los datos la pregunta se rearma para el proximo corte`() {
        val m = measurement()
        (0 until 3).forEach { m.feed(it) }
        val lastAt = start + 2_000L
        m.onTick(lastAt + 300_000)
        m.onTick(lastAt + 300_000) shouldBe MeasurementUpdate.LongPause
        m.feed(3, gapMs = 301_000).first() shouldBe MeasurementUpdate.Resumed

        val secondCut = start + 3_000L + 301_000
        m.onTick(secondCut + 2_000) shouldBe MeasurementUpdate.Paused
        m.onTick(secondCut + 300_000) shouldBe MeasurementUpdate.LongPause
    }

    @Test
    fun `S017 RF-8 la medida instantanea sigue cancelandose a los 2 s`() {
        val m = measurement(MeasurementMode.INSTANT)

        m.onTick(start + 2_000).shouldBeInstanceOf<MeasurementUpdate.Failed>().reason shouldBe MeasurementFailure.NO_DATA
        m.onTick(start + 2_250).shouldBeNull()
    }

    @Test
    fun `S017 RF-8 una medida cancelada no avisa de nada mas`() {
        val m = measurement()
        (0 until 3).forEach { m.feed(it) }

        m.cancel()

        m.onTick(start + 600_000).shouldBeNull()
        m.feed(3).shouldBeEmpty()
    }
}
