package es.topophone.core.geodesy.projection

import es.topophone.core.geodesy.Ellipsoid
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test

/** Un LCC con parámetros no válidos se rechaza e indica qué parámetro falla (S032/RF-3). */
class LambertConicParametersTest {

    private fun problems(parameters: ProjectionParameters): List<ParameterProblem> =
        (ProjectionFactory.create(ProjectionSetup(Ellipsoid.GRS80, parameters)) as ProjectionResult.Invalid).problems

    private fun two(lat1: Double = 49.0, lat2: Double = 44.0, latOrigin: Double = 46.5, lon: Double = 3.0) =
        LambertConic2Sp(latOrigin, lon, lat1, lat2, 700_000.0, 6_600_000.0)

    private fun one(latOrigin: Double = 40.0, k0: Double = 0.9996, lon: Double = -3.0) = LambertConic1Sp(latOrigin, lon, k0, 500_000.0, 0.0)

    @Test
    fun `S032 RF-3 los paralelos opuestos respecto al ecuador se rechazan`() {
        problems(two(lat1 = 30.0, lat2 = -30.0)) shouldBe
            listOf(ParameterProblem(ProjectionParameter.STANDARD_PARALLEL_2, ProblemKind.OPPOSITE_PARALLELS))
    }

    @Test
    fun `S032 RF-3 los dos paralelos en el ecuador tambien son opuestos entre si`() {
        problems(two(lat1 = 0.0, lat2 = 0.0)) shouldBe
            listOf(ParameterProblem(ProjectionParameter.STANDARD_PARALLEL_2, ProblemKind.OPPOSITE_PARALLELS))
    }

    @Test
    fun `S032 RF-3 un paralelo estandar en un polo se rechaza y se dice cual`() {
        problems(two(lat1 = 90.0)) shouldBe listOf(ParameterProblem(ProjectionParameter.STANDARD_PARALLEL_1, ProblemKind.AT_POLE))
        problems(two(lat2 = -90.0)) shouldBe listOf(ParameterProblem(ProjectionParameter.STANDARD_PARALLEL_2, ProblemKind.AT_POLE))
    }

    @Test
    fun `S032 RF-3 en el 1SP el paralelo de origen en el ecuador se rechaza`() {
        problems(one(latOrigin = 0.0)) shouldBe listOf(ParameterProblem(ProjectionParameter.LATITUDE_OF_ORIGIN, ProblemKind.AT_EQUATOR))
    }

    @Test
    fun `S032 RF-3 un paralelo de origen en un polo se rechaza en el 1SP y en el 2SP`() {
        problems(one(latOrigin = 90.0)) shouldBe listOf(ParameterProblem(ProjectionParameter.LATITUDE_OF_ORIGIN, ProblemKind.AT_POLE))
        problems(two(latOrigin = -90.0)) shouldBe listOf(ParameterProblem(ProjectionParameter.LATITUDE_OF_ORIGIN, ProblemKind.AT_POLE))
    }

    @Test
    fun `S032 RF-3 un factor de escala que no es positivo se rechaza`() {
        listOf(0.0, -1.0, -0.9996).forEach {
            problems(one(k0 = it)) shouldBe listOf(ParameterProblem(ProjectionParameter.SCALE_FACTOR, ProblemKind.NOT_POSITIVE))
        }
        problems(one(k0 = Double.NaN)) shouldBe listOf(ParameterProblem(ProjectionParameter.SCALE_FACTOR, ProblemKind.NOT_FINITE))
    }

    @Test
    fun `S032 RF-3 una latitud fuera de menos 90 a 90 o que no es un numero se rechaza`() {
        problems(two(lat1 = 95.0)) shouldBe listOf(ParameterProblem(ProjectionParameter.STANDARD_PARALLEL_1, ProblemKind.OUT_OF_RANGE))
        problems(two(lat2 = Double.NaN)) shouldBe
            listOf(ParameterProblem(ProjectionParameter.STANDARD_PARALLEL_2, ProblemKind.NOT_FINITE))
        problems(one(latOrigin = -120.0)) shouldBe
            listOf(ParameterProblem(ProjectionParameter.LATITUDE_OF_ORIGIN, ProblemKind.OUT_OF_RANGE))
    }

    @Test
    fun `S032 RF-3 una longitud de origen fuera de menos 180 a 180 o que no es un numero se rechaza`() {
        problems(two(lon = 200.0)) shouldBe listOf(ParameterProblem(ProjectionParameter.LONGITUDE_OF_ORIGIN, ProblemKind.OUT_OF_RANGE))
        problems(one(lon = Double.POSITIVE_INFINITY)) shouldBe
            listOf(ParameterProblem(ProjectionParameter.LONGITUDE_OF_ORIGIN, ProblemKind.NOT_FINITE))
    }

    @Test
    fun `S032 RF-3 si fallan varios parametros se dicen todos a la vez`() {
        problems(one(latOrigin = 0.0, k0 = -1.0)) shouldBe listOf(
            ParameterProblem(ProjectionParameter.LATITUDE_OF_ORIGIN, ProblemKind.AT_EQUATOR),
            ParameterProblem(ProjectionParameter.SCALE_FACTOR, ProblemKind.NOT_POSITIVE),
        )
        problems(two(lat1 = 90.0, lat2 = -90.0, latOrigin = 95.0)) shouldBe listOf(
            ParameterProblem(ProjectionParameter.LATITUDE_OF_ORIGIN, ProblemKind.OUT_OF_RANGE),
            ParameterProblem(ProjectionParameter.STANDARD_PARALLEL_1, ProblemKind.AT_POLE),
            ParameterProblem(ProjectionParameter.STANDARD_PARALLEL_2, ProblemKind.AT_POLE),
        )
    }

    @Test
    fun `S032 RF-3 unos parametros validos no dan problemas ni con paralelos iguales ni con el origen del 2SP en el ecuador`() {
        listOf(two(), two(lat1 = 45.0, lat2 = 45.0), two(latOrigin = 0.0), one(), one(latOrigin = -30.0, k0 = 1.0)).forEach {
            ProjectionFactory.create(ProjectionSetup(Ellipsoid.GRS80, it)).shouldBeInstanceOf<ProjectionResult.Ok>()
        }
    }
}
