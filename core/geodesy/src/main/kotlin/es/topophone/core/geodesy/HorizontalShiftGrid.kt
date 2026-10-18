package es.topophone.core.geodesy

/**
 * Rejilla de desplazamientos horizontales (como el `hgridshift` de PROJ) con una o varias subrejillas:
 * se usa la primera que contiene el punto, en el orden del fichero (S008/RF-7). Fuera de todas no hay
 * desplazamiento y no se extrapola (S008/RF-8, ADR-5).
 */
class HorizontalShiftGrid(private val grids: List<GeoTiffGrid>) {

    /** Desplazamiento en segundos de arco, positivo al norte y al este, y la subrejilla de la que sale. */
    data class Offset(val latitudeArcSec: Double, val longitudeArcSec: Double, val gridName: String?)

    fun offset(latitudeDeg: Double, longitudeDeg: Double): Offset? = grids.firstNotNullOfOrNull { grid ->
        val dLat = grid.interpolate(latitudeDeg, longitudeDeg, band = 0) ?: return@firstNotNullOfOrNull null
        val dLon = grid.interpolate(latitudeDeg, longitudeDeg, band = 1) ?: return@firstNotNullOfOrNull null
        // Las rejillas de PROJ dicen en sus metadatos hacia dónde es positiva la longitud.
        val east = grid.metadata["positive_value:1"] != "west"
        Offset(dLat, if (east) dLon else -dLon, grid.metadata["grid_name"])
    }

    companion object {
        fun read(bytes: ByteArray): HorizontalShiftGrid = HorizontalShiftGrid(GeoTiffGrid.readAll(bytes))
    }
}
