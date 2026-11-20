package es.topophone.core.geodesy.projection

import es.topophone.core.geodesy.Ellipsoid
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test

/** Una Oblique Mercator con parámetros no válidos se rechaza e indica qué parámetro falla (S032/RF-9). */
class ObliqueMercatorParametersTest {

    private fun problems(parameters: ProjectionParameters): List<ParameterProblem> =
        (ProjectionFactory.create(ProjectionSetup(Ellipsoid.GRS80, parameters)) as ProjectionResult.Invalid).problems

    private fun a(lat: Double = 57.0, lon: Double = -133.0, azimuth: Double = 323.0, gamma: Double = 323.0, k0: Double = 0.9999) =
        ObliqueMercatorA(lat, lon, azimuth, gamma, k0, 5_000_000.0, -5_000_000.0)

    private fun b(lat: Double = 4.0, lon: Double = 115.0, azimuth: Double = 53.0, gamma: Double = 53.0, k0: Double = 0.99984) =
        ObliqueMercatorB(lat, lon, azimuth, gamma, k0, 590_476.0, 442_857.0)

    private fun problem(parameter: ProjectionParameter, kind: ProblemKind) = listOf(ParameterProblem(parameter, kind))

    @Test
    fun `S032 RF-9 una latitud del centro en un polo se rechaza`() {
        problem(ProjectionParameter.LATITUDE_OF_CENTRE, ProblemKind.AT_POLE).let {
            problems(a(lat = 90.0)) shouldBe it
            problems(b(lat = -90.0)) shouldBe it
        }
    }

    @Test
    fun `S032 RF-9 una latitud fuera de menos 90 a 90 o que no es un numero se rechaza`() {
        problems(a(lat = 100.0)) shouldBe problem(ProjectionParameter.LATITUDE_OF_CENTRE, ProblemKind.OUT_OF_RANGE)
        problems(b(lat = Double.NaN)) shouldBe problem(ProjectionParameter.LATITUDE_OF_CENTRE, ProblemKind.NOT_FINITE)
    }

    @Test
    fun `S032 RF-9 una longitud del centro fuera de menos 180 a 180 o que no es un numero se rechaza`() {
        problems(a(lon = 181.0)) shouldBe problem(ProjectionParameter.LONGITUDE_OF_CENTRE, ProblemKind.OUT_OF_RANGE)
        problems(b(lon = Double.POSITIVE_INFINITY)) shouldBe problem(ProjectionParameter.LONGITUDE_OF_CENTRE, ProblemKind.NOT_FINITE)
    }

    @Test
    fun `S032 RF-9 un azimut fuera de menos 360 a 360 grados o que no es un numero se rechaza`() {
        problems(a(azimuth = 361.0)) shouldBe problem(ProjectionParameter.AZIMUTH, ProblemKind.OUT_OF_RANGE)
        problems(b(azimuth = -400.0)) shouldBe problem(ProjectionParameter.AZIMUTH, ProblemKind.OUT_OF_RANGE)
        problems(a(azimuth = Double.NaN)) shouldBe problem(ProjectionParameter.AZIMUTH, ProblemKind.NOT_FINITE)
    }

    @Test
    fun `S032 RF-9 un angulo de rectificacion fuera de menos 360 a 360 grados o que no es un numero se rechaza`() {
        problems(a(gamma = 400.0)) shouldBe problem(ProjectionParameter.RECTIFIED_GRID_ANGLE, ProblemKind.OUT_OF_RANGE)
        problems(b(gamma = Double.NEGATIVE_INFINITY)) shouldBe problem(ProjectionParameter.RECTIFIED_GRID_ANGLE, ProblemKind.NOT_FINITE)
    }

    @Test
    fun `S032 RF-9 un factor de escala que no es positivo se rechaza`() {
        listOf(0.0, -1.0).forEach {
            problems(a(k0 = it)) shouldBe problem(ProjectionParameter.SCALE_FACTOR, ProblemKind.NOT_POSITIVE)
            problems(b(k0 = it)) shouldBe problem(ProjectionParameter.SCALE_FACTOR, ProblemKind.NOT_POSITIVE)
        }
        problems(a(k0 = Double.NaN)) shouldBe problem(ProjectionParameter.SCALE_FACTOR, ProblemKind.NOT_FINITE)
    }

    @Test
    fun `S032 RF-9 si fallan varios parametros se dicen todos a la vez y en el orden de los parametros`() {
        problems(a(lat = 90.0, lon = 200.0, azimuth = 500.0, gamma = Double.NaN, k0 = -1.0)) shouldBe listOf(
            ParameterProblem(ProjectionParameter.LATITUDE_OF_CENTRE, ProblemKind.AT_POLE),
            ParameterProblem(ProjectionParameter.LONGITUDE_OF_CENTRE, ProblemKind.OUT_OF_RANGE),
            ParameterProblem(ProjectionParameter.AZIMUTH, ProblemKind.OUT_OF_RANGE),
            ParameterProblem(ProjectionParameter.RECTIFIED_GRID_ANGLE, ProblemKind.NOT_FINITE),
            ParameterProblem(ProjectionParameter.SCALE_FACTOR, ProblemKind.NOT_POSITIVE),
        )
    }

    @Test
    fun `S032 RF-9 el falso Este y el falso Norte que no son numeros se dicen despues de los demas`() {
        problems(ObliqueMercatorB(4.0, 115.0, 53.0, 53.0, -1.0, Double.NaN, 0.0)) shouldBe listOf(
            ParameterProblem(ProjectionParameter.SCALE_FACTOR, ProblemKind.NOT_POSITIVE),
            ParameterProblem(ProjectionParameter.FALSE_EASTING, ProblemKind.NOT_FINITE),
        )
    }

    @Test
    fun `S032 RF-9 unos parametros validos no dan ningun problema ni con el centro en el ecuador ni con azimut de 0 90 180 o 270`() {
        listOf(0.0, 90.0, 180.0, 270.0, 359.99).forEach { azimuth ->
            listOf(a(azimuth = azimuth, gamma = azimuth), b(lat = 0.0, azimuth = azimuth, gamma = 12.0)).forEach {
                ProjectionFactory.create(ProjectionSetup(Ellipsoid.GRS80, it)).shouldBeInstanceOf<ProjectionResult.Ok>()
            }
        }
    }
}
