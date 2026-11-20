package es.topophone.core.geodesy.projection

import es.topophone.core.geodesy.GeoPoint
import es.topophone.core.geodesy.PointFactors

/**
 * Cuenta las longitudes desde el meridiano de origen del SC (S032/RF-17; plan A6): al proyectar resta [primeMeridianDeg] a la longitud de
 * Greenwich y al desproyectar se lo suma. Con Greenwich (0°) no toca ningún valor.
 *
 * @param primeMeridianDeg longitud del meridiano de origen respecto a Greenwich, positiva al este (Madrid ≈ −3,687938°).
 */
class PrimeMeridianProjection(private val delegate: MapProjection, private val primeMeridianDeg: Double) : MapProjection {

    override fun forward(latitudeDeg: Double, longitudeDeg: Double): ProjectedPoint? =
        delegate.forward(latitudeDeg, wrap(longitudeDeg - primeMeridianDeg))

    override fun inverse(eastingM: Double, northingM: Double): GeoPoint? =
        delegate.inverse(eastingM, northingM)?.let { GeoPoint(it.latitudeDeg, wrap(it.longitudeDeg + primeMeridianDeg)) }

    override fun factors(latitudeDeg: Double, longitudeDeg: Double): PointFactors? =
        delegate.factors(latitudeDeg, wrap(longitudeDeg - primeMeridianDeg))

    private companion object {
        /** Lleva la longitud a (−180°, 180°] solo si se salió: lo que ya está dentro no se toca. */
        fun wrap(longitudeDeg: Double): Double {
            if (longitudeDeg > -180.0 && longitudeDeg <= 180.0) return longitudeDeg
            val wrapped = ((longitudeDeg + 180.0) % 360.0 + 360.0) % 360.0 - 180.0
            return if (wrapped == -180.0) 180.0 else wrapped
        }
    }
}
