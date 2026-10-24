package es.topophone.core.survey

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** La escala de la diana métrica: pasos 1-2-5 que dejan a la vista la punta y el círculo de la tolerancia (S018/RF-16). */
class DianaScaleTest {

    @Test
    fun `S018 RF-16 el radio es el menor paso que deja dentro la punta con un 25 por ciento de margen`() {
        DianaScale.radius(previous = null, tipDistanceM = 0.03, toleranceM = 0.025) shouldBe 0.05
        DianaScale.radius(previous = null, tipDistanceM = 0.05, toleranceM = 0.025) shouldBe 0.1
        DianaScale.radius(previous = null, tipDistanceM = 0.12, toleranceM = 0.025) shouldBe 0.2
        DianaScale.radius(previous = null, tipDistanceM = 0.17, toleranceM = 0.025) shouldBe 0.5
        DianaScale.radius(previous = null, tipDistanceM = 0.40, toleranceM = 0.025) shouldBe 0.5
    }

    @Test
    fun `S018 RF-16 nunca baja de 5 cm y el circulo de la tolerancia siempre cabe`() {
        DianaScale.radius(previous = null, tipDistanceM = 0.0, toleranceM = 0.01) shouldBe 0.05
        DianaScale.radius(previous = null, tipDistanceM = 0.0, toleranceM = 0.1) shouldBe 0.2
        DianaScale.radius(previous = null, tipDistanceM = 0.0, toleranceM = 0.5) shouldBe 1.0
        DianaScale.radius(previous = null, tipDistanceM = 0.2, toleranceM = 1.0) shouldBe 2.0
    }

    @Test
    fun `S018 RF-16 al alejarse la punta la escala sube enseguida`() {
        DianaScale.radius(previous = 0.1, tipDistanceM = 0.09, toleranceM = 0.025) shouldBe 0.2
    }

    @Test
    fun `S018 RF-16 al acercarse la escala solo baja cuando la punta cabe en el 80 por ciento del paso menor`() {
        // Para bajar de 0,2 a 0,1 hace falta que la punta con su margen (1,25 × distancia) quepa en 0,08 m: a 0,064 m o menos.
        DianaScale.radius(previous = 0.2, tipDistanceM = 0.07, toleranceM = 0.025) shouldBe 0.2
        DianaScale.radius(previous = 0.2, tipDistanceM = 0.055, toleranceM = 0.025) shouldBe 0.1
        DianaScale.radius(previous = 0.2, tipDistanceM = 0.01, toleranceM = 0.025) shouldBe 0.05
    }

    @Test
    fun `S018 RF-16 oscilando en el borde de un paso la escala no salta de ida y vuelta`() {
        var radius: Double? = null
        val seen = mutableListOf<Double>()
        listOf(0.082, 0.078, 0.079, 0.083, 0.077, 0.084).forEach { d ->
            radius = DianaScale.radius(radius, d, 0.025)
            seen += radius!!
        }

        seen.toSet().size shouldBe 1
    }
}
