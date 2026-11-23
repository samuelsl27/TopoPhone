package es.topophone.core.survey.localization

import es.topophone.core.model.localization.LocalizationPair
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlin.math.cos
import kotlin.math.sin
import org.junit.jupiter.api.Test

/** El ajuste horizontal de la localización: traslación, semejanza y movimiento rígido (S039/RF-7, RF-8, RF-12). */
class HorizontalSolverTest {

    /** Pares a partir de una transformación conocida sobre unas posiciones de UTM (grandes, para comprobar el centrado). */
    private fun pairs(rotation: Double, scale: Double, te: Double, tn: Double, count: Int = 4): List<LocalizationPair> {
        val xs = listOf(441000.0 to 4472900.0, 441800.0 to 4473500.0, 440300.0 to 4473100.0, 441400.0 to 4472200.0, 440900.0 to 4473900.0)
        return xs.take(count).mapIndexed { i, (e, n) ->
            val ke = scale * (cos(rotation) * e - sin(rotation) * n) + te
            val kn = scale * (sin(rotation) * e + cos(rotation) * n) + tn
            LocalizationPair("p$i", "id$i", "P$i", e, n, null, ke, kn, null)
        }
    }

    @Test
    fun `S039 RF-7 con un par es una traslacion con escala 1 y giro 0`() {
        val fit = HorizontalSolver.fit(pairs(0.01, 1.0001, 5.0, -3.0, count = 1), fixScale = false).shouldNotBeNull()

        fit.rotationRad shouldBe 0.0
        fit.scale shouldBe 1.0
        val pair = pairs(0.01, 1.0001, 5.0, -3.0, count = 1)[0]
        (fit.knownCentreE - fit.centreE) shouldBe (pair.knownE - pair.scE)
        (fit.knownCentreN - fit.centreN) shouldBe (pair.knownN - pair.scN)
    }

    @Test
    fun `S039 RF-7 con dos o mas pares sin ruido recupera el giro y la escala y no deja residuo`() {
        for (count in 2..5) {
            val fit = HorizontalSolver.fit(pairs(0.0035, 1.00002, 1000.123, -2000.456, count), fixScale = false).shouldNotBeNull()

            fit.rotationRad shouldBe (0.0035 plusOrMinus 1e-12)
            fit.scale shouldBe (1.00002 plusOrMinus 1e-12)
        }
    }

    @Test
    fun `S039 RF-8 con la escala fija la escala es exactamente 1 y el giro es el del ajuste rigido`() {
        val fit = HorizontalSolver.fit(pairs(0.0035, 1.0, 1000.123, -2000.456), fixScale = true).shouldNotBeNull()

        fit.scale shouldBe 1.0
        fit.rotationRad shouldBe (0.0035 plusOrMinus 1e-12)
    }

    @Test
    fun `S039 RF-8 con escala fija sobre pares con otra escala el giro sigue siendo el de minimos cuadrados`() {
        val fit = HorizontalSolver.fit(pairs(0.002, 1.0005, 10.0, 20.0), fixScale = true).shouldNotBeNull()

        fit.scale shouldBe 1.0
        fit.rotationRad shouldBe (0.002 plusOrMinus 1e-9)
    }

    @Test
    fun `S039 RF-12 dos pares con el mismo punto GNSS son degenerados`() {
        val a = LocalizationPair("a", "1", "A", 441000.0, 4472900.0, null, 10.0, 20.0, null)
        val b = a.copy(id = "b", pointId = "2", pointName = "B", knownE = 30.0, knownN = 40.0)

        HorizontalSolver.fit(listOf(a, b), fixScale = false).shouldBeNull()
        HorizontalSolver.fit(listOf(a, b), fixScale = true).shouldBeNull()
    }
}
