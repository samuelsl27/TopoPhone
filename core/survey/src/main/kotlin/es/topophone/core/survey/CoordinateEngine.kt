package es.topophone.core.survey

import es.topophone.core.geodesy.Ed50
import es.topophone.core.geodesy.Ellipsoid
import es.topophone.core.geodesy.Geodesy
import es.topophone.core.geodesy.GeoidModel
import es.topophone.core.geodesy.Utm
import es.topophone.core.model.point.GeodeticPosition
import es.topophone.core.model.project.CrsArea
import es.topophone.core.model.project.GeodeticDatum
import es.topophone.core.model.project.HeightSystem
import es.topophone.core.model.project.SpainCrs

/** La ondulación del geoide en un punto del suelo, o por qué no hay (S034/RF-16, S008/RF-4). */
sealed interface Undulation {
    data class Value(val metres: Double) : Undulation

    /** El punto queda fuera de la cobertura del geoide. */
    data object Outside : Undulation

    /** El proyecto necesita una rejilla de geoide que no está instalada (S034/RF-16, RF-18). */
    data class MissingGrid(val name: String) : Undulation
}

/** Latitud y longitud del suelo de un punto escrito en el SC del proyecto, o por qué no se puede calcular. */
sealed interface GroundResult {
    data class Ok(val latitudeDeg: Double, val longitudeDeg: Double) : GroundResult

    data class Unavailable(val reason: PositionUnavailable, val missingGrid: String? = null) : GroundResult
}

/**
 * Cómo se pasa del punto del suelo (geográficas en el datum de las correcciones, con su altura elipsoidal) al SC del proyecto y de vuelta
 * (S006/RF-29, S008, S034/RF-26, RF-27, RF-28). Hay uno por tipo de SC: [SpainEngine] para los diez de España (el cálculo de S008, sin cambios;
 * S034/RF-25) y el de un SC de la biblioteca.
 */
interface CoordinateEngine {
    /** El punto del suelo en el SC del proyecto, con la altura del proyecto y los factores de la proyección (S008/RF-1 a RF-16). */
    fun fromGround(ground: GeodeticPosition, heightSystem: HeightSystem): ProjectPosition

    /** Del Este y el Norte (en metros) de un punto del SC proyectado del proyecto a la latitud y la longitud del suelo. */
    fun groundFromGrid(eastingM: Double, northingM: Double): GroundResult

    /** De la latitud y la longitud escritas en el datum del SC (las de un SC geográfico) a las del suelo. */
    fun groundFromDatum(latitudeDeg: Double, longitudeDeg: Double): GroundResult

    /** La ondulación del geoide del proyecto en un punto del suelo (S008/RF-4). */
    fun undulation(latitudeDeg: Double, longitudeDeg: Double): Undulation

    /** ¿Cae la posición del suelo en el ámbito en que se calcula el SC (S006/RF-30)? */
    fun containsPosition(latitudeDeg: Double, longitudeDeg: Double): Boolean
}

/**
 * Los diez SC de España (S008): UTM en el huso del proyecto o geográficas, en ETRS89, REGCAN95 o ED50 (por la rejilla SPED2ETV2), altitud con el
 * geoide de su ámbito o altura elipsoidal, y los factores de la proyección. Es el cálculo de siempre de `ProjectPositionCalculator`, movido aquí
 * sin cambiar un valor (S034/RF-25).
 */
