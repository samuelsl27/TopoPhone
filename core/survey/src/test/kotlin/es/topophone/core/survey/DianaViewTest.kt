package es.topophone.core.survey

import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import kotlin.math.PI
import kotlin.math.hypot
import org.junit.jupiter.api.Test

/** Dónde se dibuja la punta en la diana y hacia dónde apunta la flecha (S018/RF-7, RF-15, RF-17). */
class DianaViewTest {

    @Test
    fun `S018 RF-17 con el norte arriba la punta al este va a la derecha y al norte, arriba`() {
        val east = DianaView.position(0.1, 0.0, radiusM = 0.2, headingRad = null)
        val north = DianaView.position(0.0, 0.1, radiusM = 0.2, headingRad = null)

        east.x shouldBe (0.5 plusOrMinus 1e-12)
        east.y shouldBe (0.0 plusOrMinus 1e-12)
        north.x shouldBe (0.0 plusOrMinus 1e-12)
        north.y shouldBe (0.5 plusOrMinus 1e-12)
    }

    @Test
    fun `S018 RF-17 con el rumbo del usuario hacia arriba lo que esta delante queda arriba`() {
        // El usuario mira al este: la punta al este de la diana, es decir, delante, sale arriba.
        val ahead = DianaView.position(0.1, 0.0, radiusM = 0.2, headingRad = PI / 2)
        // Y el norte, que queda a su izquierda, sale a la izquierda.
        val left = DianaView.position(0.0, 0.1, radiusM = 0.2, headingRad = PI / 2)

        ahead.x shouldBe (0.0 plusOrMinus 1e-12)
        ahead.y shouldBe (0.5 plusOrMinus 1e-12)
        left.x shouldBe (-0.5 plusOrMinus 1e-12)
        left.y shouldBe (0.0 plusOrMinus 1e-12)
    }

    @Test
    fun `S018 RF-15 una punta fuera del radio visible se queda en el borde y la punta no se pierde`() {
        val far = DianaView.position(1.0, 1.0, radiusM = 0.2, headingRad = null)

        hypot(far.x, far.y) shouldBe (1.0 plusOrMinus 1e-12)
        far.x shouldBe (far.y plusOrMinus 1e-12)
    }

    @Test
    fun `S018 RF-15 el circulo de la tolerancia es la fraccion del radio y nunca pasa del borde`() {
        DianaView.toleranceFraction(0.025, 0.1) shouldBe (0.25 plusOrMinus 1e-12)
        DianaView.toleranceFraction(0.5, 0.2) shouldBe 1.0
    }

    @Test
    fun `S018 RF-7 la flecha apunta delante, a la derecha, atras o a la izquierda`() {
        DianaView.arrowAngleRad(aheadM = 1.0, rightM = 0.0) shouldBe (0.0 plusOrMinus 1e-12)
        DianaView.arrowAngleRad(aheadM = 0.0, rightM = 1.0) shouldBe (PI / 2 plusOrMinus 1e-12)
        DianaView.arrowAngleRad(aheadM = 0.0, rightM = -1.0) shouldBe (-PI / 2 plusOrMinus 1e-12)
        DianaView.arrowAngleRad(aheadM = -1.0, rightM = 0.0) shouldBe (PI plusOrMinus 1e-12)
    }

    @Test
    fun `S018 RF-7 la flecha en grados enteros respecto al rumbo del usuario`() {
        DianaView.arrowDegrees(aheadM = 1.0, rightM = 1.0) shouldBe 45
        DianaView.arrowDegrees(aheadM = 1.0, rightM = -1.0) shouldBe -45
        DianaView.arrowDegrees(aheadM = -1.0, rightM = 0.0) shouldBe 180
    }
}
