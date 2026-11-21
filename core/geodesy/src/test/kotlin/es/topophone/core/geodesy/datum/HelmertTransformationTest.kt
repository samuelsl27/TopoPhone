package es.topophone.core.geodesy.datum

import es.topophone.core.geodesy.Ecef
import es.topophone.core.geodesy.GeodeticPoint
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.math.abs
import org.junit.jupiter.api.Test

/** Helmert de 3, 4, 6 y 7 parámetros en PV y CF frente a pyproj (S033/RF-1, RF-2; constitución nº 2). */
class HelmertTransformationTest {

    private val vectors = HelmertVector.all()

    private fun pointOf(result: TransformResult): GeodeticPoint = result.shouldBeInstanceOf<TransformResult.Ok>().point

    @Test
    fun `S033 RF-1 hay vectores de 3 4 6 y 7 parametros en PV y CF`() {
        vectors.size shouldBeGreaterThan 600
        vectors.map { it.parameterCount }.toSet() shouldBe setOf(3, 4, 6, 7)
        vectors.map { it.parameters.convention }.toSet() shouldBe
            setOf(HelmertConvention.POSITION_VECTOR, HelmertConvention.COORDINATE_FRAME)
    }

    @Test
    fun `S033 RF-1 la directa coincide con pyproj a menos de 1 mm en horizontal y en altura`() {
        val failures = vectors.mapNotNull { v ->
            val out = pointOf(v.transformation().forward(v.input))
            val dLat = abs(out.latitudeDeg - v.forward.latitudeDeg)
            val dLon = abs(out.longitudeDeg - v.forward.longitudeDeg)
            val dH = abs(out.ellipsoidalHeightM - v.forward.ellipsoidalHeightM)
            if (dLat > 1e-8 || dLon > 1e-8 || dH > 0.001) {
                "${v.case} (${v.input.latitudeDeg}, ${v.input.longitudeDeg}): ΔLat=$dLat°, ΔLon=$dLon°, ΔH=${dH * 1000} mm"
            } else {
                null
            }
        }

        failures.shouldBeEmpty()
    }

    @Test
    fun `S033 RF-1 con los parametros a cero y el mismo elipsoide el punto no cambia`() {
        val zero = HelmertVector.all().filter { it.case == "cero_7p" }

        zero.forEach { v ->
            val out = pointOf(v.transformation().forward(v.input))
            out.latitudeDeg shouldBe (v.input.latitudeDeg plusOrMinus 1e-10)
            out.longitudeDeg shouldBe (v.input.longitudeDeg plusOrMinus 1e-10)
            out.ellipsoidalHeightM shouldBe (v.input.ellipsoidalHeightM plusOrMinus 1e-6)
        }
    }

    @Test
    fun `S033 RF-1 la traslacion de 3 parametros mueve el punto en ECEF justo lo que dice`() {
        val params = (HelmertParameters.translations3(Triple(10.0, -20.0, 30.0)) as HelmertParametersResult.Ok).parameters
        val grs80 = es.topophone.core.geodesy.Ellipsoid.GRS80
        val point = GeodeticPoint(40.0, -3.0, 650.0)

        val out = pointOf(HelmertTransformation(params, grs80, grs80, TransformationInfo("t")).forward(point))

        val before = Ecef.fromGeodetic(point.latitudeDeg, point.longitudeDeg, point.ellipsoidalHeightM, grs80)
        val after = Ecef.fromGeodetic(out.latitudeDeg, out.longitudeDeg, out.ellipsoidalHeightM, grs80)
        (after.x - before.x) shouldBe (10.0 plusOrMinus 1e-6)
        (after.y - before.y) shouldBe (-20.0 plusOrMinus 1e-6)
        (after.z - before.z) shouldBe (30.0 plusOrMinus 1e-6)
    }

    @Test
    fun `S033 RF-1 una entrada no finita se rechaza sin lanzar excepcion`() {
        val v = vectors.first()

        v.transformation().forward(GeodeticPoint(Double.NaN, 0.0, 0.0)).shouldBeInstanceOf<TransformResult.NotConverged>()
        v.transformation().forward(GeodeticPoint(40.0, Double.POSITIVE_INFINITY, 0.0)).shouldBeInstanceOf<TransformResult.NotConverged>()
    }
}
