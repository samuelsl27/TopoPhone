package es.topophone.core.survey

import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** Dónde se dibuja la burbuja del nivel vertical y qué parte del radio es la banda de la tolerancia (S019/RF-7). */
class LevelViewTest {

    @Test
    fun `S019 RF-7 en la cota la burbuja esta en el centro`() {
        LevelView.bubble(dzM = 0.0, radiusM = 0.2) shouldBe 0.0
    }

    @Test
    fun `S019 RF-7 en desmonte la burbuja sube y en terraplen baja`() {
        LevelView.bubble(dzM = 0.1, radiusM = 0.2) shouldBe (0.5 plusOrMinus 1e-12)
        LevelView.bubble(dzM = -0.1, radiusM = 0.2) shouldBe (-0.5 plusOrMinus 1e-12)
        LevelView.bubble(dzM = 3.25, radiusM = 5.0) shouldBe (0.65 plusOrMinus 1e-12)
        LevelView.bubble(dzM = -40.0, radiusM = 50.0) shouldBe (-0.8 plusOrMinus 1e-12)
    }

    @Test
    fun `S019 RF-7 una burbuja fuera del radio visible se queda en el borde y no se pierde`() {
        LevelView.bubble(dzM = 3.0, radiusM = 2.0) shouldBe 1.0
        LevelView.bubble(dzM = -3.0, radiusM = 2.0) shouldBe -1.0
        LevelView.bubble(dzM = Double.POSITIVE_INFINITY, radiusM = 2.0) shouldBe 1.0
    }

    @Test
    fun `S019 RF-7 con una diferencia no valida la burbuja va al centro`() {
        LevelView.bubble(dzM = Double.NaN, radiusM = 2.0) shouldBe 0.0
    }

    @Test
    fun `S019 RF-7 la banda de la tolerancia es una fraccion del radio y no pasa del borde`() {
        LevelView.bandFraction(toleranceM = 0.03, radiusM = 0.05) shouldBe (0.6 plusOrMinus 1e-12)
        LevelView.bandFraction(toleranceM = 0.03, radiusM = 5.0) shouldBe (0.006 plusOrMinus 1e-12)
        LevelView.bandFraction(toleranceM = 1.0, radiusM = 0.5) shouldBe 1.0
    }
}
