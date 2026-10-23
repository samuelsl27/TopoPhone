package es.topophone.core.model.point

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** Nombre siguiente, control de precisión y ajustes del recolector (S010/RF-2, RF-3, RF-9, RF-13, RF-14). */
class PointModelTest {

    @Test
    fun `S010 RF-2 el nombre siguiente aumenta el numero final conservando los ceros`() {
        PointNames.next(null) shouldBe "1"
        PointNames.next("1") shouldBe "2"
        PointNames.next("P009") shouldBe "P010"
        PointNames.next("007") shouldBe "008"
        PointNames.next("A-12") shouldBe "A-13"
    }

    @Test
    fun `S010 RF-2 al pasar a mas cifras el numero crece y sin numero final se añade 1`() {
        PointNames.next("P99") shouldBe "P100"
        PointNames.next("999") shouldBe "1000"
        PointNames.next("arqueta") shouldBe "arqueta1"
        PointNames.next("12b") shouldBe "12b1"
    }

    @Test
    fun `S010 RF-3 un nombre vacio o solo con espacios no vale y los espacios de los extremos sobran`() {
        PointNames.clean("  P12  ") shouldBe "P12"
        PointNames.isValid("   ") shouldBe false
        PointNames.isValid("P12") shouldBe true
    }

    @Test
    fun `S010 RF-13 por defecto solo FIX y sin limites de precision`() {
        PrecisionControl() shouldBe PrecisionControl(fixOnly = true, horizontalLimitM = null, verticalLimitM = null)
        CollectorSettings() shouldBe CollectorSettings(MeasurementMode.INSTANT, averagingSeconds = 5, precision = PrecisionControl())
    }

    @Test
    fun `S010 RF-14 una medida en FIX sin limites cumple y fuera de FIX no, salvo sin el control de solo FIX`() {
        PrecisionControl().check(isFix = true, horizontalM = 0.012, verticalM = 0.020).shouldBeEmpty()
        PrecisionControl().check(isFix = false, horizontalM = 0.3, verticalM = 0.5) shouldBe setOf(PrecisionFailure.NOT_FIX)
        PrecisionControl(fixOnly = false).check(isFix = false, horizontalM = 0.3, verticalM = 0.5).shouldBeEmpty()
    }

    @Test
    fun `S010 RF-14 los limites se cumplen con la precision igual o menor y se dice cual falla`() {
        val control = PrecisionControl(horizontalLimitM = 0.02, verticalLimitM = 0.03)

        control.check(isFix = true, horizontalM = 0.02, verticalM = 0.03).shouldBeEmpty()
        control.check(isFix = true, horizontalM = 0.021, verticalM = 0.03) shouldBe setOf(PrecisionFailure.HORIZONTAL)
        control.check(isFix = false, horizontalM = 0.05, verticalM = 0.04) shouldBe
            setOf(PrecisionFailure.NOT_FIX, PrecisionFailure.HORIZONTAL, PrecisionFailure.VERTICAL)
    }

    @Test
    fun `S010 RF-14 con un limite activo y sin precision del receptor la condicion no se cumple`() {
        PrecisionControl(verticalLimitM = 0.03).check(isFix = true, horizontalM = null, verticalM = null) shouldBe
            setOf(PrecisionFailure.NO_ACCURACY)
        PrecisionControl().check(isFix = true, horizontalM = null, verticalM = null).shouldBeEmpty()
    }

    @Test
    fun `S010 RF-9 el promediado va de 1 a 60 s`() {
        CollectorSettings.AVERAGING_SECONDS shouldBe 1..60
        CollectorSettings(averagingSeconds = 90).validAveragingSeconds shouldBe 60
        CollectorSettings(averagingSeconds = 0).validAveragingSeconds shouldBe 1
    }

    @Test
    fun `S016 RF-20 la inclinacion maxima esta activa por defecto con 60 grados`() {
        PrecisionControl().maxTiltRad shouldBe Math.toRadians(60.0)
        PrecisionControl.MAX_TILT_RANGE_DEG shouldBe 1..60
    }

    @Test
    fun `S016 RF-20 la inclinacion maxima no se cumple si alguna epoca la supera`() {
        val control = PrecisionControl(maxTiltRad = Math.toRadians(30.0))

        control.check(isFix = true, horizontalM = 0.01, verticalM = 0.02, tiltRad = Math.toRadians(30.0)).shouldBeEmpty()
        control.check(isFix = true, horizontalM = 0.01, verticalM = 0.02, tiltRad = Math.toRadians(30.5)) shouldBe
            setOf(PrecisionFailure.TILT)
        // Sin inclinación (jalón vertical, S016/RF-20) o con la condición desactivada, no se mira.
        control.check(isFix = true, horizontalM = 0.01, verticalM = 0.02, tiltRad = null).shouldBeEmpty()
        PrecisionControl(maxTiltRad = null).check(isFix = true, horizontalM = 0.01, verticalM = 0.02, tiltRad = 1.2).shouldBeEmpty()
    }

    @Test
    fun `S016 RF-1 los modos que usan la inclinacion son el de la app y el del receptor`() {
        TiltMode.entries.filter { it.usesTilt } shouldBe listOf(TiltMode.APP, TiltMode.RECEIVER)
    }

    @Test
    fun `S016 RF-22 una medida sin inclinacion la tiene vacia por defecto`() {
        val measurement = Measurement(
            antenna = GeodeticPosition(40.0, -3.0, 700.0),
            poleHeightM = 1.8,
            quality = PointQuality("FIX", 20, 0.6, 1.0, 0.01, 0.02, MeasurementMode.INSTANT, 1, 0, fromReplay = false),
            rawNmea = "",
            outOfTolerance = emptySet(),
            measuredFromMs = 0,
            measuredToMs = 0,
            coordinates = PointCoordinates(GeodeticPosition(40.0, -3.0, 698.066), null, null, null),
        )

        measurement.tilt shouldBe null
    }
}
