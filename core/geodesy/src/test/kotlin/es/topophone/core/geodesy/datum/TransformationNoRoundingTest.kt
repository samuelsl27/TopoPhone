package es.topophone.core.geodesy.datum

import es.topophone.core.geodesy.Ecef
import es.topophone.core.geodesy.GeodeticPoint
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test

/** Ningún valor intermedio ni final se redondea: la cadena da, bit a bit, lo que da la composición manual de sus pasos (S033/RF-13). */
class TransformationNoRoundingTest {

    private val vectors = ChainVector.all()

    private fun pointOf(result: TransformResult): GeodeticPoint = result.shouldBeInstanceOf<TransformResult.Ok>().point

    @Test
    fun `S033 RF-13 una cadena con la rejilla entre dos Helmert da bit a bit lo que dan los pasos seguidos`() {
        val three = vectors.filter { it.steps.size == 3 }

        three.forEach { v ->
            var manual = v.input
            v.steps.forEach { manual = pointOf(it.forward(manual)) }

            pointOf(v.chain().forward(v.input)) shouldBe manual
        }
    }

    @Test
    fun `S033 RF-13 los pasos geocentricos seguidos se componen en cartesianas sin pasar por geograficas ni redondear`() {
        vectors.filter { it.steps.size == 4 }.forEach { v ->
            val (first, second, grid, last) = v.steps
            val a = (first as HelmertTransformation).geocentricStep
            val b = (second as HelmertTransformation).geocentricStep
            val x = Ecef.fromGeodetic(v.input.latitudeDeg, v.input.longitudeDeg, v.input.ellipsoidalHeightM, a.source)
            val composed: GeodeticPoint = b.kernel.forward(a.kernel.forward(x)).toGeodetic(b.target)
            val manual = pointOf(last.forward(pointOf(grid.forward(composed))))

            pointOf(v.chain().forward(v.input)) shouldBe manual
        }
    }

    @Test
    fun `S033 RF-13 el resultado conserva todos los decimales de un Double`() {
        val results = vectors.take(20).map { pointOf(it.chain().forward(it.input)) }

        results.forEach { p ->
            // Un valor redondeado a milímetros o a 1e-8 grados sería exacto con pocos decimales; uno sin redondear no lo es.
            (Math.round(p.latitudeDeg * 1e8) / 1e8 != p.latitudeDeg) shouldBe true
            (Math.round(p.ellipsoidalHeightM * 1e3) / 1e3 != p.ellipsoidalHeightM) shouldBe true
        }
    }
}