class SpainEngine(private val crs: SpainCrs, private val peninsulaGeoid: () -> GeoidModel, private val canariasGeoid: () -> GeoidModel) :
    CoordinateEngine {

    override fun containsPosition(latitudeDeg: Double, longitudeDeg: Double): Boolean = crs.area.contains(latitudeDeg, longitudeDeg)

    override fun undulation(latitudeDeg: Double, longitudeDeg: Double): Undulation {
        val geoid = if (crs.area == CrsArea.CANARIAS) canariasGeoid() else peninsulaGeoid()
        return geoid.undulationM(latitudeDeg, longitudeDeg)?.let { Undulation.Value(it) } ?: Undulation.Outside
    }

    override fun groundFromDatum(latitudeDeg: Double, longitudeDeg: Double): GroundResult {
        if (crs.datum != GeodeticDatum.ED50) return GroundResult.Ok(latitudeDeg, longitudeDeg)
        val ground = Ed50.toEtrs89(latitudeDeg, longitudeDeg) ?: return GroundResult.Unavailable(PositionUnavailable.OUTSIDE_ED50_GRID)
        return GroundResult.Ok(ground.first, ground.second)
    }

    override fun groundFromGrid(eastingM: Double, northingM: Double): GroundResult {
        val zone = requireNotNull(crs.utmZone) { "Un SC geográfico no tiene Este y Norte" }
        val point = Utm.inverse(eastingM, northingM, zone, ellipsoid())
        return groundFromDatum(point.latitudeDeg, point.longitudeDeg)
    }

    private fun ellipsoid(): Ellipsoid = if (crs.datum == GeodeticDatum.ED50) Ellipsoid.INTERNATIONAL_1924 else Ellipsoid.GRS80

    override fun fromGround(ground: GeodeticPosition, heightSystem: HeightSystem): ProjectPosition {
        val lat = ground.latitudeDeg
        val lon = ground.longitudeDeg
        if (!crs.containsPosition(lat, lon)) return ProjectPosition(unavailable = setOf(PositionUnavailable.OUTSIDE_CRS_AREA)) // S008/RF-9

        // S008/RF-7, RF-8: ED50 sale de ETRS89 por la rejilla; fuera de ella no hay coordenadas.
        val (datumLat, datumLon) = if (crs.datum == GeodeticDatum.ED50) {
            Ed50.fromEtrs89(lat, lon) ?: return ProjectPosition(unavailable = setOf(PositionUnavailable.OUTSIDE_ED50_GRID))
        } else {
            lat to lon
        }
        val unavailable = mutableSetOf<PositionUnavailable>()
        val groundHeight = ground.ellipsoidalHeightM
        if (groundHeight == null) unavailable += PositionUnavailable.NO_HEIGHT
        val height = when (heightSystem) {
            HeightSystem.ELLIPSOIDAL -> groundHeight // S008/RF-5
            HeightSystem.ORTHOMETRIC -> {
                // S008/RF-4: el geoide del ámbito, en las geográficas ETRS89 o REGCAN95 del punto.
                val undulation = (undulation(lat, lon) as? Undulation.Value)?.metres
                if (undulation == null && groundHeight != null) unavailable += PositionUnavailable.OUTSIDE_GEOID
                if (groundHeight != null && undulation != null) groundHeight - undulation else null
            }
        }

        val zone = crs.utmZone
        if (zone == null) {
            // S008/RF-6, RF-16: geográficas, sin factores.
            return ProjectPosition(
                height = height,
                unavailable = unavailable,
                latitudeDeg = datumLat,
                longitudeDeg = datumLon,
                positionZone = Utm.zoneFor(lon),
            )
        }
        val ellipsoid = ellipsoid()
        val utm = Utm.forward(datumLat, datumLon, zone, ellipsoid)
        val factors = Utm.factors(datumLat, datumLon, zone, ellipsoid) // S008/RF-13, RF-14
        val combined = groundHeight?.let { h ->
            val radius = Geodesy.gaussMeanRadius(datumLat, ellipsoid)
            factors.scale * radius / (radius + h) // S008/RF-15
        }
        return ProjectPosition(
            easting = utm.easting,
            northing = utm.northing,
            height = height,
            unavailable = unavailable,
            scaleFactor = factors.scale,
            convergenceRad = factors.convergenceRad,
            combinedFactor = combined,
            positionZone = Utm.zoneFor(lon), // S008/RF-12
        )
    }
}
