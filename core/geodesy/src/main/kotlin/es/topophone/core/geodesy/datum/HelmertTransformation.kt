package es.topophone.core.geodesy.datum

import es.topophone.core.geodesy.Ecef
import es.topophone.core.geodesy.Ellipsoid
import es.topophone.core.geodesy.GeodeticPoint

/**
 * Helmert de 3, 4, 6 o 7 parámetros entre dos datums, en PV o CF (S033/RF-1, RF-2; EPSG GN 7-2, métodos 9603, 9606 y 9607).
 * Pasa las geográficas 3D de [source] a cartesianas geocéntricas, aplica `X′ = T + (1 + s)·R·X` y vuelve a geográficas sobre [target].
 */
class HelmertTransformation(
    private val parameters: HelmertParameters,
    private val source: Ellipsoid,
    private val target: Ellipsoid,
    override val info: TransformationInfo,
) : DatumTransformation {

    /** Matriz de rotaciones pequeñas de EPSG: la de PV, y su traspuesta en CF (que equivale a cambiar el signo de las rotaciones). */
    private val rotation: DoubleArray = rotationMatrix(parameters)

    override fun forward(point: GeodeticPoint): TransformResult {
        if (!point.isFinite()) return TransformResult.NotConverged(null)
        val x = Ecef.fromGeodetic(point.latitudeDeg, point.longitudeDeg, point.ellipsoidalHeightM, source)
        val k = 1.0 + parameters.scale
        val r = rotation
        val shifted = Ecef(
            parameters.txM + k * (r[0] * x.x + r[1] * x.y + r[2] * x.z),
            parameters.tyM + k * (r[3] * x.x + r[4] * x.y + r[5] * x.z),
            parameters.tzM + k * (r[6] * x.x + r[7] * x.y + r[8] * x.z),
        )
        return TransformResult.Ok(shifted.toGeodetic(target))
    }

    override fun inverse(point: GeodeticPoint): TransformResult = TODO("S033 T4: inverso exacto")

    private companion object {
        fun rotationMatrix(p: HelmertParameters): DoubleArray {
            val sign = if (p.convention == HelmertConvention.POSITION_VECTOR) 1.0 else -1.0
            val rx = sign * p.rxRad
            val ry = sign * p.ryRad
            val rz = sign * p.rzRad
            return doubleArrayOf(
                1.0, -rz, ry,
                rz, 1.0, -rx,
                -ry, rx, 1.0,
            )
        }
    }
}

internal fun GeodeticPoint.isFinite(): Boolean = latitudeDeg.isFinite() && longitudeDeg.isFinite() && ellipsoidalHeightM.isFinite()
