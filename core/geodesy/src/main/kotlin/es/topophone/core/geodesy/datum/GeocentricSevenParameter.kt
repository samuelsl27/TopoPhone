package es.topophone.core.geodesy.datum

import es.topophone.core.geodesy.Ecef

/**
 * El cálculo de Helmert y de Molodensky-Badekas sobre cartesianas geocéntricas: `X′ = T + P + (1 + s)·R·(X − P)` con el punto de rotación
 * `P` (en el centro de la Tierra, `P = 0`, es el Helmert; S033/RF-1, RF-3). La inversa es exacta, con `R⁻¹` calculada de verdad y no con
 * las rotaciones cambiadas de signo (S033/RF-5). `R` es la matriz de rotaciones pequeñas de EPSG: la de PV y su traspuesta en CF.
 */
internal class GeocentricSevenParameter(private val parameters: HelmertParameters, private val pivot: Ecef = Ecef(0.0, 0.0, 0.0)) {
    private val rotation: DoubleArray = rotationMatrix(parameters)
    private val inverseRotation: DoubleArray = invert(rotation)
    private val k = 1.0 + parameters.scale

    fun forward(x: Ecef): Ecef {
        val dx = x.x - pivot.x
        val dy = x.y - pivot.y
        val dz = x.z - pivot.z
        val r = rotation
        return Ecef(
            parameters.txM + pivot.x + k * (r[0] * dx + r[1] * dy + r[2] * dz),
            parameters.tyM + pivot.y + k * (r[3] * dx + r[4] * dy + r[5] * dz),
            parameters.tzM + pivot.z + k * (r[6] * dx + r[7] * dy + r[8] * dz),
        )
    }

    fun inverse(y: Ecef): Ecef {
        val dx = (y.x - parameters.txM - pivot.x) / k
        val dy = (y.y - parameters.tyM - pivot.y) / k
        val dz = (y.z - parameters.tzM - pivot.z) / k
        val m = inverseRotation
        return Ecef(
            pivot.x + m[0] * dx + m[1] * dy + m[2] * dz,
            pivot.y + m[3] * dx + m[4] * dy + m[5] * dz,
            pivot.z + m[6] * dx + m[7] * dy + m[8] * dz,
        )
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
