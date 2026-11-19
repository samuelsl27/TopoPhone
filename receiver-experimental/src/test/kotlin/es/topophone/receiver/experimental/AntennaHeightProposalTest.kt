package es.topophone.receiver.experimental

import es.topophone.core.model.point.TiltMode
import io.kotest.matchers.shouldBe
import org.junit.Test

/** S044/RF-18: la propuesta de altura de antena según el modo de inclinación; plan D10. */
class AntennaHeightProposalTest {

    @Test
    fun `S044 RF-18 con la posicion compensada por el receptor se propone la altura del jalon activo`() {
        AntennaHeightProposal.of(TiltMode.RECEIVER, poleHeightM = 1.8) shouldBe AntennaHeightProposal(1.8, warnDoubleCount = false)
        AntennaHeightProposal.of(TiltMode.RECEIVER, poleHeightM = 1.95) shouldBe AntennaHeightProposal(1.95, warnDoubleCount = false)
    }

    @Test
    fun `S044 RF-18 con cualquier otro modo se propone cero y se avisa de la doble cuenta`() {
        AntennaHeightProposal.of(TiltMode.APP, poleHeightM = 1.8) shouldBe AntennaHeightProposal(0.0, warnDoubleCount = true)
        AntennaHeightProposal.of(TiltMode.VERTICAL, poleHeightM = 2.0) shouldBe AntennaHeightProposal(0.0, warnDoubleCount = true)
    }

    @Test
    fun `S044 RF-18 sin jalon activo con el modo compensado se propone cero sin aviso`() {
        AntennaHeightProposal.of(TiltMode.RECEIVER, poleHeightM = null) shouldBe AntennaHeightProposal(0.0, warnDoubleCount = false)
    }

    @Test
    fun `S044 RF-18 una altura que no es un numero finito no se propone`() {
        AntennaHeightProposal.of(TiltMode.RECEIVER, poleHeightM = Double.NaN) shouldBe AntennaHeightProposal(0.0, warnDoubleCount = false)
        AntennaHeightProposal.of(TiltMode.RECEIVER, poleHeightM = Double.POSITIVE_INFINITY).valueM shouldBe 0.0
    }
}
