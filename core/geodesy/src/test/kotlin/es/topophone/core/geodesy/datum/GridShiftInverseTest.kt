package es.topophone.core.geodesy.datum

import es.topophone.core.geodesy.GeodeticPoint
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.doubles.shouldBeGreaterThan
import io.kotest.matchers.ints.shouldBeGreaterThan as intShouldBeGreaterThan
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.math.cos
import kotlin.math.hypot
import org.junit.jupiter.api.Test

/** Inversa de la rejilla: ETRS89 a ED50 por iteración, con ida y vuelta a 0,1 mm y rechazo si no converge (S033/RF-10). */
class GridShiftInverseTest {

    private val vectors = GridShiftVector.all()
    private val source = ClasspathGridSource("/shift/")
    private val transformation = GridShiftTransformation(GridShiftVector.GRID_NAME, source, TransformationInfo("SPED2ETV2"))

    private fun pointOf(result: TransformResult): GeodeticPoint = result.shouldBeInstanceOf<TransformResult.Ok>().point

    /** Distancia horizontal aproximada en metros entre dos puntos cercanos. */
    private fun horizontalM(a: GeodeticPoint, b: GeodeticPoint): Double {
        val metresPerDegree = 111_319.49
        val dLat = (a.latitudeDeg - b.latitudeDeg) * metresPerDegree
        val dLon = (a.longitudeDeg - b.longitudeDeg) * metresPerDegree * cos(Math.toRadians(a.latitudeDeg))
        return hypot(dLat, dLon)
    }

    /** ¿Está el punto dentro de la rejilla, en las coordenadas de su datum de origen? */
    private fun insideGrid(p: GeodeticPoint) = transformation.forward(p) is TransformResult.Ok

    @Test
    fun `S033 RF-10 la inversa coincide con pyproj a menos de 1 mm cuando el resultado esta dentro de la rejilla`() {
        val withValue = vectors.filter { it.inverse != null && insideGrid(it.inverse) }
        // La inversa de PROJ para en 10 iteraciones y en algún punto no cierra: se usan solo las que cierran a 1 mm con la directa.
        val selfConsistent = withValue.filter { horizontalM(pointOf(transformation.forward(it.inverse!!)), it.input) <= 1e-3 }
        selfConsistent.size intShouldBeGreaterThan 80
        (withValue.size - selfConsistent.size) shouldBeLessThanOrEqual 2

        val failures = selfConsistent.mapNotNull { v ->
            val d = horizontalM(pointOf(transformation.inverse(v.input)), v.inverse!!)
            if (d > 1e-3) "${v.group} (${v.input.latitudeDeg}, ${v.input.longitudeDeg}): ${d * 1000} mm" else null
        }

        failures.shouldBeEmpty()
    }

    @Test
    fun `S033 RF-10 si el resultado cae fuera de la rejilla se rechaza aunque PROJ lo extrapole`() {
        val outsideResult = vectors.filter { it.inverse != null && !insideGrid(it.inverse) }

        outsideResult.forEach { v ->
            transformation.inverse(v.input) shouldBe TransformResult.OutsideGrid(GridShiftVector.GRID_NAME)
        }
    }

    @Test
    fun `S033 RF-10 la ida y la vuelta cierran a menos de 0,1 mm con desplazamientos de mas de 100 m`() {
        val inside = vectors.filter { it.forward != null }
        val failures = inside.mapNotNull { v ->
            val shifted = pointOf(transformation.forward(v.input))
            val d = horizontalM(pointOf(transformation.inverse(shifted)), v.input)
            if (d > 1e-4) "${v.group} (${v.input.latitudeDeg}, ${v.input.longitudeDeg}): ${d * 1000} mm" else null
        }
        val largestShiftM = inside.maxOf { v -> horizontalM(pointOf(transformation.forward(v.input)), v.input) }

        failures.shouldBeEmpty()
        largestShiftM shouldBeGreaterThan 100.0
    }

    @Test
    fun `S033 RF-10 y la vuelta de la inversa tambien cierra a menos de 0,1 mm`() {
        val solved = vectors.filter { it.group != "fuera" }.mapNotNull { v ->
            (transformation.inverse(v.input) as? TransformResult.Ok)?.let { v to it.point }
        }
        solved.size intShouldBeGreaterThan 80

        val failures = solved.mapNotNull { (v, ed50) ->
            val d = horizontalM(pointOf(transformation.forward(ed50)), v.input)
            if (d > 1e-4) "${v.group} (${v.input.latitudeDeg}, ${v.input.longitudeDeg}): ${d * 1000} mm" else null
        }

        failures.shouldBeEmpty()
    }

    @Test
    fun `S033 RF-10 lejos de la rejilla la inversa tambien rechaza el punto`() {
        vectors.filter { it.group == "fuera" }.forEach { v ->
            transformation.inverse(v.input) shouldBe TransformResult.OutsideGrid(GridShiftVector.GRID_NAME)
        }
    }

    @Test
    fun `S033 RF-10 si la iteracion no converge se rechaza el punto en vez de devolver una aproximacion`() {
        val impatient = GridShiftTransformation(GridShiftVector.GRID_NAME, source, TransformationInfo("SPED2ETV2"), maxIterations = 1)
        val v = vectors.first { it.group == "peninsula" }

        impatient.inverse(v.input) shouldBe TransformResult.NotConverged(GridShiftVector.GRID_NAME)
    }

    @Test
    fun `S033 RF-10 una rejilla ausente en la inversa se rechaza con su nombre`() {
        val missing = GridShiftTransformation("falta.tif", { null }, TransformationInfo("prueba"))

        missing.inverse(GeodeticPoint(40.0, -3.0, 0.0)) shouldBe TransformResult.GridUnavailable("falta.tif")
    }
}
