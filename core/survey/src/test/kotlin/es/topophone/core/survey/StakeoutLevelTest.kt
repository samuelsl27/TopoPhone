package es.topophone.core.survey

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** Los estados del nivel vertical (desmonte, terraplén, en cota) y sus avisos (S019/RF-6, RF-8, RF-9). */
class StakeoutLevelTest {

    private val tolerance = 0.03

    /** Recorre las diferencias de cota desde [from] (null: sin estado previo) y devuelve los estados y los avisos que salen. */
    private fun run(from: LevelState?, vararg dz: Double, tolerance: Double = this.tolerance): List<LevelStep> {
        var state = from
        return dz.map { value -> StakeoutLevel.next(state, value, tolerance).also { state = it.state } }
    }

    @Test
    fun `S019 RF-6 con la punta por encima de la cota es desmonte y por debajo terraplen`() {
        StakeoutLevel.next(null, 3.25, tolerance).state shouldBe LevelState.CUT
        StakeoutLevel.next(null, 0.031, tolerance).state shouldBe LevelState.CUT
        StakeoutLevel.next(null, -3.25, tolerance).state shouldBe LevelState.FILL
        StakeoutLevel.next(null, -0.031, tolerance).state shouldBe LevelState.FILL
    }

    @Test
    fun `S019 RF-8 con la diferencia dentro de la tolerancia esta en cota, limites incluidos`() {
        StakeoutLevel.next(null, 0.0, tolerance).state shouldBe LevelState.ON_GRADE
        StakeoutLevel.next(null, 0.03, tolerance).state shouldBe LevelState.ON_GRADE
        StakeoutLevel.next(null, -0.03, tolerance).state shouldBe LevelState.ON_GRADE
        StakeoutLevel.next(null, 0.25, 0.25).state shouldBe LevelState.ON_GRADE
        StakeoutLevel.next(null, -0.25, 0.25).state shouldBe LevelState.ON_GRADE
    }

    @Test
    fun `S019 RF-6 a cualquier distancia el estado es el mismo, con decenas de metros incluidas`() {
        StakeoutLevel.next(null, 12.5, tolerance).state shouldBe LevelState.CUT
        StakeoutLevel.next(null, -40.0, tolerance).state shouldBe LevelState.FILL
        StakeoutLevel.next(null, 1_000.0, tolerance).state shouldBe LevelState.CUT
    }

    @Test
    fun `S019 RF-9 al entrar en cota desde fuera avisa una vez`() {
        val steps = run(LevelState.CUT, 0.10, 0.05, 0.03, 0.02, 0.01, 0.0)

        steps.map { it.alert } shouldBe listOf(null, null, LevelAlert.ARRIVED, null, null, null)
        steps.map { it.state } shouldBe listOf(
            LevelState.CUT,
            LevelState.CUT,
            LevelState.ON_GRADE,
            LevelState.ON_GRADE,
            LevelState.ON_GRADE,
            LevelState.ON_GRADE,
        )
    }

    @Test
    fun `S019 RF-9 al entrar en cota desde el terraplen avisa igual`() {
        val steps = run(LevelState.FILL, -0.10, -0.04, -0.03)

        steps.map { it.alert } shouldBe listOf(null, null, LevelAlert.ARRIVED)
        steps.last().state shouldBe LevelState.ON_GRADE
    }

    @Test
    fun `S019 RF-9 al salir de la cota avisa una vez y el estado es el del lado por el que sale`() {
        run(LevelState.ON_GRADE, 0.02, 0.034, 0.04).let { steps ->
            steps.map { it.alert } shouldBe listOf(null, null, LevelAlert.LEFT)
            steps.map { it.state } shouldBe listOf(LevelState.ON_GRADE, LevelState.ON_GRADE, LevelState.CUT)
        }
        run(LevelState.ON_GRADE, -0.02, -0.034, -0.04).let { steps ->
            steps.map { it.alert } shouldBe listOf(null, null, LevelAlert.LEFT)
            steps.last().state shouldBe LevelState.FILL
        }
    }

