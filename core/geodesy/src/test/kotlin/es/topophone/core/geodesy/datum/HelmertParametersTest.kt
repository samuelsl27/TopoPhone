package es.topophone.core.geodesy.datum

import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test

/** Parámetros de Helmert: factorías de 3, 4, 6 y 7, unidades de entrada a SI y rechazo de valores no finitos (S033/RF-4, RF-6). */
class HelmertParametersTest {

    private fun ok(result: HelmertParametersResult): HelmertParameters = result.shouldBeInstanceOf<HelmertParametersResult.Ok>().parameters

    @Test
    fun `S033 RF-4 los segundos de arco pasan a radianes y los ppm a adimensional`() {
        val p = ok(
            HelmertParameters.seven(
                translationM = Triple(1.0, 2.0, 3.0),
                rotation = Triple(1.0, -2.0, 3.0),
                rotationUnit = RotationUnit.ARC_SECOND,
                scalePpm = 10.0,
                convention = HelmertConvention.COORDINATE_FRAME,
            ),
        )

        p.txM shouldBe 1.0
        p.tyM shouldBe 2.0
        p.tzM shouldBe 3.0
        p.rxRad shouldBe (Math.toRadians(1.0 / 3600.0) plusOrMinus 1e-18)
        p.ryRad shouldBe (Math.toRadians(-2.0 / 3600.0) plusOrMinus 1e-18)
        p.rzRad shouldBe (Math.toRadians(3.0 / 3600.0) plusOrMinus 1e-18)
        p.scale shouldBe (1e-5 plusOrMinus 1e-20)
        p.convention shouldBe HelmertConvention.COORDINATE_FRAME
    }

    @Test
    fun `S033 RF-4 los radianes se guardan tal cual`() {
        val p = ok(
            HelmertParameters.rotations6(
                translationM = Triple(0.0, 0.0, 0.0),
                rotation = Triple(1e-6, 2e-6, 3e-6),
                rotationUnit = RotationUnit.RADIAN,
                convention = HelmertConvention.POSITION_VECTOR,
            ),
        )

        p.rxRad shouldBe 1e-6
        p.ryRad shouldBe 2e-6
        p.rzRad shouldBe 3e-6
    }

    @Test
    fun `S033 RF-1 las factorias de 3 4 6 y 7 parametros ponen a cero lo que no usan`() {
        val three = ok(HelmertParameters.translations3(Triple(1.0, 2.0, 3.0)))
        three.rxRad shouldBe 0.0
        three.ryRad shouldBe 0.0
        three.rzRad shouldBe 0.0
        three.scale shouldBe 0.0

        val four = ok(HelmertParameters.translationsScale4(Triple(1.0, 2.0, 3.0), scalePpm = 4.0))
        four.rxRad shouldBe 0.0
        four.rzRad shouldBe 0.0
        four.scale shouldBe (4e-6 plusOrMinus 1e-20)

        val six = ok(
            HelmertParameters.rotations6(
                Triple(1.0, 2.0, 3.0),
                Triple(1.0, 1.0, 1.0),
                RotationUnit.ARC_SECOND,
                HelmertConvention.POSITION_VECTOR,
            ),
        )
        six.scale shouldBe 0.0
        (six.rxRad > 0.0) shouldBe true
    }

    @Test
    fun `S033 RF-6 un parametro no finito se rechaza y se indica cual`() {
        val result = HelmertParameters.translations3(Triple(Double.NaN, 2.0, 3.0))

        result.shouldBeInstanceOf<HelmertParametersResult.Invalid>().problems shouldContainExactlyInAnyOrder
            listOf(ParameterProblem(DatumParameter.TRANSLATION_X, ProblemKind.NOT_FINITE))
    }

    @Test
    fun `S033 RF-6 todos los problemas salen a la vez`() {
        val result = HelmertParameters.seven(
            translationM = Triple(0.0, Double.POSITIVE_INFINITY, 0.0),
            rotation = Triple(Double.NaN, 0.0, Double.NEGATIVE_INFINITY),
            rotationUnit = RotationUnit.ARC_SECOND,
            scalePpm = Double.NaN,
            convention = HelmertConvention.POSITION_VECTOR,
        )

        result.shouldBeInstanceOf<HelmertParametersResult.Invalid>().problems shouldContainExactlyInAnyOrder
            listOf(
                ParameterProblem(DatumParameter.TRANSLATION_Y, ProblemKind.NOT_FINITE),
                ParameterProblem(DatumParameter.ROTATION_X, ProblemKind.NOT_FINITE),
                ParameterProblem(DatumParameter.ROTATION_Z, ProblemKind.NOT_FINITE),
                ParameterProblem(DatumParameter.SCALE, ProblemKind.NOT_FINITE),
            )
    }

    @Test
    fun `S033 RF-14 el ambito contiene sus bordes y la transformacion se describe`() {
        val area = AreaOfUse(southDeg = 35.0, northDeg = 44.0, westDeg = -10.0, eastDeg = 5.0)

        area.contains(40.0, -3.0) shouldBe true
        area.contains(35.0, 5.0) shouldBe true
        area.contains(34.9, -3.0) shouldBe false
        area.contains(40.0, 5.1) shouldBe false
        TransformationInfo("Prueba", epsgCode = 1234, accuracyM = 1.5, areaOfUse = area).accuracyM shouldBe 1.5
    }
}
