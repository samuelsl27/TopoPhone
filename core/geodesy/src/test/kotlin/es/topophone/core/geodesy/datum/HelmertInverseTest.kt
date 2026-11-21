package es.topophone.core.geodesy.datum

import es.topophone.core.geodesy.Ecef
import es.topophone.core.geodesy.Ellipsoid
import es.topophone.core.geodesy.GeodeticPoint
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test

/** El inverso del Helmert es el exacto del directo, no el cambio de signo de los parámetros (S033/RF-5; constitución nº 2). */
class HelmertInverseTest {

    private val vectors = HelmertVector.all()

    private fun pointOf(result: TransformResult): GeodeticPoint = result.shouldBeInstanceOf<TransformResult.Ok>().point

    private fun distanceM(a: GeodeticPoint, b: GeodeticPoint, ellipsoid: Ellipsoid): Double {
        val ea = Ecef.fromGeodetic(a.latitudeDeg, a.longitudeDeg, a.ellipsoidalHeightM, ellipsoid)
        val eb = Ecef.fromGeodetic(b.latitudeDeg, b.longitudeDeg, b.ellipsoidalHeightM, ellipsoid)
        return ea.distanceTo(eb)
    }

    @Test
    fun `S033 RF-5 la ida y la vuelta cierran a menos de 0,1 mm en todos los casos`() {
        val failures = vectors.mapNotNull { v ->
            val t = v.transformation()
            val back = pointOf(t.inverse(pointOf(t.forward(v.input))))
            val d = distanceM(back, v.input, v.source)
            if (d > 1e-4) "${v.case} (${v.input.latitudeDeg}, ${v.input.longitudeDeg}): ${d * 1000} mm" else null
        }

        failures.shouldBeEmpty()
    }

    @Test
    fun `S033 RF-5 el inverso difiere del de pyproj en menos de 1 mm con los parametros habituales`() {
        val failures = vectors.filter { !it.isLarge() }.mapNotNull { v ->
            val out = pointOf(v.transformation().inverse(v.input))
            val d = distanceM(out, v.inverse, v.source)
            if (d > 1e-3) "${v.case} (${v.input.latitudeDeg}, ${v.input.longitudeDeg}): ${d * 1000} mm" else null
        }

        failures.shouldBeEmpty()
    }

    /**
     * Cambiar el signo de los parámetros invierte la matriz solo hasta el primer orden: el error es del orden de R·(s² + Σθ²). Con 3″ y
     * 15 ppm ya son 1,3 mm y con 10″ y 50 ppm unos 5 cm. Aquí se comprueba que la diferencia con pyproj nunca pasa del doble de esa cota.
     */
    @Test
    fun `S033 RF-5 con rotaciones y escalas grandes el de pyproj se separa del exacto menos que la cota teorica`() {
        val failures = vectors.filter { it.isLarge() }.mapNotNull { v ->
            val p = v.parameters
            val bound = 2.0 * EARTH_RADIUS_M * (p.scale * p.scale + p.rxRad * p.rxRad + p.ryRad * p.ryRad + p.rzRad * p.rzRad)
            val d = distanceM(pointOf(v.transformation().inverse(v.input)), v.inverse, v.source)
            if (d > bound) "${v.case} (${v.input.latitudeDeg}, ${v.input.longitudeDeg}): ${d * 1000} mm > ${bound * 1000} mm" else null
        }

        failures.shouldBeEmpty()
    }

    private fun HelmertVector.isLarge() = case.startsWith("grande") || case.startsWith("enorme")

    private companion object {
        const val EARTH_RADIUS_M = 6_400_000.0
    }

    @Test
    fun `S033 RF-5 una entrada no finita se rechaza en el inverso`() {
        vectors.first().transformation().inverse(GeodeticPoint(Double.NaN, 0.0, 0.0)).shouldBeInstanceOf<TransformResult.NotConverged>()
    }
}
