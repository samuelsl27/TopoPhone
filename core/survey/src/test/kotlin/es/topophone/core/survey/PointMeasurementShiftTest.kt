package es.topophone.core.survey

import es.topophone.core.gnss.nmea.Epoch
import es.topophone.core.gnss.nmea.GgaFix
import es.topophone.core.gnss.nmea.GstAccuracy
import es.topophone.core.gnss.nmea.SolutionStatus
import es.topophone.core.model.point.AdmittedSolutions
import es.topophone.core.model.point.BaseShiftVector
import es.topophone.core.model.point.MeasurementMode
import es.topophone.core.model.project.HeightSystem
import es.topophone.core.model.project.ProjectCrs
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** La medida lleva el desplazamiento con el que empezó hasta el final (S020/RF-7, RF-8, RF-9). */
class PointMeasurementShiftTest {

    private val calculator = ProjectPositionCalculator()
    private val vector = BaseShiftVector(2.1, -1.7, 0.4)

    private fun epoch(index: Int): Epoch {
        val utc = 36_930_000 + index * 1_000
        return Epoch(
            utcMillisOfDay = utc,
            date = null,
            gga = GgaFix("t$index", 40.416_80, -3.703_71, SolutionStatus.FIX, 24, 0.6, 650.123, 51.234, 1.0, "\$GNGGA,$index*00"),
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

    private fun measurement(mode: MeasurementMode, seconds: Int = 3, shift: BaseShiftVector? = null) = PointMeasurement(
        mode,
        seconds,
        AdmittedSolutions.FIX_ONLY,
        poleHeightM = 1.8,
        ProjectCrs.ETRS89_UTM30,
        HeightSystem.ORTHOMETRIC,
        1.0,
        startedAtMs = 1_000_000L,
        calculator,
        shift = shift,
    )

    private fun PointMeasurement.feed(epoch: Epoch) = onEpoch(epoch, 1_000_000L + (epoch.utcMillisOfDay - 36_930_000))

    private fun List<MeasurementUpdate>.done() = filterIsInstance<MeasurementUpdate.Done>().single().result

    @Test
    fun `S020 RF-7 el suelo y el SC de la medida llevan el vector, y la punta sin desplazar queda aparte`() {
        val plain = measurement(MeasurementMode.INSTANT).feed(epoch(0)).done()

        val shifted = measurement(MeasurementMode.INSTANT, shift = vector).feed(epoch(0)).done()

        shifted.unshiftedGround shouldBe plain.ground
        shifted.ground shouldBe BaseShiftApplier.apply(plain.ground, vector)
        shifted.position shouldBe calculator.fromGround(shifted.ground, ProjectCrs.ETRS89_UTM30, HeightSystem.ORTHOMETRIC)
        shifted.shift shouldBe vector
    }

    @Test
    fun `S020 RF-8 la antena queda bruta con el desplazamiento`() {
        val plain = measurement(MeasurementMode.INSTANT).feed(epoch(0)).done()

        val shifted = measurement(MeasurementMode.INSTANT, shift = vector).feed(epoch(0)).done()

        shifted.antenna shouldBe plain.antenna
    }

    @Test
    fun `S020 RF-9 la medida promediada termina con el vector con que empezo`() {
        val m = measurement(MeasurementMode.AVERAGED, seconds = 3, shift = vector)

        m.feed(epoch(0))
        m.feed(epoch(1))
        val result = m.feed(epoch(2)).done()

        result.shift shouldBe vector
        result.ground shouldBe BaseShiftApplier.apply(result.unshiftedGround, vector)
    }

    @Test
    fun `S020 RF-8 sin desplazamiento no hay vector y la punta sin desplazar es la del suelo`() {
        val plain = measurement(MeasurementMode.INSTANT).feed(epoch(0)).done()

        plain.shift shouldBe null
        plain.unshiftedGround shouldBe plain.ground
    }
}
