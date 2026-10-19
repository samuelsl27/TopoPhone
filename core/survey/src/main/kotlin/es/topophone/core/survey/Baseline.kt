package es.topophone.core.survey

import es.topophone.core.geodesy.Ecef
import es.topophone.core.gnss.nmea.GgaFix

/** Línea base: la distancia en línea recta entre la antena del rover y la base (S015/RF-14, plan A7). */
object Baseline {
    /** A partir de aquí se avisa de "Línea base larga" (S015/RF-15). */
    const val LONG_M = 20_000.0

    fun distanceM(latitudeDeg: Double, longitudeDeg: Double, ellipsoidalHeightM: Double, base: Ecef): Double =
        Ecef.fromGeodetic(latitudeDeg, longitudeDeg, ellipsoidalHeightM).distanceTo(base)

    /** Con la antena de la GGA, de altura elipsoidal altitud + separación (ADR-6); `null` si falta alguna. */
    fun fromGga(fix: GgaFix, base: Ecef): Double? {
        val altitude = fix.altitudeM ?: return null
        val separation = fix.geoidSeparationM ?: return null
        if (!fix.hasPosition) return null
        return distanceM(fix.latitudeDeg, fix.longitudeDeg, altitude + separation, base)
    }

    fun isLong(distanceM: Double): Boolean = distanceM > LONG_M
}
