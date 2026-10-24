package es.topophone.core.survey

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** La escala del nivel vertical: pasos 1-2-5 por cada potencia de diez que dejan a la vista la burbuja y la banda (S019/RF-7). */
class LevelScaleTest {

    private val tolerance = 0.03

    @Test
    fun `S019 RF-7 el radio es el menor paso que deja dentro la burbuja con un 25 por ciento de margen`() {
        LevelScale.radius(previous = null, dzAbsM = 0.03, toleranceM = tolerance) shouldBe 0.05
        LevelScale.radius(previous = null, dzAbsM = 0.05, toleranceM = tolerance) shouldBe 0.1
        LevelScale.radius(previous = null, dzAbsM = 0.10, toleranceM = tolerance) shouldBe 0.2
        LevelScale.radius(previous = null, dzAbsM = 0.20, toleranceM = tolerance) shouldBe 0.5
        LevelScale.radius(previous = null, dzAbsM = 0.40, toleranceM = tolerance) shouldBe 0.5
        LevelScale.radius(previous = null, dzAbsM = 0.41, toleranceM = tolerance) shouldBe 1.0
    }

    @Test
    fun `S019 RF-7 la escala sigue subiendo en pasos de 1, 2 y 5 con metros y decenas de metros`() {
        LevelScale.radius(previous = null, dzAbsM = 1.0, toleranceM = tolerance) shouldBe 2.0
        LevelScale.radius(previous = null, dzAbsM = 3.25, toleranceM = tolerance) shouldBe 5.0
        LevelScale.radius(previous = null, dzAbsM = 6.0, toleranceM = tolerance) shouldBe 10.0
        LevelScale.radius(previous = null, dzAbsM = 12.5, toleranceM = tolerance) shouldBe 20.0
        LevelScale.radius(previous = null, dzAbsM = 40.0, toleranceM = tolerance) shouldBe 50.0
        LevelScale.radius(previous = null, dzAbsM = 41.0, toleranceM = tolerance) shouldBe 100.0
        LevelScale.radius(previous = null, dzAbsM = 1_000.0, toleranceM = tolerance) shouldBe 2_000.0
    }

    @Test
    fun `S019 RF-7 los pasos son 1, 2 y 5 por cada potencia de diez desde 5 cm`() {
        LevelScale.STEPS.take(13) shouldBe listOf(0.05, 0.1, 0.2, 0.5, 1.0, 2.0, 5.0, 10.0, 20.0, 50.0, 100.0, 200.0, 500.0)
        LevelScale.STEPS.zipWithNext().all { (a, b) -> b > a } shouldBe true
    }

    @Test
    fun `S019 RF-7 nunca baja de 5 cm y la banda de la tolerancia siempre cabe`() {
        LevelScale.radius(previous = null, dzAbsM = 0.0, toleranceM = 0.01) shouldBe 0.05
        LevelScale.radius(previous = null, dzAbsM = 0.0, toleranceM = 0.03) shouldBe 0.05
        LevelScale.radius(previous = null, dzAbsM = 0.0, toleranceM = 0.05) shouldBe 0.1
        LevelScale.radius(previous = null, dzAbsM = 0.0, toleranceM = 0.5) shouldBe 1.0
        LevelScale.radius(previous = null, dzAbsM = 0.1, toleranceM = 1.0) shouldBe 2.0
    }

    @Test
    fun `S019 RF-7 con una diferencia enorme o no valida la escala se queda en un paso finito`() {
        LevelScale.radius(previous = null, dzAbsM = Double.POSITIVE_INFINITY, toleranceM = tolerance) shouldBe LevelScale.STEPS.last()
        LevelScale.radius(previous = null, dzAbsM = Double.NaN, toleranceM = tolerance) shouldBe 0.05
    }

    @Test
    fun `S019 RF-7 al alejarse la burbuja la escala sube enseguida`() {
        LevelScale.radius(previous = 0.1, dzAbsM = 0.09, toleranceM = tolerance) shouldBe 0.2
        LevelScale.radius(previous = 2.0, dzAbsM = 30.0, toleranceM = tolerance) shouldBe 50.0
    }

    @Test
    fun `S019 RF-7 al acercarse la escala solo baja cuando la burbuja cabe en el 80 por ciento del paso menor`() {
        // Para bajar de 0,2 a 0,1 hace falta que la burbuja con su margen (1,25 × diferencia) quepa en 0,08 m: a 0,064 m o menos.
        LevelScale.radius(previous = 0.2, dzAbsM = 0.07, toleranceM = tolerance) shouldBe 0.2
        LevelScale.radius(previous = 0.2, dzAbsM = 0.055, toleranceM = tolerance) shouldBe 0.1
        LevelScale.radius(previous = 0.2, dzAbsM = 0.01, toleranceM = tolerance) shouldBe 0.05
        // Lo mismo con metros: de 5 a 2 hace falta 1,25 × diferencia ≤ 1,6, o sea 1,28 m o menos.
        LevelScale.radius(previous = 5.0, dzAbsM = 1.5, toleranceM = tolerance) shouldBe 5.0
        LevelScale.radius(previous = 5.0, dzAbsM = 1.2, toleranceM = tolerance) shouldBe 2.0
    }

    @Test
    fun `S019 RF-7 al bajar de varios pasos de golpe se queda en el menor que cabe holgado`() {
        LevelScale.radius(previous = 50.0, dzAbsM = 0.5, toleranceM = tolerance) shouldBe 1.0
        LevelScale.radius(previous = 50.0, dzAbsM = 0.01, toleranceM = tolerance) shouldBe 0.05
    }

    @Test
    fun `S019 RF-7 la escala no baja de la banda de la tolerancia aunque la burbuja quepa`() {
        // Con tolerancia de 1 m la banda con su margen del 20 % necesita 1,2 m: el paso mínimo es 2 m.
        LevelScale.radius(previous = 5.0, dzAbsM = 0.0, toleranceM = 1.0) shouldBe 2.0
    }

    @Test
    fun `S019 RF-7 oscilando en el borde de un paso la escala no salta de ida y vuelta`() {
        var radius: Double? = null
        val seen = mutableListOf<Double>()
        listOf(0.082, 0.078, 0.079, 0.083, 0.077, 0.084).forEach { dz ->
            radius = LevelScale.radius(radius, dz, tolerance)
            seen += radius!!
        }

        seen.toSet().size shouldBe 1
    }

    @Test
    fun `S019 RF-7 oscilando en el borde de un paso de metros la escala tampoco salta`() {
        var radius: Double? = null
        val seen = mutableListOf<Double>()
        listOf(4.1, 3.95, 4.05, 3.8, 4.15, 3.9).forEach { dz ->
            radius = LevelScale.radius(radius, dz, tolerance)
            seen += radius!!
        }

        seen.toSet().size shouldBe 1
    }
}
