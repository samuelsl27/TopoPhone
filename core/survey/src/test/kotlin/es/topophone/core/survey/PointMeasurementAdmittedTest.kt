package es.topophone.core.survey

import es.topophone.core.gnss.nmea.Epoch
import es.topophone.core.gnss.nmea.GgaFix
import es.topophone.core.gnss.nmea.GstAccuracy
import es.topophone.core.gnss.nmea.SolutionStatus
import es.topophone.core.model.point.AdmittedSolutions
import es.topophone.core.model.point.MeasurementMode
import es.topophone.core.model.point.PrecisionControl
import es.topophone.core.model.point.PrecisionFailure
import es.topophone.core.model.point.SolutionType
import es.topophone.core.model.project.HeightSystem
import es.topophone.core.model.project.ProjectCrs
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** La medida de un punto con los tipos de solución admitidos (S047/RF-8). */
class PointMeasurementAdmittedTest {

    private fun epoch(index: Int, status: SolutionStatus, hasPosition: Boolean = true): Epoch = Epoch(
        utcMillisOfDay = 36_930_000 + index * 1_000,
        date = null,
        gga = GgaFix(
            "t$index",
            if (hasPosition) 40.416_80 else Double.NaN,
            -3.703_71,
            status,
            24,
            0.6,
            650.123,
            51.234,
            1.0,
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

    private fun instant(admitted: AdmittedSolutions) = PointMeasurement(
        MeasurementMode.INSTANT,
        5,
        admitted,
        poleHeightM = 1.8,
        crs = ProjectCrs.ETRS89_UTM30,
        heightSystem = HeightSystem.ORTHOMETRIC,
        epochIntervalS = 1.0,
        startedAtMs = 1_000_000L,
    )

    private fun PointMeasurement.feed(epoch: Epoch) = onEpoch(epoch, 1_000_000L + (epoch.utcMillisOfDay - 36_930_000))

    private fun List<MeasurementUpdate>.done() = filterIsInstance<MeasurementUpdate.Done>().single().result

    @Test
    fun `S047 RF-8 con FLOAT admitido la instantanea mide con la primera epoca FLOAT`() {
        val result = instant(AdmittedSolutions.of(setOf(SolutionType.FLOAT))).feed(epoch(0, SolutionStatus.FLOAT)).done()

        result.solution shouldBe SolutionStatus.FLOAT
        result.epochsUsed shouldBe 1
        PrecisionControl(admitted = AdmittedSolutions.of(setOf(SolutionType.FLOAT)))
            .check(SolutionTypes.of(result.solution), result.horizontalM, result.verticalM).shouldBeEmpty()
    }

    @Test
    fun `S047 RF-8 una epoca sin posicion no es la primera completa y se descarta`() {
        val m = instant(AdmittedSolutions.ALL)

        m.feed(epoch(0, SolutionStatus.NONE, hasPosition = false)).filterIsInstance<MeasurementUpdate.Done>().shouldBeEmpty()
        val result = m.feed(epoch(1, SolutionStatus.SINGLE)).done()

        result.solution shouldBe SolutionStatus.SINGLE
        result.epochsDiscarded shouldBe 1
    }

    @Test
    fun `S047 RF-6 y RF-7 con solo FIX la instantanea en FLOAT mide y el control la marca como no admitida`() {
        val admitted = AdmittedSolutions.FIX_ONLY
        val result = instant(admitted).feed(epoch(0, SolutionStatus.FLOAT)).done()

        result.solution shouldBe SolutionStatus.FLOAT
        PrecisionControl(admitted = admitted).check(SolutionTypes.of(result.solution), result.horizontalM, result.verticalM) shouldBe
            setOf(PrecisionFailure.NOT_FIX)
    }

    @Test
    fun `S047 RF-18 una instantanea en DGPS se comprueba como SINGLE`() {
        val result = instant(AdmittedSolutions.of(setOf(SolutionType.FIX, SolutionType.SINGLE))).feed(epoch(0, SolutionStatus.DGPS)).done()

        result.solution shouldBe SolutionStatus.DGPS
        SolutionTypes.of(result.solution) shouldBe SolutionType.SINGLE
    }
}
