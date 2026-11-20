package es.topophone.core.geodesy.projection

import es.topophone.core.geodesy.Ellipsoid
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test

/** Una Estereográfica polar con parámetros no válidos se rechaza e indica qué parámetro falla (S032/RF-15). */
class PolarStereographicParametersTest {

    private fun problems(parameters: ProjectionParameters): List<ParameterProblem> =
        (ProjectionFactory.create(ProjectionSetup(Ellipsoid.GRS80, parameters)) as ProjectionResult.Invalid).problems

    private fun a(lat: Double = 90.0, lon: Double = 0.0, k0: Double = 0.994) = PolarStereographicA(lat, lon, k0, 2_000_000.0, 2_000_000.0)

    private fun b(lat: Double = -71.0, lon: Double = 0.0) = PolarStereographicB(lat, lon, 0.0, 0.0)

    private fun c(lat: Double = -67.0, lon: Double = 140.0) = PolarStereographicC(lat, lon, 300_000.0, 200_000.0)

    private fun problem(parameter: ProjectionParameter, kind: ProblemKind) = listOf(ParameterProblem(parameter, kind))

    @Test
    fun `S032 RF-15 en la A una latitud de origen que no es un polo se rechaza`() {
        problems(a(lat = 89.0)) shouldBe problem(ProjectionParameter.LATITUDE_OF_ORIGIN, ProblemKind.NOT_A_POLE)
        problems(a(lat = 0.0)) shouldBe problem(ProjectionParameter.LATITUDE_OF_ORIGIN, ProblemKind.NOT_A_POLE)
        problems(a(lat = -45.0)) shouldBe problem(ProjectionParameter.LATITUDE_OF_ORIGIN, ProblemKind.NOT_A_POLE)
    }

    @Test
    fun `S032 RF-15 una latitud fuera de menos 90 a 90 o que no es un numero se rechaza en las tres variantes`() {
        problems(a(lat = 91.0)) shouldBe problem(ProjectionParameter.LATITUDE_OF_ORIGIN, ProblemKind.OUT_OF_RANGE)
        problems(b(lat = -100.0)) shouldBe problem(ProjectionParameter.STANDARD_PARALLEL, ProblemKind.OUT_OF_RANGE)
        problems(c(lat = 120.0)) shouldBe problem(ProjectionParameter.STANDARD_PARALLEL, ProblemKind.OUT_OF_RANGE)
        problems(a(lat = Double.NaN)) shouldBe problem(ProjectionParameter.LATITUDE_OF_ORIGIN, ProblemKind.NOT_FINITE)
        problems(b(lat = Double.NaN)) shouldBe problem(ProjectionParameter.STANDARD_PARALLEL, ProblemKind.NOT_FINITE)
    }

    @Test
    fun `S032 RF-15 en la B y la C un paralelo estandar en el ecuador no dice a que polo pertenece y se rechaza`() {
        problems(b(lat = 0.0)) shouldBe problem(ProjectionParameter.STANDARD_PARALLEL, ProblemKind.AT_EQUATOR)
        problems(c(lat = 0.0)) shouldBe problem(ProjectionParameter.STANDARD_PARALLEL, ProblemKind.AT_EQUATOR)
    }

    @Test
    fun `S032 RF-15 una longitud de origen fuera de menos 180 a 180 o que no es un numero se rechaza`() {
        problems(a(lon = 181.0)) shouldBe problem(ProjectionParameter.LONGITUDE_OF_ORIGIN, ProblemKind.OUT_OF_RANGE)
        problems(b(lon = Double.NaN)) shouldBe problem(ProjectionParameter.LONGITUDE_OF_ORIGIN, ProblemKind.NOT_FINITE)
        problems(c(lon = -200.0)) shouldBe problem(ProjectionParameter.LONGITUDE_OF_ORIGIN, ProblemKind.OUT_OF_RANGE)
    }

    @Test
    fun `S032 RF-15 un factor de escala que no es positivo se rechaza en la A`() {
        listOf(0.0, -1.0).forEach {
            problems(a(k0 = it)) shouldBe problem(ProjectionParameter.SCALE_FACTOR, ProblemKind.NOT_POSITIVE)
        }
        problems(a(k0 = Double.NaN)) shouldBe problem(ProjectionParameter.SCALE_FACTOR, ProblemKind.NOT_FINITE)
    }

    @Test
    fun `S032 RF-15 si fallan varios parametros se dicen todos a la vez y en el orden de los parametros`() {
        problems(a(lat = 45.0, lon = 200.0, k0 = -1.0)) shouldBe listOf(
            ParameterProblem(ProjectionParameter.LATITUDE_OF_ORIGIN, ProblemKind.NOT_A_POLE),
            ParameterProblem(ProjectionParameter.LONGITUDE_OF_ORIGIN, ProblemKind.OUT_OF_RANGE),
            ParameterProblem(ProjectionParameter.SCALE_FACTOR, ProblemKind.NOT_POSITIVE),
        )
        problems(PolarStereographicC(0.0, 190.0, Double.NaN, 0.0)) shouldBe listOf(
            ParameterProblem(ProjectionParameter.STANDARD_PARALLEL, ProblemKind.AT_EQUATOR),
            ParameterProblem(ProjectionParameter.LONGITUDE_OF_ORIGIN, ProblemKind.OUT_OF_RANGE),
            ParameterProblem(ProjectionParameter.FALSE_EASTING, ProblemKind.NOT_FINITE),
        )
    }

    @Test
    fun `S032 RF-15 unos parametros validos no dan ningun problema ni con el paralelo estandar en el polo`() {
        listOf(a(), a(lat = -90.0), b(), b(lat = 90.0), b(lat = -90.0), c(), c(lat = 75.0)).forEach {
            ProjectionFactory.create(ProjectionSetup(Ellipsoid.GRS80, it)).shouldBeInstanceOf<ProjectionResult.Ok>()
        }
    }
}
