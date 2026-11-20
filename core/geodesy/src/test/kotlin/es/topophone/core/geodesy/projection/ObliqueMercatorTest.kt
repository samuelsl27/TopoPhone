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

/** Oblique Mercator A y B frente a pyproj: ida, vuelta y puntos sin imagen (S032/RF-7, RF-19; constitución nº 2). */
class ObliqueMercatorTest {

    private val vectors = OmercVector.all()

    private fun projectionOf(setup: ProjectionSetup): MapProjection = (ProjectionFactory.create(setup) as ProjectionResult.Ok).projection

    private val sphere = Ellipsoid(6_371_000.0, Double.POSITIVE_INFINITY)

    @Test
    fun `S032 RF-7 hay vectores de las variantes A y B en los casos de EPSG y en los propios`() {
        vectors.size shouldBeGreaterThan 100
        vectors.map { it.setup.parameters.method }.toSet() shouldBe
            setOf(ProjectionMethod.OBLIQUE_MERCATOR_A, ProjectionMethod.OBLIQUE_MERCATOR_B)
    }

    @Test
    fun `S032 RF-7 la ida coincide con pyproj a menos de 1 mm en A y B`() {
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
    fun `S032 RF-7 la vuelta coincide con pyproj a menos de 1e-8 grados`() {
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
    fun `S032 RF-7 los casos limite de azimut estan cubiertos y coinciden con pyproj`() {
        val limitCases = listOf("a-azimut-0", "b-azimut-90", "a-azimut-120", "b-azimut-180") +
            listOf("b-azimut-270", "b-gamma-distinto", "b-ecuador")

        limitCases.forEach { name ->
            vectors.filter { it.case == name }.size shouldBeGreaterThan 10
        }
    }

    @Test
    fun `S032 RF-7 la variante A con azimut de 90 grados sigue de forma continua a los azimuts vecinos`() {
        fun eastingAt(azimuth: Double) = projectionOf(
            ProjectionSetup(Ellipsoid.GRS80, ObliqueMercatorA(45.0, 10.0, azimuth, azimuth, 0.9996, 1_000.0, 2_000.0)),
        ).forward(41.0, 5.0)!!

        val exact = eastingAt(90.0)
        val before = eastingAt(89.999999999)
        val after = eastingAt(90.000000001)

        exact.eastingM shouldBe before.eastingM.plusOrMinus(0.01)
        exact.eastingM shouldBe after.eastingM.plusOrMinus(0.01)
        exact.northingM shouldBe before.northingM.plusOrMinus(0.01)
    }

    @Test
    fun `S032 RF-7 en la variante B el centro de la proyeccion cae en el Este y el Norte del centro`() {
        vectors.filter { it.setup.parameters is ObliqueMercatorB }.map { it.case }.distinct().forEach { name ->
            val setup = vectors.first { it.case == name }.setup
            val parameters = setup.parameters as ObliqueMercatorB
            val projection = projectionOf(setup)

            val centre = projection.forward(parameters.latitudeOfCentreDeg, parameters.longitudeOfCentreDeg + setup.primeMeridianDeg)!!

            val unit = setup.linearUnit.metres
            centre.eastingM shouldBe (parameters.eastingAtCentre * unit).plusOrMinus(1e-6)
            centre.northingM shouldBe (parameters.northingAtCentre * unit).plusOrMinus(1e-6)
        }
    }

    @Test
    fun `S032 RF-7 en la variante A el origen natural lleva el falso Este y el falso Norte`() {
        val setup =
            ProjectionSetup(sphere, ObliqueMercatorA(0.0, 0.0, 0.0, 0.0, 1.0, 1_000.0, 2_000.0))

        // Con el centro en el ecuador y azimut 0 el origen natural es el propio centro (u = v = 0).
        val origin = projectionOf(setup).forward(0.0, 0.0)!!

        origin.eastingM shouldBe 1_000.0.plusOrMinus(1e-6)
        origin.northingM shouldBe 2_000.0.plusOrMinus(1e-6)
    }

    @Test
    fun `S032 RF-7 ida y vuelta devuelven el mismo punto con cualquier azimut`() {
        listOf(0.0, 30.0, 90.0, 135.0, 180.0, 225.0, 270.0, 330.0).forEach { azimuth ->
            listOf<ProjectionParameters>(
                ObliqueMercatorA(40.0, -3.0, azimuth, azimuth, 0.9996, 500_000.0, 0.0),
                ObliqueMercatorB(40.0, -3.0, azimuth, azimuth - 7.0, 0.9996, 500_000.0, 0.0),
            ).forEach { parameters ->
                val projection = projectionOf(ProjectionSetup(Ellipsoid.GRS80, parameters))

                val point = projection.forward(41.0, -2.0)!!
                val back = projection.inverse(point.eastingM, point.northingM)!!

                back.latitudeDeg shouldBe 41.0.plusOrMinus(1e-9)
                back.longitudeDeg shouldBe (-2.0).plusOrMinus(1e-9)
            }
        }
    }

    @Test
    fun `S032 RF-19 un punto a 90 grados de la linea central no tiene imagen y uno a 89 grados si`() {
        // Esfera, centro en el ecuador y azimut 0: es una Transversa de Mercator; a 90° del meridiano central, en el ecuador, está en el infinito.
        val projection = projectionOf(ProjectionSetup(sphere, ObliqueMercatorB(0.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0)))

        projection.forward(0.0, 90.0) shouldBe null
        projection.forward(0.0, -90.0) shouldBe null
        projection.forward(0.0, 89.0).shouldBeInstanceOf<ProjectedPoint>()
    }

    @Test
    fun `S032 RF-19 una latitud fuera de plus minus 90 grados o un valor que no es un numero no tiene imagen`() {
        val projection = projectionOf(vectors.first { it.case == "EPSG:26931" }.setup)

        projection.forward(90.5, 0.0) shouldBe null
        projection.forward(-120.0, 0.0) shouldBe null
        projection.forward(Double.NaN, 0.0) shouldBe null
        projection.forward(45.0, Double.NaN) shouldBe null
        projection.forward(45.0, Double.POSITIVE_INFINITY) shouldBe null
        projection.inverse(Double.NaN, 0.0) shouldBe null
        projection.inverse(0.0, Double.NEGATIVE_INFINITY) shouldBe null
    }

    @Test
    fun `S032 RF-7 los polos tienen imagen finita y vuelven al polo`() {
        val projection = projectionOf(vectors.first { it.case == "b-azimut-90" }.setup)

        listOf(90.0, -90.0).forEach { pole ->
            val point = projection.forward(pole, 10.0)!!
            val back = projection.inverse(point.eastingM, point.northingM)!!

            back.latitudeDeg shouldBe pole.plusOrMinus(1e-6)
        }
    }

    @Test
    fun `S032 RF-7 la fabrica crea Oblique Mercator y la lista publicada la declara`() {
        ProjectionMethod.implemented.contains(ProjectionMethod.OBLIQUE_MERCATOR_A) shouldBe true
        ProjectionMethod.implemented.contains(ProjectionMethod.OBLIQUE_MERCATOR_B) shouldBe true
        ProjectionFactory.create(vectors.first { it.case == "EPSG:29873" }.setup).shouldBeInstanceOf<ProjectionResult.Ok>()
    }
}
