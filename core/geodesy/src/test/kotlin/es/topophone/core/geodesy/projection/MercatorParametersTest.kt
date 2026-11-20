package es.topophone.core.geodesy.projection

import es.topophone.core.geodesy.Ellipsoid
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test

/** Una Mercator con parámetros no válidos se rechaza e indica qué parámetro falla (S032/RF-6). */
class MercatorParametersTest {

    private fun problems(parameters: ProjectionParameters): List<ParameterProblem> =
        (ProjectionFactory.create(ProjectionSetup(Ellipsoid.GRS80, parameters)) as ProjectionResult.Invalid).problems

    private fun a(lon: Double = 110.0, k0: Double = 0.997) = MercatorA(lon, k0, 3_900_000.0, 900_000.0)

    private fun b(lat: Double = 42.0, lon: Double = 51.0) = MercatorB(lon, lat, 0.0, 0.0)

    @Test
    fun `S032 RF-6 un paralelo estandar en un polo se rechaza`() {
        problems(b(lat = 90.0)) shouldBe listOf(ParameterProblem(ProjectionParameter.STANDARD_PARALLEL, ProblemKind.AT_POLE))
        problems(b(lat = -90.0)) shouldBe listOf(ParameterProblem(ProjectionParameter.STANDARD_PARALLEL, ProblemKind.AT_POLE))
    }

    @Test
    fun `S032 RF-6 un factor de escala que no es positivo se rechaza`() {
        listOf(0.0, -1.0).forEach {
            problems(a(k0 = it)) shouldBe listOf(ParameterProblem(ProjectionParameter.SCALE_FACTOR, ProblemKind.NOT_POSITIVE))
        }
        problems(a(k0 = Double.NaN)) shouldBe listOf(ParameterProblem(ProjectionParameter.SCALE_FACTOR, ProblemKind.NOT_FINITE))
    }

    @Test
    fun `S032 RF-6 un paralelo fuera de menos 90 a 90 o que no es un numero se rechaza`() {
        problems(b(lat = 100.0)) shouldBe listOf(ParameterProblem(ProjectionParameter.STANDARD_PARALLEL, ProblemKind.OUT_OF_RANGE))
        problems(b(lat = Double.NaN)) shouldBe listOf(ParameterProblem(ProjectionParameter.STANDARD_PARALLEL, ProblemKind.NOT_FINITE))
    }

    @Test
    fun `S032 RF-6 una longitud de origen fuera de menos 180 a 180 o que no es un numero se rechaza`() {
        problems(a(lon = 181.0)) shouldBe listOf(ParameterProblem(ProjectionParameter.LONGITUDE_OF_ORIGIN, ProblemKind.OUT_OF_RANGE))
        problems(b(lon = Double.NaN)) shouldBe listOf(ParameterProblem(ProjectionParameter.LONGITUDE_OF_ORIGIN, ProblemKind.NOT_FINITE))
    }

    @Test
    fun `S032 RF-6 si fallan varios parametros se dicen todos a la vez`() {
        problems(a(lon = 200.0, k0 = -1.0)) shouldBe listOf(
            ParameterProblem(ProjectionParameter.LONGITUDE_OF_ORIGIN, ProblemKind.OUT_OF_RANGE),
            ParameterProblem(ProjectionParameter.SCALE_FACTOR, ProblemKind.NOT_POSITIVE),
        )
        problems(b(lat = 90.0, lon = 190.0)) shouldBe listOf(
            ParameterProblem(ProjectionParameter.LONGITUDE_OF_ORIGIN, ProblemKind.OUT_OF_RANGE),
            ParameterProblem(ProjectionParameter.STANDARD_PARALLEL, ProblemKind.AT_POLE),
        )
    }

    @Test
    fun `S032 RF-6 unos parametros validos no dan ningun problema ni con el paralelo en el ecuador`() {
        listOf(a(), b(), b(lat = 0.0), b(lat = -35.0)).forEach {
            ProjectionFactory.create(ProjectionSetup(Ellipsoid.GRS80, it)).shouldBeInstanceOf<ProjectionResult.Ok>()
        }
    }
}
