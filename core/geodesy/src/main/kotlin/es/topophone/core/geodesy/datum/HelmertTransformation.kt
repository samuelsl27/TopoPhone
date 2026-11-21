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
    private val inverseRotation: DoubleArray = invert(rotation)

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

    /** Inverso exacto de [forward]: `X = R⁻¹·(X′ − T)/(1 + s)` con `R⁻¹` calculada de verdad, no `R` con las rotaciones cambiadas de signo (S033/RF-5). */
    override fun inverse(point: GeodeticPoint): TransformResult {
        if (!point.isFinite()) return TransformResult.NotConverged(null)
        val y = Ecef.fromGeodetic(point.latitudeDeg, point.longitudeDeg, point.ellipsoidalHeightM, target)
        val k = 1.0 + parameters.scale
        val dx = (y.x - parameters.txM) / k
        val dy = (y.y - parameters.tyM) / k
        val dz = (y.z - parameters.tzM) / k
        val m = inverseRotation
        val back = Ecef(
            m[0] * dx + m[1] * dy + m[2] * dz,
            m[3] * dx + m[4] * dy + m[5] * dz,
            m[6] * dx + m[7] * dy + m[8] * dz,
        )
        return TransformResult.Ok(back.toGeodetic(source))
    }

    private companion object {
        /** Inversa exacta de una matriz 3×3 (fila a fila) por adjunta y determinante. */
        fun invert(a: DoubleArray): DoubleArray {
            val c0 = a[4] * a[8] - a[5] * a[7]
            val c1 = a[5] * a[6] - a[3] * a[8]
            val c2 = a[3] * a[7] - a[4] * a[6]
            val det = a[0] * c0 + a[1] * c1 + a[2] * c2
            return doubleArrayOf(
                c0 / det, (a[2] * a[7] - a[1] * a[8]) / det, (a[1] * a[5] - a[2] * a[4]) / det,
                c1 / det, (a[0] * a[8] - a[2] * a[6]) / det, (a[2] * a[3] - a[0] * a[5]) / det,
                c2 / det, (a[1] * a[6] - a[0] * a[7]) / det, (a[0] * a[4] - a[1] * a[3]) / det,
            )
        }

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
