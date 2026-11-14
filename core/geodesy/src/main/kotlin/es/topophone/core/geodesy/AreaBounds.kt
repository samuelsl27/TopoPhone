package es.topophone.core.geodesy

import es.topophone.core.model.offline.OfflineArea
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max

/**
 * De un área de una zona sin conexión a la caja en latitud y longitud que se descarga (S037/RF-2 a RF-7, plan A1): MapLibre trabaja con cajas, así que
 * un radio se descarga como la caja que lo contiene y todo el proyecto como su rectángulo ampliado en el margen.
 *
 * La caja es un superconjunto de lo que pide la spec (todo punto a una distancia igual o menor que el radio o el margen) y se pasa de él por menos de un
 * 2 %: se amplía en `d = 1,01 · metros` por cada lado, con `Δφ = d / M(φ)` y `Δλ = d / (N(φe) · cos φe)`, y `φe` la latitud de borde más alejada del
 * ecuador (donde un metro son más grados de longitud). La referencia de `tools/mapas_offline` calcula las cajas exactas con el problema geodésico directo.
 */
object AreaBounds {
    /** El 1 % de más que cubre la variación de los radios de curvatura entre el centro y el borde. */
    private const val SAFETY = 1.01

    fun of(area: OfflineArea): GeoBox = when (area) {
        is OfflineArea.Radius -> grow(area.latitudeDeg, area.longitudeDeg, area.latitudeDeg, area.longitudeDeg, area.radiusM)
        is OfflineArea.Viewport -> GeoBox(area.south, area.west, area.north, area.east)
        is OfflineArea.ProjectExtent -> grow(area.south, area.west, area.north, area.east, area.marginM)
    }

    private fun grow(south: Double, west: Double, north: Double, east: Double, metres: Double): GeoBox {
        val d = metres * SAFETY
        val newSouth = (south - Math.toDegrees(d / Geodesy.meridianRadius(Math.toRadians(south)))).coerceAtLeast(-90.0)
        val newNorth = (north + Math.toDegrees(d / Geodesy.meridianRadius(Math.toRadians(north)))).coerceAtMost(90.0)
        val phi = Math.toRadians(max(abs(newSouth), abs(newNorth)))
        val dLon = Math.toDegrees(d / (Geodesy.primeVerticalRadius(phi) * cos(phi)))
        return GeoBox(newSouth, (west - dLon).coerceAtLeast(-180.0), newNorth, (east + dLon).coerceAtMost(180.0))
    }
}
