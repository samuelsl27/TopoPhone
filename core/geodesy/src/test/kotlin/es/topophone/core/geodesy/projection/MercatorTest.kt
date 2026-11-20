package es.topophone.core.geodesy.projection

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.math.abs
import org.junit.jupiter.api.Test

/** Mercator A y B frente a pyproj: ida, vuelta y puntos sin imagen (S032/RF-4, RF-19; constitución nº 2). */
class MercatorTest {

    private val vectors = MercatorVector.all()

    private fun projectionOf(setup: ProjectionSetup): MapProjection = (ProjectionFactory.create(setup) as ProjectionResult.Ok).projection

    @Test
    fun `S032 RF-4 hay vectores de las variantes A y B en los casos de EPSG y en los propios`() {
        vectors.size shouldBeGreaterThan 40
        vectors.map { it.setup.parameters.method }.toSet() shouldBe setOf(ProjectionMethod.MERCATOR_A, ProjectionMethod.MERCATOR_B)
    }

    @Test
    fun `S032 RF-4 la ida coincide con pyproj a menos de 1 mm en A y B`() {
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
    fun `S032 RF-4 la vuelta coincide con pyproj a menos de 1e-8 grados`() {
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
    fun `S032 RF-4 con el paralelo estandar en el ecuador la B equivale a la A con factor 1`() {
        val b = vectors.first { it.case == "b-ecuador" }
        val parameters = b.setup.parameters as MercatorB
        val a =
            ProjectionSetup(
                b.setup.ellipsoid,
                MercatorA(parameters.longitudeOfOriginDeg, 1.0, parameters.falseEasting, parameters.falseNorthing),
            )

        val fromB = projectionOf(b.setup).forward(37.0, 12.0)!!
        val fromA = projectionOf(a).forward(37.0, 12.0)!!

        fromB.eastingM shouldBe fromA.eastingM.plusOrMinus(1e-6)
        fromB.northingM shouldBe fromA.northingM.plusOrMinus(1e-6)
    }

    @Test
    fun `S032 RF-4 en la esfera la ida y la vuelta coinciden con pyproj`() {
        listOf("esfera-a", "esfera-b").forEach { name ->
            val sphere = vectors.filter { it.case == name }
            sphere.size shouldBeGreaterThan 10
            sphere.forEach { v ->
                val projection = projectionOf(v.setup)
                val point = projection.forward(v.latitudeDeg, v.longitudeDeg)!!
                point.eastingM shouldBe v.eastingM.plusOrMinus(0.001)
                point.northingM shouldBe v.northingM.plusOrMinus(0.001)
            }
        }
    }

    @Test
    fun `S032 RF-4 el Este crece en linea recta con la longitud y no depende de la latitud`() {
        val projection = projectionOf(vectors.first { it.case == "EPSG:3395" }.setup)

        val a = projection.forward(10.0, 20.0)!!
        val b = projection.forward(70.0, 20.0)!!

        a.eastingM shouldBe b.eastingM.plusOrMinus(1e-6)
    }

    @Test
    fun `S032 RF-19 los polos no tienen imagen`() {
        val projection = projectionOf(vectors.first { it.case == "EPSG:3395" }.setup)

        projection.forward(90.0, 0.0) shouldBe null
        projection.forward(-90.0, 0.0) shouldBe null
    }

    @Test
    fun `S032 RF-19 una latitud fuera de plus minus 90 grados o un valor que no es un numero no tiene imagen`() {
        val projection = projectionOf(vectors.first { it.case == "EPSG:3388" }.setup)

        projection.forward(90.5, 0.0) shouldBe null
        projection.forward(-120.0, 0.0) shouldBe null
        projection.forward(Double.NaN, 0.0) shouldBe null
        projection.forward(45.0, Double.NaN) shouldBe null
        projection.forward(45.0, Double.POSITIVE_INFINITY) shouldBe null
        projection.inverse(Double.NaN, 0.0) shouldBe null
        projection.inverse(0.0, Double.NEGATIVE_INFINITY) shouldBe null
    }

    @Test
    fun `S032 RF-4 la fabrica crea Mercator y la lista publicada la declara`() {
        ProjectionMethod.implemented.contains(ProjectionMethod.MERCATOR_A) shouldBe true
        ProjectionMethod.implemented.contains(ProjectionMethod.MERCATOR_B) shouldBe true
        ProjectionFactory.create(vectors.first { it.case == "EPSG:3002" }.setup).shouldBeInstanceOf<ProjectionResult.Ok>()
    }
}