    @Test
    fun `S019 RF-9 entre la tolerancia y 5 mm mas no cambia nada, ni al entrar ni al salir`() {
        // Dentro, hasta 0,0349 sigue en cota (el margen de 5 mm); a partir de ahi sale.
        run(LevelState.ON_GRADE, 0.031, 0.0349, 0.0351).map { it.state } shouldBe
            listOf(LevelState.ON_GRADE, LevelState.ON_GRADE, LevelState.CUT)
        // Fuera, 0,031 no basta para entrar: hay que llegar a la tolerancia.
        run(LevelState.CUT, 0.035, 0.031).map { it.state } shouldBe listOf(LevelState.CUT, LevelState.CUT)
    }

    @Test
    fun `S019 RF-9 oscilando en el borde de la tolerancia no se repite el aviso`() {
        val steps = run(LevelState.CUT, 0.031, 0.029, 0.033, 0.028, 0.034, 0.03, 0.0345)

        steps.map { it.alert } shouldBe listOf(null, LevelAlert.ARRIVED, null, null, null, null, null)
        steps.drop(1).all { it.state == LevelState.ON_GRADE } shouldBe true
    }

    @Test
    fun `S019 RF-9 sin estado previo, estar ya en cota en la primera epoca avisa de la entrada`() {
        StakeoutLevel.next(null, 0.01, tolerance) shouldBe LevelStep(LevelState.ON_GRADE, LevelAlert.ARRIVED)
        StakeoutLevel.next(null, 0.5, tolerance) shouldBe LevelStep(LevelState.CUT, null)
        StakeoutLevel.next(null, -0.5, tolerance) shouldBe LevelStep(LevelState.FILL, null)
    }

    @Test
    fun `S019 RF-9 pasar de desmonte a terraplen sin pasar por la cota no avisa`() {
        val steps = run(LevelState.CUT, 0.5, -0.5, 0.5)

        steps.map { it.alert } shouldBe listOf(null, null, null)
        steps.map { it.state } shouldBe listOf(LevelState.CUT, LevelState.FILL, LevelState.CUT)
    }

    @Test
    fun `S019 RF-9 volver a empezar sin estado previo rearma el aviso de la entrada`() {
        val inside = run(LevelState.ON_GRADE, 0.01).single()
        inside.alert shouldBe null
        // Tras apagar la guía el controlador empieza otra vez desde cero (S019/RF-11).
        StakeoutLevel.next(null, 0.01, tolerance).alert shouldBe LevelAlert.ARRIVED
    }

    @Test
    fun `S019 RF-10 el margen es de 5 mm con cualquier tolerancia vertical`() {
        run(LevelState.ON_GRADE, 1.0, 1.004, 1.006, tolerance = 1.0).map { it.state } shouldBe
            listOf(LevelState.ON_GRADE, LevelState.ON_GRADE, LevelState.CUT)
        run(LevelState.ON_GRADE, 0.01, 0.0149, 0.0151, tolerance = 0.01).map { it.state } shouldBe
            listOf(LevelState.ON_GRADE, LevelState.ON_GRADE, LevelState.CUT)
    }

    @Test
    fun `S019 RF-6 la clasificacion sin historial usa solo la tolerancia`() {
        StakeoutLevel.classify(0.03, 0.03) shouldBe LevelState.ON_GRADE
        StakeoutLevel.classify(0.0300001, 0.03) shouldBe LevelState.CUT
        StakeoutLevel.classify(-0.0300001, 0.03) shouldBe LevelState.FILL
    }

    @Test
    fun `S019 RF-12 el nivel no depende de las zonas horizontales de la guia`() {
        // Ninguna función del nivel recibe la distancia horizontal: la llegada de S018 se calcula aparte.
        StakeoutZones.next(StakeZone.FAR, 0.01, 0.025).zone shouldBe StakeZone.INSIDE
        StakeoutLevel.next(null, 2.0, tolerance).state shouldBe LevelState.CUT
    }
}
