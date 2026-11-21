package es.topophone.core.geodesy.datum

import es.topophone.core.geodesy.Ecef
import es.topophone.core.geodesy.Ellipsoid
import es.topophone.core.geodesy.GeodeticPoint
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.math.abs
import org.junit.jupiter.api.Test

/** Molodensky-Badekas de 10 parámetros frente a pyproj, con el punto de rotación y su inverso exacto (S033/RF-3, RF-5; constitución nº 2). */
class MolodenskyBadekasTest {

    private val vectors = MolodenskyBadekasVector.all()

    private fun pointOf(result: TransformResult): GeodeticPoint = result.shouldBeInstanceOf<TransformResult.Ok>().point

    private fun distanceM(a: GeodeticPoint, b: GeodeticPoint, ellipsoid: Ellipsoid): Double {
        val ea = Ecef.fromGeodetic(a.latitudeDeg, a.longitudeDeg, a.ellipsoidalHeightM, ellipsoid)
        val eb = Ecef.fromGeodetic(b.latitudeDeg, b.longitudeDeg, b.ellipsoidalHeightM, ellipsoid)
        return ea.distanceTo(eb)
    }

    @Test
    fun `S033 RF-3 la directa coincide con pyproj a menos de 1 mm en PV y CF`() {
        vectors.size shouldBeGreaterThan 250

        val failures = vectors.mapNotNull { v ->
            val out = pointOf(v.transformation().forward(v.input))
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
    fun `S033 RF-3 con el punto de rotacion en el centro de la Tierra da lo mismo que el Helmert de 7 parametros`() {
        val zeroPivot = vectors.filter { it.case == "mb_pivote_cero_pv" }
        zeroPivot.size shouldBeGreaterThan 10

        zeroPivot.forEach { v ->
            val mb = pointOf(v.transformation().forward(v.input))
            val helmert = pointOf(HelmertTransformation(v.helmert, v.source, v.target, TransformationInfo("h")).forward(v.input))
            distanceM(mb, helmert, v.target) shouldBe (0.0 plusOrMinus 1e-9)
        }
    }

    @Test
    fun `S033 RF-3 con otro punto de rotacion no da lo mismo que el Helmert`() {
        val v = vectors.first { it.case == "mb_pivote_iberia_pv" }

        val mb = pointOf(v.transformation().forward(v.input))
        val helmert = pointOf(HelmertTransformation(v.helmert, v.source, v.target, TransformationInfo("h")).forward(v.input))

        (distanceM(mb, helmert, v.target) > 0.001) shouldBe true
    }

    @Test
    fun `S033 RF-5 la ida y la vuelta cierran a menos de 0,1 mm`() {
        val failures = vectors.mapNotNull { v ->
            val t = v.transformation()
            val d = distanceM(pointOf(t.inverse(pointOf(t.forward(v.input)))), v.input, v.source)
            if (d > 1e-4) "${v.case} (${v.input.latitudeDeg}, ${v.input.longitudeDeg}): ${d * 1000} mm" else null
        }

        failures.shouldBeEmpty()
    }

    @Test
    fun `S033 RF-5 el inverso difiere del de pyproj menos que la cota del cambio de signo`() {
        val failures = vectors.mapNotNull { v ->
            val p = v.helmert
            val bound = maxOf(1e-3, 2.0 * 6_400_000.0 * (p.scale * p.scale + p.rxRad * p.rxRad + p.ryRad * p.ryRad + p.rzRad * p.rzRad))
            val d = distanceM(pointOf(v.transformation().inverse(v.input)), v.inverse, v.source)
            if (d > bound) "${v.case} (${v.input.latitudeDeg}, ${v.input.longitudeDeg}): ${d * 1000} mm > ${bound * 1000} mm" else null
        }

        failures.shouldBeEmpty()
    }

    @Test
    fun `S033 RF-6 un punto de rotacion no finito se rechaza y se indica cual`() {
        val helmert = vectors.first().helmert

        MolodenskyBadekasParameters.of(helmert, Triple(Double.NaN, 1.0, Double.POSITIVE_INFINITY))
            .shouldBeInstanceOf<MolodenskyBadekasParametersResult.Invalid>().problems shouldContainExactlyInAnyOrder
            listOf(
                ParameterProblem(DatumParameter.PIVOT_X, ProblemKind.NOT_FINITE),
                ParameterProblem(DatumParameter.PIVOT_Z, ProblemKind.NOT_FINITE),
            )
    }

    @Test
    fun `S033 RF-3 una entrada no finita se rechaza sin lanzar excepcion`() {
        val t = vectors.first().transformation()

        t.forward(GeodeticPoint(Double.NaN, 0.0, 0.0)).shouldBeInstanceOf<TransformResult.NotConverged>()
        t.inverse(GeodeticPoint(0.0, Double.NaN, 0.0)).shouldBeInstanceOf<TransformResult.NotConverged>()
    }
}
