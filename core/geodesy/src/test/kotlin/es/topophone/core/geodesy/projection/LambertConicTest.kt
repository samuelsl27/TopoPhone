package es.topophone.core.geodesy.projection

import es.topophone.core.geodesy.Ellipsoid
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.math.abs
import org.junit.jupiter.api.Test

/** Cónica Conforme de Lambert 1SP y 2SP frente a pyproj: ida, vuelta y puntos sin imagen (S032/RF-1, RF-19; constitución nº 2). */
class LambertConicTest {

    private val vectors = LccVector.all()

    private fun projectionOf(setup: ProjectionSetup): MapProjection = (ProjectionFactory.create(setup) as ProjectionResult.Ok).projection

    @Test
    fun `S032 RF-1 hay vectores de 1SP y 2SP en los casos de EPSG y en los propios`() {
        vectors.size shouldBeGreaterThan 40
        vectors.map { it.setup.parameters.method }.toSet() shouldBe
            setOf(ProjectionMethod.LAMBERT_CONIC_1SP, ProjectionMethod.LAMBERT_CONIC_2SP)
    }

    @Test
    fun `S032 RF-1 la ida coincide con pyproj a menos de 1 mm en 1SP y 2SP`() {
        val failures = vectors.mapNotNull { v ->
            val point = projectionOf(v.setup).forward(v.latitudeDeg, v.longitudeDeg)
            if (point == null) {
                "${v.case} (${v.latitudeDeg}, ${v.longitudeDeg}): sin imagen"
            } else {
                val dE = abs(point.eastingM - v.eastingM)
                val dN = abs(point.northingM - v.northingM)
                if (dE > 0.001 ||
                    dN > 0.001
                ) {
                    "${v.case} (${v.latitudeDeg}, ${v.longitudeDeg}): ΔE=${dE * 1000} mm, ΔN=${dN * 1000} mm"
                } else {
                    null
                }
            }
        }

        failures.shouldBeEmpty()
    }

    @Test
    fun `S032 RF-1 la vuelta coincide con pyproj a menos de 1e-8 grados`() {
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

        failures.shouldBeEmpty()
    }

    @Test
    fun `S032 RF-1 con los dos paralelos iguales el 2SP equivale al 1SP con factor 1`() {
        val vector = vectors.first { it.case == "paralelos-iguales-2sp" }
        val two = vector.setup.parameters as LambertConic2Sp
        val one = ProjectionSetup(
            vector.setup.ellipsoid,
            LambertConic1Sp(
                two.standardParallel1Deg,
                two.longitudeOfFalseOriginDeg,
                1.0,
                two.eastingAtFalseOrigin,
                two.northingAtFalseOrigin,
            ),
        )

        val a = projectionOf(vector.setup).forward(41.0, -97.5)!!
        val b = projectionOf(one).forward(41.0, -97.5)!!

        a.eastingM shouldBe b.eastingM.plusOrMinus(1e-6)
        a.northingM shouldBe b.northingM.plusOrMinus(1e-6)
    }

    @Test
    fun `S032 RF-1 en la esfera la ida y la vuelta tambien coinciden con pyproj`() {
        val sphere = vectors.filter { it.case == "esfera-2sp" }

        sphere.size shouldBeGreaterThan 10
        sphere.forEach { v ->
            val projection = projectionOf(v.setup)
            val point = projection.forward(v.latitudeDeg, v.longitudeDeg)!!
            point.eastingM shouldBe v.eastingM.plusOrMinus(0.001)
            point.northingM shouldBe v.northingM.plusOrMinus(0.001)
        }
    }

    @Test
    fun `S032 RF-1 el meridiano de Paris de los SC de EPSG se aplica al proyectar y al desproyectar`() {
        val paris = vectors.filter { it.case == "EPSG:27571" }

        paris.size shouldBeGreaterThan 10
        paris.forEach { v ->
            v.setup.primeMeridianDeg shouldBe 2.33722917.plusOrMinus(1e-6)
            val back = projectionOf(v.setup).inverse(v.eastingM, v.northingM)!!
            back.longitudeDeg shouldBe v.longitudeDeg.plusOrMinus(1e-8)
        }
    }

    @Test
    fun `S032 RF-19 el polo opuesto al apice del cono no tiene imagen`() {
        val north = vectors.first { it.case == "EPSG:2154" }.setup
        val south = vectors.first { it.case == "sur-2sp" }.setup

        projectionOf(north).forward(-90.0, 0.0) shouldBe null
        projectionOf(south).forward(90.0, 0.0) shouldBe null
    }

    @Test
    fun `S032 RF-19 el apice del cono es un punto valido`() {
        val setup = vectors.first { it.case == "EPSG:2154" }.setup

        val apex = projectionOf(setup).forward(90.0, 12.0)!!
        val back = projectionOf(setup).inverse(apex.eastingM, apex.northingM)!!

        back.latitudeDeg shouldBe 90.0.plusOrMinus(1e-9)
    }

    @Test
    fun `S032 RF-19 una latitud fuera de plus minus 90 grados o un valor que no es un numero no tiene imagen`() {
        val projection = projectionOf(vectors.first { it.case == "EPSG:3034" }.setup)

        projection.forward(90.5, 0.0) shouldBe null
        projection.forward(-91.0, 0.0) shouldBe null
        projection.forward(Double.NaN, 0.0) shouldBe null
        projection.forward(45.0, Double.NaN) shouldBe null
        projection.forward(45.0, Double.POSITIVE_INFINITY) shouldBe null
        projection.inverse(Double.NaN, 0.0) shouldBe null
        projection.inverse(0.0, Double.NEGATIVE_INFINITY) shouldBe null
    }

    @Test
    fun `S032 RF-1 la fabrica crea LCC y la lista publicada lo declara`() {
        ProjectionMethod.implemented.contains(ProjectionMethod.LAMBERT_CONIC_1SP) shouldBe true
        ProjectionMethod.implemented.contains(ProjectionMethod.LAMBERT_CONIC_2SP) shouldBe true
        ProjectionFactory.create(vectors.first { it.case == "EPSG:27571" }.setup).shouldBeInstanceOf<ProjectionResult.Ok>()
        ProjectionFactory.create(ProjectionSetup(Ellipsoid.GRS80, LambertConic2Sp(46.5, 3.0, 49.0, 44.0, 700_000.0, 6_600_000.0)))
            .shouldBeInstanceOf<ProjectionResult.Ok>()
    }
}
