package es.topophone.core.geodesy.projection

import es.topophone.core.geodesy.Ellipsoid
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.math.abs
import org.junit.jupiter.api.Test

/** Estereográfica oblicua frente a pyproj y al punto publicado de GN 7-2: ida, vuelta y puntos sin imagen (S032/RF-10, RF-19). */
class ObliqueStereographicTest {

    private val vectors = StereoObliqueVector.all()

    private fun projectionOf(setup: ProjectionSetup): MapProjection = (ProjectionFactory.create(setup) as ProjectionResult.Ok).projection

    private val sphere = Ellipsoid(6_371_000.0, Double.POSITIVE_INFINITY)

    @Test
    fun `S032 RF-10 hay vectores de RD Nueva y de los casos limite`() {
        vectors.size shouldBeGreaterThan 100
        vectors.map { it.case }.toSet() shouldBe setOf("EPSG:28992", "origen-ecuador", "cerca-del-polo", "esfera", "sur")
    }

    @Test
    fun `S032 RF-10 la ida coincide con pyproj a menos de 1 mm`() {
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
    fun `S032 RF-10 la vuelta coincide con pyproj a menos de 1e-8 grados`() {
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
    fun `S032 RF-10 el punto publicado de GN 7-2 en RD Nueva sale a menos de 2 mm`() {
        val projection = projectionOf(vectors.first { it.case == "EPSG:28992" }.setup)

        val point = projection.forward(53.0, 6.0)!!

        point.eastingM shouldBe 196_105.283.plusOrMinus(0.002)
        point.northingM shouldBe 557_057.739.plusOrMinus(0.002)
    }

    @Test
    fun `S032 RF-10 el origen de la proyeccion cae en el falso Este y el falso Norte`() {
        vectors.map { it.case }.distinct().forEach { name ->
            val setup = vectors.first { it.case == name }.setup
            val parameters = setup.parameters as ObliqueStereographic

            val origin = projectionOf(setup).forward(
                parameters.latitudeOfOriginDeg,
                parameters.longitudeOfOriginDeg + setup.primeMeridianDeg,
            )!!

            origin.eastingM shouldBe (parameters.falseEasting * setup.linearUnit.metres).plusOrMinus(1e-6)
            origin.northingM shouldBe (parameters.falseNorthing * setup.linearUnit.metres).plusOrMinus(1e-6)
        }
    }

    @Test
    fun `S032 RF-19 el punto antipoda del origen no tiene imagen y uno cercano si`() {
        val projection = projectionOf(ProjectionSetup(sphere, ObliqueStereographic(40.0, 10.0, 1.0, 0.0, 0.0)))

        projection.forward(-40.0, -170.0) shouldBe null
        projection.forward(-40.0, 190.0) shouldBe null
        projection.forward(-39.0, -170.0).shouldBeInstanceOf<ProjectedPoint>()
    }

    @Test
    fun `S032 RF-19 una latitud fuera de plus minus 90 grados o un valor que no es un numero no tiene imagen`() {
        val projection = projectionOf(vectors.first { it.case == "EPSG:28992" }.setup)

        projection.forward(90.5, 0.0) shouldBe null
        projection.forward(-120.0, 0.0) shouldBe null
        projection.forward(Double.NaN, 0.0) shouldBe null
        projection.forward(45.0, Double.NaN) shouldBe null
        projection.forward(45.0, Double.POSITIVE_INFINITY) shouldBe null
        projection.inverse(Double.NaN, 0.0) shouldBe null
        projection.inverse(0.0, Double.NEGATIVE_INFINITY) shouldBe null
    }

    @Test
    fun `S032 RF-10 los polos tienen imagen finita y vuelven al polo`() {
        val projection = projectionOf(vectors.first { it.case == "EPSG:28992" }.setup)

        listOf(90.0, -90.0).forEach { pole ->
            val point = projection.forward(pole, 10.0)!!
            val back = projection.inverse(point.eastingM, point.northingM)!!

            back.latitudeDeg shouldBe pole.plusOrMinus(1e-6)
        }
    }

    @Test
    fun `S032 RF-10 ida y vuelta devuelven el mismo punto en el ecuador y en los dos hemisferios`() {
        listOf(0.0, -35.0, 60.0).forEach { origin ->
            val projection = projectionOf(ProjectionSetup(Ellipsoid.GRS80, ObliqueStereographic(origin, 20.0, 0.9996, 500_000.0, 1_000.0)))

            val point = projection.forward(origin + 3.0, 25.0)!!
            val back = projection.inverse(point.eastingM, point.northingM)!!

            back.latitudeDeg shouldBe (origin + 3.0).plusOrMinus(1e-9)
            back.longitudeDeg shouldBe 25.0.plusOrMinus(1e-9)
        }
    }

    @Test
    fun `S032 RF-10 la fabrica crea la Estereografica oblicua y la lista publicada la declara`() {
        ProjectionMethod.implemented.contains(ProjectionMethod.OBLIQUE_STEREOGRAPHIC) shouldBe true
        ProjectionFactory.create(vectors.first { it.case == "EPSG:28992" }.setup).shouldBeInstanceOf<ProjectionResult.Ok>()
    }
}
