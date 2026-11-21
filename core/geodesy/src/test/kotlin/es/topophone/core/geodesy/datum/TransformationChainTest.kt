package es.topophone.core.geodesy.datum

import es.topophone.core.geodesy.Ecef
import es.topophone.core.geodesy.Ellipsoid
import es.topophone.core.geodesy.GeodeticPoint
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.math.abs
import org.junit.jupiter.api.Test

/** Cadenas de transformaciones de tres y cuatro pasos frente a la tubería completa de PROJ y su inverso (S033/RF-11; constitución nº 2). */
class TransformationChainTest {

    private val vectors = ChainVector.all()

    private fun pointOf(result: TransformResult): GeodeticPoint = result.shouldBeInstanceOf<TransformResult.Ok>().point

    private fun distanceM(a: GeodeticPoint, b: GeodeticPoint): Double {
        val grs80 = Ellipsoid.GRS80
        val ea = Ecef.fromGeodetic(a.latitudeDeg, a.longitudeDeg, a.ellipsoidalHeightM, grs80)
        val eb = Ecef.fromGeodetic(b.latitudeDeg, b.longitudeDeg, b.ellipsoidalHeightM, grs80)
        return ea.distanceTo(eb)
    }

    @Test
    fun `S033 RF-11 una cadena de tres pasos y otra de cuatro coinciden con la tuberia de PROJ a menos de 1 mm`() {
        vectors.size shouldBeGreaterThan 90
        vectors.map { it.steps.size }.toSet() shouldBe setOf(3, 4)

        val failures = vectors.mapNotNull { v ->
            val out = pointOf(v.chain().forward(v.input))
            val dLat = abs(out.latitudeDeg - v.forward.latitudeDeg)
            val dLon = abs(out.longitudeDeg - v.forward.longitudeDeg)
            val dH = abs(out.ellipsoidalHeightM - v.forward.ellipsoidalHeightM)
            if (dLat > 1e-8 ||
                dLon > 1e-8 ||
                dH > 0.001
            ) {
                "${v.case} (${v.input.latitudeDeg}, ${v.input.longitudeDeg}): $dLat°, $dLon°, ${dH * 1000} mm"
            } else {
                null
            }
        }

        failures.shouldBeEmpty()
    }

    @Test
    fun `S033 RF-11 la ida y la vuelta de la cadena cierran a menos de 0,1 mm`() {
        val failures = vectors.mapNotNull { v ->
            val chain = v.chain()
            val d = distanceM(pointOf(chain.inverse(pointOf(chain.forward(v.input)))), v.input)
            if (d > 1e-4) "${v.case} (${v.input.latitudeDeg}, ${v.input.longitudeDeg}): ${d * 1000} mm" else null
        }

        failures.shouldBeEmpty()
    }

    @Test
    fun `S033 RF-11 una cadena de un solo paso da lo mismo que el paso`() {
        val v = vectors.first()
        val first = v.steps.first()

        val chain = TransformationChain(listOf(first), TransformationInfo("uno"))

        chain.forward(v.input) shouldBe first.forward(v.input)
        chain.inverse(v.input) shouldBe first.inverse(v.input)
    }

    @Test
    fun `S033 RF-11 una cadena dentro de otra se calcula como los pasos seguidos`() {
        val v = vectors.first { it.steps.size == 4 }
        val inner = TransformationChain(v.steps.take(2), TransformationInfo("interna"))
        val nested = TransformationChain(listOf(inner) + v.steps.drop(2), TransformationInfo("externa"))

        val expected = pointOf(v.chain().forward(v.input))
        val actual = pointOf(nested.forward(v.input))

        distanceM(actual, expected) shouldBe 0.0
    }

    @Test
    fun `S033 RF-11 una cadena sin pasos no es valida`() {
        shouldThrow<IllegalArgumentException> { TransformationChain(emptyList(), TransformationInfo("vacia")) }
    }

    @Test
    fun `S033 RF-11 una entrada no finita se rechaza sin lanzar excepcion`() {
        val chain = vectors.first().chain()

        chain.forward(GeodeticPoint(Double.NaN, 0.0, 0.0)).shouldBeInstanceOf<TransformResult.NotConverged>()
        chain.inverse(GeodeticPoint(40.0, Double.POSITIVE_INFINITY, 0.0)).shouldBeInstanceOf<TransformResult.NotConverged>()
    }

    @Test
    fun `S033 RF-11 un punto fuera de la rejilla de la cadena se rechaza con el nombre de la rejilla`() {
        val chain = vectors.first().chain()

        chain.forward(GeodeticPoint(28.1, -15.4, 0.0)) shouldBe TransformResult.OutsideGrid(GridShiftVector.GRID_NAME)
    }
}
