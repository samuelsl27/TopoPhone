package es.topophone.core.geodesy

import kotlin.math.abs

/**
 * ED50 ↔ ETRS89 en la Península y Baleares con la transformación oficial del IGN (EPSG:15932, rejilla
 * SPED2ETV2) (S008/RF-7). La rejilla da los desplazamientos en ED50; de ETRS89 a ED50 se invierte por
 * iteración, como el `hgridshift` inverso de PROJ. Fuera de la rejilla no hay ED50 (S008/RF-8, ADR-5).
 */
object Ed50 {
    const val ATTRIBUTION = "ED50 a ETRS89 (SPED2ETV2) © Instituto Geográfico Nacional (IGN), CC-BY 4.0"

    private const val ARC_SECONDS_PER_DEGREE = 3600.0
    private const val TOLERANCE_DEG = 1e-12
    private const val MAX_ITERATIONS = 20

    private val grid: HorizontalShiftGrid by lazy {
        val bytes = Ed50::class.java.getResourceAsStream("/shift/es_ign_SPED2ETV2.tif")?.use { it.readBytes() }
            ?: error("Falta la rejilla SPED2ETV2")
        HorizontalShiftGrid.read(bytes)
    }

    /** ED50 → ETRS89 (latitud, longitud en grados), o `null` fuera de la rejilla. */
    fun toEtrs89(latitudeDeg: Double, longitudeDeg: Double): Pair<Double, Double>? {
        val offset = grid.offset(latitudeDeg, longitudeDeg) ?: return null
        return (latitudeDeg + offset.latitudeArcSec / ARC_SECONDS_PER_DEGREE) to
            (longitudeDeg + offset.longitudeArcSec / ARC_SECONDS_PER_DEGREE)
    }

    /** ETRS89 → ED50 (latitud, longitud en grados), o `null` fuera de la rejilla. */
    fun fromEtrs89(latitudeDeg: Double, longitudeDeg: Double): Pair<Double, Double>? {
        var lat = latitudeDeg
        var lon = longitudeDeg
        repeat(MAX_ITERATIONS) {
            val offset = grid.offset(lat, lon) ?: return null
            val nextLat = latitudeDeg - offset.latitudeArcSec / ARC_SECONDS_PER_DEGREE
            val nextLon = longitudeDeg - offset.longitudeArcSec / ARC_SECONDS_PER_DEGREE
            val converged = abs(nextLat - lat) < TOLERANCE_DEG && abs(nextLon - lon) < TOLERANCE_DEG
            lat = nextLat
            lon = nextLon
            if (converged) return lat to lon
        }
        return lat to lon
    }
}
