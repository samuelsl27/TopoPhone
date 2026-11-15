package es.topophone.core.model.point

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** La fuente de la posición de un punto y cuándo cuenta como simulada (S022/RF-7, RF-11, RF-19). */
class PositionSourceTest {

    @Test
    fun `S022 RF-19 reproduccion, virtual y ubicacion simulada son simuladas y receptor y movil no`() {
        PositionSource.REPLAY.simulated shouldBe true
        PositionSource.VIRTUAL.simulated shouldBe true
        PositionSource.PHONE_MOCK.simulated shouldBe true
        PositionSource.RECEIVER.simulated shouldBe false
        PositionSource.PHONE.simulated shouldBe false
    }

    @Test
    fun `S022 RF-11 la fuente guardada se lee de vuelta y la desconocida cae en el receptor`() {
        for (source in PositionSource.entries) {
            PositionSource.fromStored(source.name, fromReplay = source.simulated) shouldBe source
        }
        PositionSource.fromStored("OTRA", fromReplay = false) shouldBe PositionSource.RECEIVER
    }

    @Test
    fun `S022 RF-11 un punto anterior sin fuente es del receptor o de una reproduccion`() {
        PositionSource.fromStored(null, fromReplay = false) shouldBe PositionSource.RECEIVER
        PositionSource.fromStored(null, fromReplay = true) shouldBe PositionSource.REPLAY
        PositionSource.fromStored(null, fromReplay = null) shouldBe PositionSource.RECEIVER
    }

    @Test
    fun `S022 RF-19 la calidad por defecto viene del receptor y lleva su fuente`() {
        val quality = PointQuality("FIX", 20, 0.8, 1.0, 0.01, 0.02, MeasurementMode.INSTANT, 1, 0, fromReplay = false)

        quality.source shouldBe PositionSource.RECEIVER
        quality.copy(source = PositionSource.VIRTUAL).source shouldBe PositionSource.VIRTUAL
    }
}
