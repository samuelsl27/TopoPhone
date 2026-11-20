package es.topophone.core.geodesy.projection

import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import kotlin.math.abs
import org.junit.jupiter.api.Test

/**
 * Los diez métodos con el meridiano de origen de Madrid, París y Lisboa frente a pyproj, que cuenta las longitudes desde Greenwich: la ida,
 * la vuelta, el factor de escala y la convergencia (S032/RF-17; constitución nº 2).
 */
class PrimeMeridianVectorsTest {

    private val vectors = PrimeMeridianVector.all()

    private fun projectionOf(setup: ProjectionSetup): MapProjection = (ProjectionFactory.create(setup) as ProjectionResult.Ok).projection

    @Test
    fun `S032 RF-17 hay vectores de los diez metodos y de Madrid Paris y Lisboa`() {
        vectors.size shouldBeGreaterThan 300
        vectors.map { it.setup.parameters.method }.toSet().size shouldBe 10
        vectors.map { it.setup.primeMeridianDeg }.distinct().size shouldBeGreaterThan 2
    }

    @Test
    fun `S032 RF-17 la ida coincide con pyproj a menos de 1 mm con cualquier meridiano de origen`() {
        val failures = vectors.mapNotNull { v ->
            val point = projectionOf(v.setup).forward(v.latitudeDeg, v.longitudeDeg)
            if (point == null) {
                "${v.case} (${v.latitudeDeg}, ${v.longitudeDeg}): sin imagen"
            } else {
                val dE = abs(point.eastingM - v.eastingM)
                val dN = abs(point.northingM - v.northingM)
                if (dE > 0.001 || dN > 0.001) {
                    "${v.case} (${v.latitudeDeg}, ${v.longitudeDeg}): ΔE=${dE * 1000} mm, ΔN=${dN * 1000} mm"
                } else {
                    null
                }
            }
        }

        withClue(failures.joinToString("\n")) { failures.shouldBeEmpty() }
    }

    @Test
    fun `S032 RF-17 la vuelta coincide con pyproj a menos de 1e-8 grados y devuelve la longitud de Greenwich`() {
        val failures = vectors.mapNotNull { v ->
            val point = projectionOf(v.setup).inverse(v.eastingM, v.northingM)
            if (point == null) {
                "${v.case} (${v.eastingM}, ${v.northingM}): sin imagen"
            } else {
                val dLat = abs(point.latitudeDeg - v.latitudeDeg)
                val dLon = abs(point.longitudeDeg - v.longitudeDeg)
                if (dLat > 1e-8 || dLon > 1e-8) "${v.case} (${v.eastingM}, ${v.northingM}): Δφ=$dLat°, Δλ=$dLon°" else null
            }
        }

        withClue(failures.joinToString("\n")) { failures.shouldBeEmpty() }
    }

    @Test
    fun `S032 RF-17 el factor de escala y la convergencia coinciden con pyproj`() {
        val failures = vectors.mapNotNull { v ->
            val factors = projectionOf(v.setup).factors(v.latitudeDeg, v.longitudeDeg)
            val dScale = factors?.let { abs(it.scale - v.scale) }
            val dConvergenceArcSeconds = factors?.let { abs(Math.toDegrees(it.convergenceRad) - v.convergenceDeg) * 3600.0 }
            if (dScale == null || dConvergenceArcSeconds == null || dScale > 1e-9 || dConvergenceArcSeconds > 0.01) {
                "${v.case} (${v.latitudeDeg}, ${v.longitudeDeg}): Δk=$dScale, Δγ=$dConvergenceArcSeconds″"
            } else {
                null
            }
        }

        withClue(failures.joinToString("\n")) { failures.shouldBeEmpty() }
    }

    @Test
    fun `S032 RF-17 un SC que cruza el antimeridiano devuelve las longitudes en menos 180 a 180`() {
        val crossing = vectors.filter { it.case.startsWith("cruza-antimeridiano") }

        crossing.size shouldBeGreaterThan 10
        crossing.any { it.longitudeDeg < -170.0 } shouldBe true
        crossing.any { it.longitudeDeg > 170.0 } shouldBe true
        crossing.forEach { v ->
            val back = projectionOf(v.setup).inverse(v.eastingM, v.northingM)!!
            (back.longitudeDeg > -180.0 && back.longitudeDeg <= 180.0) shouldBe true
        }
    }
}
