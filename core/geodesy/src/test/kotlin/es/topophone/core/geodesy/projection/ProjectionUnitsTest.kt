package es.topophone.core.geodesy.projection

import es.topophone.core.geodesy.Ellipsoid
import es.topophone.core.model.project.LinearUnit
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
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
