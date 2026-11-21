package es.topophone.core.geodesy.datum

import es.topophone.core.geodesy.GeodeticPoint
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** Una rejilla ausente en cualquier paso rechaza el punto con su nombre y no se calcula ningún paso de la cadena (S033/RF-12). */
class TransformationChainMissingGridTest {

    /** Un paso que cuenta cuántas veces se le pide calcular y que no cambia el punto. */
    private class SpyStep : DatumTransformation {
        var calls = 0
        override val info = TransformationInfo("espía")

        override fun forward(point: GeodeticPoint): TransformResult {
            calls++
            return TransformResult.Ok(point)
        }

        override fun inverse(point: GeodeticPoint): TransformResult {
            calls++
            return TransformResult.Ok(point)
        }
    }

    private val missing = GridShiftTransformation("falta.tif", { null }, TransformationInfo("sin rejilla"))
    private val real = GridShiftTransformation(GridShiftVector.GRID_NAME, ClasspathGridSource("/shift/"), TransformationInfo("SPED2ETV2"))
    private val madrid = GeodeticPoint(40.4168, -3.7038, 650.0)
    private val canarias = GeodeticPoint(28.1, -15.4, 10.0)

    private fun chain(vararg steps: DatumTransformation) = TransformationChain(steps.toList(), TransformationInfo("cadena"))

    @Test
    fun `S033 RF-12 la rejilla ausente se rechaza con su nombre sea cual sea su posicion`() {
        val a = SpyStep()
        val b = SpyStep()

        listOf(chain(missing, a, b), chain(a, missing, b), chain(a, b, missing)).forEach { c ->
            c.forward(madrid) shouldBe TransformResult.GridUnavailable("falta.tif")
            c.inverse(madrid) shouldBe TransformResult.GridUnavailable("falta.tif")
        }
    }

    @Test
    fun `S033 RF-12 no se calcula ningun paso de la cadena si falta una rejilla`() {
        val first = SpyStep()
        val last = SpyStep()

        chain(first, real, missing, last).forward(madrid)
        chain(first, real, missing, last).inverse(madrid)

        first.calls shouldBe 0
        last.calls shouldBe 0
    }

    @Test
    fun `S033 RF-12 la rejilla que falta gana a un rechazo anterior de otro paso`() {
        // Canarias está fuera de la SPED2ETV2, que va antes; aun así se dice qué rejilla falta, no que el punto esté fuera.
        chain(real, missing).forward(canarias) shouldBe TransformResult.GridUnavailable("falta.tif")
    }

    @Test
    fun `S033 RF-12 una cadena anidada con una rejilla ausente tambien rechaza`() {
        val inner = chain(SpyStep(), missing)
        val outer = chain(SpyStep(), inner)

        outer.forward(madrid) shouldBe TransformResult.GridUnavailable("falta.tif")
    }

    @Test
    fun `S033 RF-12 con todas las rejillas instaladas la cadena calcula y no informa de rejillas ausentes`() {
        val chain = chain(SpyStep(), real, SpyStep())

        chain.missingGrid() shouldBe null
        (chain.forward(madrid) is TransformResult.Ok) shouldBe true
    }

    @Test
    fun `S033 RF-12 la primera rejilla ausente es la que se nombra`() {
        val other = GridShiftTransformation("otra.tif", { null }, TransformationInfo("otra"))

        chain(real, other, missing).forward(madrid) shouldBe TransformResult.GridUnavailable("otra.tif")
    }
}
