package es.topophone.core.survey

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** El intervalo redondo de las marcas de PK según la escala del mapa (S049/RF-17). */
class ChainageMarksTest {

    @Test
    fun `S049 RF-17 el intervalo es 1 2 o 5 por una potencia de diez`() {
        // 90 px a cada escala: 9 m, 90 m, 225 m, 900 m…
        ChainageMarks.interval(0.1) shouldBe 10.0
        ChainageMarks.interval(1.0) shouldBe 100.0
        ChainageMarks.interval(2.5) shouldBe 500.0
        ChainageMarks.interval(10.0) shouldBe 1000.0
        ChainageMarks.interval(0.02) shouldBe 2.0
        ChainageMarks.interval(0.005) shouldBe 0.5
    }

    @Test
    fun `S049 RF-17 dos marcas distan siempre al menos la separacion minima en pantalla y es el menor intervalo que lo cumple`() {
        val rounds = (-3..6).flatMap { e -> listOf(1.0, 2.0, 5.0).map { it * Math.pow(10.0, e.toDouble()) } }.sorted()
        var mpp = 0.01
        while (mpp < 5_000.0) {
            val interval = ChainageMarks.interval(mpp)
            (interval / mpp >= ChainageMarks.MIN_SPACING_PX * (1 - 1e-9)) shouldBe true
            val previous = rounds.lastOrNull { it < interval * (1 - 1e-9) }
            (previous == null || previous / mpp < ChainageMarks.MIN_SPACING_PX) shouldBe true
            mpp *= 1.37
        }
    }

    @Test
    fun `S049 RF-17 un intervalo exacto no sube al siguiente por redondeo`() {
        ChainageMarks.interval(1.0, minSpacingPx = 100.0) shouldBe 100.0
        ChainageMarks.interval(0.2, minSpacingPx = 100.0) shouldBe 20.0
    }

    @Test
    fun `S049 RF-17 los PK son multiplos del intervalo dentro del eje`() {
        ChainageMarks.chainages(1000.5, 1380.5, 100.0) shouldBe listOf(1100.0, 1200.0, 1300.0)
        ChainageMarks.chainages(0.0, 420.0, 100.0) shouldBe listOf(0.0, 100.0, 200.0, 300.0, 400.0)
        ChainageMarks.chainages(0.0, 420.0, 100.0).first() shouldBe 0.0
    }

    @Test
    fun `S049 RF-17 el inicio y el final que son multiplos entran`() {
        ChainageMarks.chainages(100.0, 300.0, 100.0) shouldBe listOf(100.0, 200.0, 300.0)
        ChainageMarks.chainages(99.9999999, 200.0000001, 100.0) shouldBe listOf(100.0, 200.0)
    }

    @Test
    fun `S049 RF-17 un eje mas corto que el intervalo puede no tener ninguna marca`() {
        ChainageMarks.chainages(1010.0, 1090.0, 100.0) shouldBe emptyList()
        ChainageMarks.chainages(5.0, 3.0, 1.0) shouldBe emptyList()
    }

    @Test
    fun `S049 RF-17 con demasiadas marcas no se rotula ninguna`() {
        ChainageMarks.chainages(0.0, 100_000.0, 1.0) shouldBe emptyList()
        ChainageMarks.chainages(0.0, 399.0, 1.0).size shouldBe 400
    }

    @Test
    fun `S049 RF-17 una escala o un intervalo no positivos se rechazan`() {
        shouldThrow<IllegalArgumentException> { ChainageMarks.interval(0.0) }
        shouldThrow<IllegalArgumentException> { ChainageMarks.chainages(0.0, 10.0, 0.0) }
    }
}
