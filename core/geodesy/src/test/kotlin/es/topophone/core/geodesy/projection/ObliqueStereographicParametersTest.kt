package es.topophone.core.geodesy.projection

import es.topophone.core.geodesy.Ellipsoid
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test

/** Una Estereográfica oblicua con parámetros no válidos se rechaza e indica qué parámetro falla (S032/RF-12). */
class ObliqueStereographicParametersTest {

    private fun problems(parameters: ProjectionParameters): List<ParameterProblem> =
        (ProjectionFactory.create(ProjectionSetup(Ellipsoid.GRS80, parameters)) as ProjectionResult.Invalid).problems

    private fun stereo(lat: Double = 52.0, lon: Double = 5.0, k0: Double = 0.9999079) =
        ObliqueStereographic(lat, lon, k0, 155_000.0, 463_000.0)

    private fun problem(parameter: ProjectionParameter, kind: ProblemKind) = listOf(ParameterProblem(parameter, kind))

    @Test
    fun `S032 RF-12 una latitud de origen fuera de menos 90 a 90 o que no es un numero se rechaza`() {
        problems(stereo(lat = 91.0)) shouldBe problem(ProjectionParameter.LATITUDE_OF_ORIGIN, ProblemKind.OUT_OF_RANGE)
        problems(stereo(lat = -120.0)) shouldBe problem(ProjectionParameter.LATITUDE_OF_ORIGIN, ProblemKind.OUT_OF_RANGE)
        problems(stereo(lat = Double.NaN)) shouldBe problem(ProjectionParameter.LATITUDE_OF_ORIGIN, ProblemKind.NOT_FINITE)
    }

    @Test
    fun `S032 RF-12 una latitud de origen en un polo se rechaza porque ahi se usa la polar`() {
        problems(stereo(lat = 90.0)) shouldBe problem(ProjectionParameter.LATITUDE_OF_ORIGIN, ProblemKind.AT_POLE)
        problems(stereo(lat = -90.0)) shouldBe problem(ProjectionParameter.LATITUDE_OF_ORIGIN, ProblemKind.AT_POLE)
    }

    @Test
    fun `S032 RF-12 una longitud de origen fuera de menos 180 a 180 o que no es un numero se rechaza`() {
        problems(stereo(lon = 181.0)) shouldBe problem(ProjectionParameter.LONGITUDE_OF_ORIGIN, ProblemKind.OUT_OF_RANGE)
        problems(stereo(lon = Double.POSITIVE_INFINITY)) shouldBe problem(ProjectionParameter.LONGITUDE_OF_ORIGIN, ProblemKind.NOT_FINITE)
    }

    @Test
    fun `S032 RF-12 un factor de escala que no es positivo se rechaza`() {
        listOf(0.0, -1.0).forEach {
            problems(stereo(k0 = it)) shouldBe problem(ProjectionParameter.SCALE_FACTOR, ProblemKind.NOT_POSITIVE)
        }
        problems(stereo(k0 = Double.NaN)) shouldBe problem(ProjectionParameter.SCALE_FACTOR, ProblemKind.NOT_FINITE)
    }

    @Test
    fun `S032 RF-12 si fallan varios parametros se dicen todos a la vez y despues los falsos`() {
        problems(ObliqueStereographic(95.0, 200.0, -1.0, Double.NaN, 0.0)) shouldBe listOf(
            ParameterProblem(ProjectionParameter.LATITUDE_OF_ORIGIN, ProblemKind.OUT_OF_RANGE),
            ParameterProblem(ProjectionParameter.LONGITUDE_OF_ORIGIN, ProblemKind.OUT_OF_RANGE),
            ParameterProblem(ProjectionParameter.SCALE_FACTOR, ProblemKind.NOT_POSITIVE),
            ParameterProblem(ProjectionParameter.FALSE_EASTING, ProblemKind.NOT_FINITE),
        )
    }

    @Test
    fun `S032 RF-12 unos parametros validos no dan ningun problema ni con el origen en el ecuador`() {
        listOf(stereo(), stereo(lat = 0.0), stereo(lat = -35.0), stereo(lat = 89.9)).forEach {
            ProjectionFactory.create(ProjectionSetup(Ellipsoid.GRS80, it)).shouldBeInstanceOf<ProjectionResult.Ok>()
        }
    }
}
