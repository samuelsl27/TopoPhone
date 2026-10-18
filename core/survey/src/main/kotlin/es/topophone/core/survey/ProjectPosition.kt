package es.topophone.core.survey

import es.topophone.core.geodesy.Ed50
import es.topophone.core.geodesy.Ellipsoid
import es.topophone.core.geodesy.Geodesy
import es.topophone.core.geodesy.GeoidModel
import es.topophone.core.geodesy.GeoidModels
import es.topophone.core.geodesy.Utm
import es.topophone.core.gnss.nmea.GgaFix
import es.topophone.core.model.point.GeodeticPosition
import es.topophone.core.model.project.CrsArea
import es.topophone.core.model.project.GeodeticDatum
import es.topophone.core.model.project.HeightSystem
import es.topophone.core.model.project.ProjectCrs

/** Por qué falta alguna coordenada de la posición en el SC del proyecto (S006/RF-30, S008/RF-8). */
enum class PositionUnavailable { NO_HEIGHT, OUTSIDE_CRS_AREA, OUTSIDE_GEOID, OUTSIDE_ED50_GRID }

/**
 * Punto del suelo en el SC del proyecto, en metros y grados; `null` lo que no se puede calcular.
 *
 * @property height altitud o altura elipsoidal, según el proyecto (S008/RF-2).
 * @property latitudeDeg y [longitudeDeg] en los SC de coordenadas geográficas (S008/RF-6).
 * @property scaleFactor, [convergenceRad] y [combinedFactor] solo en los SC UTM (S008/RF-13 a RF-16).
 * @property positionZone huso que corresponde a la longitud del punto (S008/RF-12).
 */
data class ProjectPosition(
    val easting: Double? = null,
    val northing: Double? = null,
    val height: Double? = null,
    val unavailable: Set<PositionUnavailable> = emptySet(),
    val latitudeDeg: Double? = null,
    val longitudeDeg: Double? = null,
    val scaleFactor: Double? = null,
    val convergenceRad: Double? = null,
    val combinedFactor: Double? = null,
    val positionZone: Int? = null,
)

/**
 * Del fix GGA y la altura del jalón al punto del suelo en el SC del proyecto (S006/RF-29, S008):
 * UTM en el huso del proyecto aunque el punto caiga en otro (huso forzado) o geográficas; ED50 por la
 * rejilla del IGN; altitud con el geoide de su ámbito o altura elipsoidal; y factores de la proyección.
 */
class ProjectPositionCalculator(
    private val peninsulaGeoid: () -> GeoidModel = { GeoidModels.egm08RednapPeninsula },
    private val canariasGeoid: () -> GeoidModel = { GeoidModels.egm08RednapCanarias },
    private val model: ReceiverModel = ReceiverModel.RS3,
) {
    fun compute(
        fix: GgaFix,
        poleHeightM: Double,
        crs: ProjectCrs,
        heightSystem: HeightSystem = HeightSystem.ORTHOMETRIC,
    ): ProjectPosition? {
        if (!fix.hasPosition) return null
        // Jalón vertical, como en S002/RF-32: el suelo está bajo el receptor sobre la normal.
        val altitude = fix.altitudeM
        val separation = fix.geoidSeparationM
        val groundHeight = if (altitude != null && separation != null) {
            altitude + separation - VerticalPole.antennaHeightM(poleHeightM, model)
        } else {
            null
        }
        return fromGround(GeodeticPosition(fix.latitudeDeg, fix.longitudeDeg, groundHeight), crs, heightSystem)
    }

    /**
     * Del punto del suelo (geográficas ETRS89 o REGCAN95 y altura elipsoidal) al SC del proyecto: lo que hace
     * [compute] tras restar el jalón, para los puntos manuales y al corregir el jalón (S011/RF-14, RF-17).
     */
    fun fromGround(ground: GeodeticPosition, crs: ProjectCrs, heightSystem: HeightSystem = HeightSystem.ORTHOMETRIC): ProjectPosition {
        val lat = ground.latitudeDeg
        val lon = ground.longitudeDeg
        if (!crs.area.contains(lat, lon)) return ProjectPosition(unavailable = setOf(PositionUnavailable.OUTSIDE_CRS_AREA)) // S008/RF-9

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
                val geoid = if (crs.area == CrsArea.CANARIAS) canariasGeoid() else peninsulaGeoid()
                val undulation = geoid.undulationM(lat, lon)
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
        val ellipsoid = if (crs.datum == GeodeticDatum.ED50) Ellipsoid.INTERNATIONAL_1924 else Ellipsoid.GRS80
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
