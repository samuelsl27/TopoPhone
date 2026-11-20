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

/** Estereográfica polar A, B y C frente a pyproj: ida, vuelta, los dos polos y puntos sin imagen (S032/RF-13, RF-19; constitución nº 2). */
class PolarStereographicTest {

    private val vectors = StereoPolarVector.all()

    private fun projectionOf(setup: ProjectionSetup): MapProjection = (ProjectionFactory.create(setup) as ProjectionResult.Ok).projection

    private fun setupOf(case: String) = vectors.first { it.case == case }.setup

    @Test
    fun `S032 RF-13 hay vectores de las tres variantes en los dos polos`() {
        vectors.size shouldBeGreaterThan 500
        vectors.map { it.setup.parameters.method }.toSet() shouldBe setOf(
            ProjectionMethod.POLAR_STEREOGRAPHIC_A,
            ProjectionMethod.POLAR_STEREOGRAPHIC_B,
            ProjectionMethod.POLAR_STEREOGRAPHIC_C,
        )
    }

    @Test
    fun `S032 RF-13 la ida coincide con pyproj a menos de 1 mm en A B y C`() {
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
    fun `S032 RF-13 la vuelta coincide con pyproj a menos de 1e-8 grados`() {
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
    fun `S032 RF-13 en la A y la B el polo cae en el falso Este y el falso Norte`() {
        listOf("EPSG:5041", "EPSG:5042", "EPSG:3031", "EPSG:3413", "b-polo-norte", "b-polo-sur").forEach { name ->
            val setup = setupOf(name)
            val pole = if (setup.parameters.let {
                    (it as? PolarStereographicA)?.latitudeOfOriginDeg
                        ?: (it as PolarStereographicB).latitudeOfStandardParallelDeg
                } >
                0
            ) {
                90.0
            } else {
                -90.0
            }
            val (falseEasting, falseNorthing) = setup.parameters.falseOrigin()

            val point = projectionOf(setup).forward(pole, 33.0)!!

            point.eastingM shouldBe (falseEasting * setup.linearUnit.metres).plusOrMinus(1e-6)
            point.northingM shouldBe (falseNorthing * setup.linearUnit.metres).plusOrMinus(1e-6)
        }
    }

    @Test
    fun `S032 RF-13 en la C el paralelo estandar sobre el meridiano de origen cae en el Este y el Norte del origen falso`() {
        listOf("EPSG:2985", "c-norte", "c-sur").forEach { name ->
            val setup = setupOf(name)
            val parameters = setup.parameters as PolarStereographicC

            val point = projectionOf(setup).forward(
                parameters.latitudeOfStandardParallelDeg,
                parameters.longitudeOfOriginDeg + setup.primeMeridianDeg,
            )!!

            point.eastingM shouldBe (parameters.eastingAtFalseOrigin * setup.linearUnit.metres).plusOrMinus(1e-6)
            point.northingM shouldBe (parameters.northingAtFalseOrigin * setup.linearUnit.metres).plusOrMinus(1e-6)
        }
    }

    @Test
    fun `S032 RF-13 con el paralelo estandar en el polo la B equivale a la A con factor de escala 1`() {
        val b = PolarStereographicB(90.0, 15.0, 500.0, 500.0)
        val a = PolarStereographicA(90.0, 15.0, 1.0, 500.0, 500.0)

        val fromB = projectionOf(ProjectionSetup(Ellipsoid.GRS80, b)).forward(75.0, 40.0)!!
        val fromA = projectionOf(ProjectionSetup(Ellipsoid.GRS80, a)).forward(75.0, 40.0)!!

        fromB.eastingM shouldBe fromA.eastingM.plusOrMinus(1e-6)
        fromB.northingM shouldBe fromA.northingM.plusOrMinus(1e-6)
    }

    @Test
    fun `S032 RF-13 los polos vuelven al polo con cualquier variante`() {
        listOf("EPSG:5041", "EPSG:5042", "EPSG:3031", "EPSG:3413", "c-norte", "c-sur").forEach { name ->
            val setup = setupOf(name)
            val projection = projectionOf(setup)
            val pole = if (name == "EPSG:5042" || name == "EPSG:3031" || name == "c-sur") -90.0 else 90.0

            val point = projection.forward(pole, 12.0)!!
            val back = projection.inverse(point.eastingM, point.northingM)!!

            back.latitudeDeg shouldBe pole.plusOrMinus(1e-9)
        }
    }

    @Test
    fun `S032 RF-19 el polo opuesto no tiene imagen y uno cercano si`() {
        val north = projectionOf(setupOf("EPSG:5041"))
        val south = projectionOf(setupOf("EPSG:5042"))

        north.forward(-90.0, 0.0) shouldBe null
        south.forward(90.0, 0.0) shouldBe null
        north.forward(-89.9, 0.0).shouldBeInstanceOf<ProjectedPoint>()
        south.forward(89.9, 0.0).shouldBeInstanceOf<ProjectedPoint>()
    }

    @Test
    fun `S032 RF-19 una latitud fuera de plus minus 90 grados o un valor que no es un numero no tiene imagen`() {
        val projection = projectionOf(setupOf("EPSG:3413"))

        projection.forward(90.5, 0.0) shouldBe null
        projection.forward(-120.0, 0.0) shouldBe null
        projection.forward(Double.NaN, 0.0) shouldBe null
        projection.forward(75.0, Double.NaN) shouldBe null
        projection.forward(75.0, Double.POSITIVE_INFINITY) shouldBe null
        projection.inverse(Double.NaN, 0.0) shouldBe null
        projection.inverse(0.0, Double.NEGATIVE_INFINITY) shouldBe null
    }

    @Test
    fun `S032 RF-13 la fabrica crea las tres variantes y la lista publicada las declara`() {
        listOf(
            ProjectionMethod.POLAR_STEREOGRAPHIC_A,
            ProjectionMethod.POLAR_STEREOGRAPHIC_B,
            ProjectionMethod.POLAR_STEREOGRAPHIC_C,
        ).forEach { ProjectionMethod.implemented.contains(it) shouldBe true }
        ProjectionFactory.create(setupOf("EPSG:2985")).shouldBeInstanceOf<ProjectionResult.Ok>()
    }
}
