package es.topophone.core.survey

import es.topophone.core.gnss.nmea.GgaFix
import es.topophone.core.gnss.nmea.SolutionStatus
import es.topophone.core.model.point.BaseShiftVector
import es.topophone.core.model.point.TiltMode
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** La punta en directo lleva el desplazamiento activo (S020/RF-7). */
class LiveTipShiftTest {

    private val fix = GgaFix("101530.20", 40.4168, -3.7038, SolutionStatus.FIX, 24, 0.6, 650.123, 51.234, 1.0, "\$GNGGA,...")
    private val vector = BaseShiftVector(2.1, -1.7, 0.4)

    @Test
    fun `S020 RF-7 la punta en directo es la de siempre mas el vector`() {
        val plain = LiveTip.of(fix, null, TiltMode.VERTICAL, 1.8)!!

        val shifted = LiveTip.of(fix, null, TiltMode.VERTICAL, 1.8, shift = vector)!!

        shifted.ground shouldBe BaseShiftApplier.apply(plain.ground, vector)
    }

    @Test
    fun `S020 RF-7 sin desplazamiento la punta no cambia`() {
        val plain = LiveTip.of(fix, null, TiltMode.VERTICAL, 1.8)!!

        LiveTip.of(fix, null, TiltMode.VERTICAL, 1.8, shift = null) shouldBe plain
    }

    @Test
    fun `S020 RF-7 el modo del receptor tambien lleva el vector`() {
        val plain = LiveTip.of(fix, null, TiltMode.RECEIVER, 1.8)!!

        val shifted = LiveTip.of(fix, null, TiltMode.RECEIVER, 1.8, shift = vector)!!

        shifted.ground shouldBe BaseShiftApplier.apply(plain.ground, vector)
        shifted.uncompensated shouldBe plain.uncompensated
    }
}
