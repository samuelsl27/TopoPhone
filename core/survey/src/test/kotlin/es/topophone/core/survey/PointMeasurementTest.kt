package es.topophone.core.survey

import es.topophone.core.gnss.nmea.Epoch
import es.topophone.core.gnss.nmea.GgaFix
import es.topophone.core.gnss.nmea.GstAccuracy
import es.topophone.core.gnss.nmea.SolutionStatus
import es.topophone.core.model.point.MeasurementMode
import es.topophone.core.model.project.HeightSystem
import es.topophone.core.model.project.ProjectCrs
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.doubles.shouldBeLessThan
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.math.BigDecimal
import java.math.MathContext
import kotlin.math.cos
import org.junit.jupiter.api.Test

/** Medida de un punto a partir de las épocas del receptor (S010/RF-8 a RF-10, RF-12, RF-17, RF-18). */
class PointMeasurementTest {

    private val calculator = ProjectPositionCalculator()

    private fun epoch(
        index: Int,
        intervalMs: Int = 1_000,
        lat: Double = 40.416_80,
        lon: Double = -3.703_71,
        status: SolutionStatus = SolutionStatus.FIX,
        altitude: Double = 650.123,
        separation: Double = 51.234,
        sigma: Double = 0.008,
    ): Epoch {
        val utc = 36_930_000 + index * intervalMs
        return Epoch(
            utcMillisOfDay = utc,
            date = null,
            gga = GgaFix("t$index", lat, lon, status, 24, 0.6, altitude, separation, 1.0, "\$GNGGA,$index*00"),
            gst = GstAccuracy("t$index", sigma, sigma * 0.75, sigma * 2, "\$GNGST,$index*00"),
            rmc = null,
            vtg = null,
            zda = null,
            basePosition = null,
            tilt = null,
            gsa = emptyMap(),
            gsv = emptyMap(),
            completedAtMs = 1_000_000L + utc,
        )
    }

    private fun measurement(
        mode: MeasurementMode,
        seconds: Int = 5,
        fixOnly: Boolean = true,
        intervalS: Double = 1.0,
        crs: ProjectCrs = ProjectCrs.ETRS89_UTM30,
    ) = PointMeasurement(
        mode,
        seconds,
        fixOnly,
        poleHeightM = 1.8,
        crs,
        HeightSystem.ORTHOMETRIC,
        intervalS,
        startedAtMs = 1_000_000L,
        calculator,
    )

    private fun List<MeasurementUpdate>.done() = filterIsInstance<MeasurementUpdate.Done>().single().result

    @Test
    fun `S010 RF-8 RF-17 la medida instantanea es la primera epoca con posicion, tal como llega`() {
        val m = measurement(MeasurementMode.INSTANT)

        m.onEpoch(epoch(0, status = SolutionStatus.NONE, lat = Double.NaN)).filterIsInstance<MeasurementUpdate.Done>().shouldBeEmpty()
        val result = m.onEpoch(epoch(1)).done()

        result.antenna.latitudeDeg shouldBe 40.416_80
        result.antenna.longitudeDeg shouldBe -3.703_71
        result.antenna.ellipsoidalHeightM.shouldNotBeNull() shouldBe (650.123 + 51.234 plusOrMinus 1e-9)
        result.epochsUsed shouldBe 1
        result.epochsDiscarded shouldBe 1
        result.rawNmea shouldBe "\$GNGGA,1*00\n\$GNGST,1*00"
    }

    @Test
    fun `S010 RF-17 RF-18 el punto del suelo y el SC del proyecto salen como en S008 con la altura del jalon`() {
        val e = epoch(0)
        val result = measurement(MeasurementMode.INSTANT).onEpoch(e).done()
        val expected = calculator.compute(e.gga!!, 1.8, ProjectCrs.ETRS89_UTM30, HeightSystem.ORTHOMETRIC).shouldNotBeNull()

        result.position.easting shouldBe expected.easting
        result.position.northing shouldBe expected.northing
        result.position.height shouldBe expected.height
        result.ground.latitudeDeg shouldBe 40.416_80
        result.ground.ellipsoidalHeightM.shouldNotBeNull() shouldBe (650.123 + 51.234 - 1.8 - 0.134 plusOrMinus 1e-9)
        result.poleHeightM shouldBe 1.8
        result.solution shouldBe SolutionStatus.FIX
        result.horizontalM.shouldNotBeNull() shouldBe (0.010 plusOrMinus 1e-12)
        result.verticalM shouldBe 0.016
    }

    @Test
    fun `S010 RF-12 sin datos en 2 s la medida se cancela`() {
        val m = measurement(MeasurementMode.INSTANT)

        m.onTick(1_000_000L + 1_999).shouldBeNull()
        m.onTick(1_000_000L + 2_000).shouldBeInstanceOf<MeasurementUpdate.Failed>().reason shouldBe MeasurementFailure.NO_DATA
    }

