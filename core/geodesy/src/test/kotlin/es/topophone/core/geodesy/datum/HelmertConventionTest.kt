package es.topophone.core.geodesy.datum

import es.topophone.core.geodesy.Ecef
import es.topophone.core.geodesy.Ellipsoid
import es.topophone.core.geodesy.GeodeticPoint
import io.kotest.matchers.doubles.shouldBeGreaterThan
import io.kotest.matchers.doubles.shouldBeLessThan
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test

/** La convención PV o CF cambia el signo de las rotaciones y no se mezclan (S033/RF-2). */
class HelmertConventionTest {

    private val grs80 = Ellipsoid.GRS80
    private val rotation = Triple(1.5, -2.0, 3.0)
    private val translation = Triple(100.0, -200.0, 300.0)

    private fun transformation(convention: HelmertConvention): HelmertTransformation {
        val params = (
            HelmertParameters.rotations6(
                translation,
                rotation,
                RotationUnit.ARC_SECOND,
                convention,
            ) as HelmertParametersResult.Ok
            ).parameters
        return HelmertTransformation(params, grs80, grs80, TransformationInfo("conv"))
    }

    private fun ecefOf(result: TransformResult): Ecef {
        val p = result.shouldBeInstanceOf<TransformResult.Ok>().point
        return Ecef.fromGeodetic(p.latitudeDeg, p.longitudeDeg, p.ellipsoidalHeightM, grs80)
    }

    @Test
    fun `S033 RF-2 con rotaciones de unos segundos PV y CF dan puntos a mas de 10 cm`() {
        val point = GeodeticPoint(40.0, -3.0, 650.0)

        val pv = ecefOf(transformation(HelmertConvention.POSITION_VECTOR).forward(point))
        val cf = ecefOf(transformation(HelmertConvention.COORDINATE_FRAME).forward(point))

        pv.distanceTo(cf) shouldBeGreaterThan 0.1
    }

    @Test
    fun `S033 RF-2 CF con las rotaciones cambiadas de signo da lo mismo que PV`() {
        val point = GeodeticPoint(40.0, -3.0, 650.0)
        val negated = Triple(-rotation.first, -rotation.second, -rotation.third)
        val params = (
            HelmertParameters.rotations6(translation, negated, RotationUnit.ARC_SECOND, HelmertConvention.COORDINATE_FRAME) as
                HelmertParametersResult.Ok
            ).parameters

        val pv = ecefOf(transformation(HelmertConvention.POSITION_VECTOR).forward(point))
        val cf = ecefOf(HelmertTransformation(params, grs80, grs80, TransformationInfo("cf")).forward(point))

        pv.distanceTo(cf) shouldBeLessThan 1e-6
    }
}
