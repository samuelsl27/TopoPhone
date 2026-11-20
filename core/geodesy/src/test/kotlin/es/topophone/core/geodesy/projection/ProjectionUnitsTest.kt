package es.topophone.core.geodesy.projection

import es.topophone.core.geodesy.Ellipsoid
import es.topophone.core.model.project.LinearUnit
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import kotlin.math.abs
import org.junit.jupiter.api.Test

/** El falso Este y el falso Norte se escriben en la unidad del SC y pasan a metros (S032/RF-16, RNF-T9). */
class ProjectionUnitsTest {

    private val footM = 0.3048
    private val usFootM = 1200.0 / 3937.0

    @Test
    fun `S032 RF-16 en metros los falsos no cambian en ninguna variante`() {
        ProjectionTestData.all.forEach { parameters ->
            val converted = parameters.inMetres(LinearUnit.METRE)

            ProjectionTestData.falseOrigin(converted) shouldBe ProjectionTestData.falseOrigin(parameters)
        }
    }

    @Test
    fun `S032 RF-16 en pies internacionales los falsos se multiplican por 0,3048 en todas las variantes`() {
        ProjectionTestData.all.forEach { parameters ->
            val (east, north) = ProjectionTestData.falseOrigin(parameters.inMetres(LinearUnit.INTERNATIONAL_FOOT))

            east shouldBe (ProjectionTestData.FALSE_EASTING * footM).plusOrMinus(1e-9)
            north shouldBe (ProjectionTestData.FALSE_NORTHING * footM).plusOrMinus(1e-9)
        }
    }

    @Test
    fun `S032 RF-16 en pies topograficos de EEUU los falsos se multiplican por 1200 entre 3937`() {
        ProjectionTestData.all.forEach { parameters ->
            val (east, north) = ProjectionTestData.falseOrigin(parameters.inMetres(LinearUnit.US_SURVEY_FOOT))

            east shouldBe (ProjectionTestData.FALSE_EASTING * usFootM).plusOrMinus(1e-9)
            north shouldBe (ProjectionTestData.FALSE_NORTHING * usFootM).plusOrMinus(1e-9)
        }
    }

    @Test
    fun `S032 RF-16 la conversion no toca ningun angulo ni factor de escala ni paralelo`() {
        ProjectionTestData.all.forEach { parameters ->
            val converted = parameters.inMetres(LinearUnit.US_SURVEY_FOOT)

            ProjectionTestData.withFalseOrigin(converted, 0.0, 0.0) shouldBe ProjectionTestData.withFalseOrigin(parameters, 0.0, 0.0)
        }
    }

    private val vectors = PrimeMeridianVector.all("unidades_proyeccion.csv")

    private fun projectionOf(setup: ProjectionSetup): MapProjection = (ProjectionFactory.create(setup) as ProjectionResult.Ok).projection

    @Test
    fun `S032 RF-16 hay vectores de los diez metodos en pies y en pies topograficos y EPSG 32040 y 29873`() {
        val cases = vectors.map { it.case }.toSet()

        listOf(LinearUnit.INTERNATIONAL_FOOT, LinearUnit.US_SURVEY_FOOT).forEach { unit ->
            vectors.filter { it.setup.linearUnit == unit }.map { it.setup.parameters.method }.toSet().size shouldBe 10
        }
        (cases.containsAll(listOf("EPSG:32040", "EPSG:29873"))) shouldBe true
    }

    @Test
    fun `S032 RF-16 la ida y la vuelta con los falsos en pies coinciden con pyproj en metros`() {
        val failures = vectors.mapNotNull { v ->
            val projection = projectionOf(v.setup)
            val point = projection.forward(v.latitudeDeg, v.longitudeDeg)
            val back = projection.inverse(v.eastingM, v.northingM)
            when {
                point == null || back == null -> "${v.case} (${v.latitudeDeg}, ${v.longitudeDeg}): sin imagen"
                abs(point.eastingM - v.eastingM) > 0.001 || abs(point.northingM - v.northingM) > 0.001 ->
                    "${v.case} (${v.latitudeDeg}, ${v.longitudeDeg}): ΔE=${point.eastingM - v.eastingM} m, ΔN=${point.northingM - v.northingM} m"
                abs(back.latitudeDeg - v.latitudeDeg) > 1e-8 || abs(back.longitudeDeg - v.longitudeDeg) > 1e-8 ->
                    "${v.case} (${v.eastingM}, ${v.northingM}): vuelta Δφ=${back.latitudeDeg - v.latitudeDeg}°"
                else -> null
            }
        }

        withClue(failures.joinToString("\n")) { failures.shouldBeEmpty() }
    }

    @Test
    fun `S032 RF-16 el factor de escala y la convergencia no dependen de la unidad`() {
        val failures = vectors.mapNotNull { v ->
            val factors = projectionOf(v.setup).factors(v.latitudeDeg, v.longitudeDeg)
            if (factors == null ||
                abs(factors.scale - v.scale) > 1e-9 ||
                abs(Math.toDegrees(factors.convergenceRad) - v.convergenceDeg) * 3600.0 > 0.01
            ) {
                "${v.case} (${v.latitudeDeg}, ${v.longitudeDeg}): $factors"
            } else {
                null
            }
        }

        withClue(failures.joinToString("\n")) { failures.shouldBeEmpty() }
    }

    @Test
    fun `S032 RF-16 un falso que no es un numero finito se rechaza e indica cual es`() {
        val setup = ProjectionSetup(Ellipsoid.GRS80, MercatorA(0.0, 1.0, Double.NaN, Double.POSITIVE_INFINITY))

        val problems = (ProjectionFactory.create(setup) as ProjectionResult.Invalid).problems

        problems shouldBe listOf(
            ParameterProblem(ProjectionParameter.FALSE_EASTING, ProblemKind.NOT_FINITE),
            ParameterProblem(ProjectionParameter.FALSE_NORTHING, ProblemKind.NOT_FINITE),
        )
    }
}