    @Test
    fun `S010 RF-12 fuera del ambito del SC del proyecto no hay punto`() {
        val failed = measurement(MeasurementMode.INSTANT).onEpoch(epoch(0, lat = 28.4636, lon = -16.2518))
            .filterIsInstance<MeasurementUpdate.Failed>().single()

        failed.reason shouldBe MeasurementFailure.OUTSIDE_CRS_AREA
    }

    @Test
    fun `S010 RF-9 el promediado de 5 s a 1 Hz usa 5 epocas y va dando el progreso`() {
        val m = measurement(MeasurementMode.AVERAGED, seconds = 5)

        val progress = (0 until 4).flatMap { m.onEpoch(epoch(it)) }.filterIsInstance<MeasurementUpdate.Progress>()
        progress.map { it.elapsedS } shouldContainExactly listOf(1.0, 2.0, 3.0, 4.0)
        progress.last().requiredS shouldBe 5.0
        m.onEpoch(epoch(4)).done().epochsUsed shouldBe 5
        m.onEpoch(epoch(5)).shouldBeEmpty() // terminada, no hace nada más
    }

    @Test
    fun `S010 RF-9 a 10 Hz el mismo tiempo son 50 epocas`() {
        val m = measurement(MeasurementMode.AVERAGED, seconds = 5, intervalS = 0.1)

        val updates = (0 until 50).flatMap { m.onEpoch(epoch(it, intervalMs = 100)) }

        updates.done().epochsUsed shouldBe 50
    }

    @Test
    fun `S010 RF-10 al perder el FIX se descartan esas epocas, se avisa una vez y se sigue hasta completar`() {
        val m = measurement(MeasurementMode.AVERAGED, seconds = 3)
        val statuses = listOf(SolutionStatus.FIX, SolutionStatus.FLOAT, SolutionStatus.FLOAT, SolutionStatus.FIX, SolutionStatus.FIX)

        val updates = statuses.withIndex().flatMap { (i, status) -> m.onEpoch(epoch(i, status = status)) }

        updates.filterIsInstance<MeasurementUpdate.SolutionLost>().size shouldBe 1
        val result = updates.done()
        result.epochsUsed shouldBe 3
        result.epochsDiscarded shouldBe 2
        result.solution shouldBe SolutionStatus.FIX
    }

    @Test
    fun `S010 RF-10 sin el control de solo FIX cuentan todas las epocas y la solucion es la peor`() {
        val m = measurement(MeasurementMode.AVERAGED, seconds = 3, fixOnly = false)
        val statuses = listOf(SolutionStatus.FIX, SolutionStatus.FLOAT, SolutionStatus.FIX)

        val result = statuses.withIndex().flatMap { (i, status) -> m.onEpoch(epoch(i, status = status)) }.done()

        result.epochsUsed shouldBe 3
        result.epochsDiscarded shouldBe 0
        result.solution shouldBe SolutionStatus.FLOAT
    }

    @Test
    fun `S010 RF-9 la posicion promediada coincide con la media calculada aparte a menos de 0,1 mm`() {
        val offsets = listOf(0.0, 1.3e-7, -2.1e-7, 0.7e-7, 1.9e-7, -0.4e-7, 2.6e-7, -1.1e-7, 0.2e-7, 1.4e-7)
        val epochs = offsets.mapIndexed { i, d -> epoch(i, lat = 40.416_80 + d, lon = -3.703_71 - 2 * d, altitude = 650.123 + d * 1e5) }
        val m = measurement(MeasurementMode.AVERAGED, seconds = 10)

        val result = epochs.flatMap { m.onEpoch(it) }.done()

        // Referencia: media exacta con BigDecimal.
        fun mean(values: List<Double>) =
            values.map { BigDecimal(it) }.reduce(BigDecimal::add).divide(BigDecimal(values.size), MathContext.DECIMAL128).toDouble()
        val lat = mean(epochs.map { it.gga!!.latitudeDeg })
        val lon = mean(epochs.map { it.gga!!.longitudeDeg })
        val h = mean(epochs.map { it.gga!!.altitudeM!! + it.gga!!.geoidSeparationM!! })
        (kotlin.math.abs(result.antenna.latitudeDeg - lat) * 111_000.0) shouldBeLessThan 1e-4
        (kotlin.math.abs(result.antenna.longitudeDeg - lon) * 111_000.0 * cos(Math.toRadians(lat))) shouldBeLessThan 1e-4
        kotlin.math.abs(result.antenna.ellipsoidalHeightM!! - h) shouldBeLessThan 1e-4
        result.rawNmea.lines().size shouldBe 20
        result.measuredFromMs shouldBe epochs.first().completedAtMs
        result.measuredToMs shouldBe epochs.last().completedAtMs
    }

    @Test
    fun `S010 RF-11 una medida cancelada no da nada mas`() {
        val m = measurement(MeasurementMode.AVERAGED, seconds = 3)
        m.onEpoch(epoch(0))

        m.cancel()

        (1 until 5).flatMap { m.onEpoch(epoch(it)) }.shouldBeEmpty()
        m.onTick(10_000_000L).shouldBeNull()
    }
}
