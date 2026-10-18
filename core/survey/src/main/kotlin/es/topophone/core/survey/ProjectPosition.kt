package es.topophone.core.survey

import es.topophone.core.geodesy.GeoidModel
import es.topophone.core.geodesy.GeoidModels
import es.topophone.core.geodesy.Utm
import es.topophone.core.gnss.nmea.GgaFix
import es.topophone.core.model.project.ProjectCrs

/** Por qué falta alguna coordenada de la posición en el SC del proyecto (S006/RF-30). */
enum class PositionUnavailable { NO_HEIGHT, OUTSIDE_CRS_AREA, OUTSIDE_GEOID }

/** Punto del suelo en el SC del proyecto, en metros; null lo que no se puede calcular. */
data class ProjectPosition(
    val easting: Double?,
    val northing: Double?,
    val orthometricHeight: Double?,
    val unavailable: Set<PositionUnavailable>,
)

/**
 * Del fix GGA y la altura del jalón al Este, Norte y altitud en el SC del proyecto, con su huso
 * fijo aunque el punto caiga en otro (huso forzado), y altitud EGM08-REDNAP (S006/RF-29, RF-30).
 */
class ProjectPositionCalculator(
    private val geoid: GeoidModel = GeoidModels.egm08RednapPeninsula,
    private val model: ReceiverModel = ReceiverModel.RS3,
) {
    fun compute(fix: GgaFix, poleHeightM: Double, crs: ProjectCrs): ProjectPosition? {
        if (!fix.hasPosition) return null
        val lat = fix.latitudeDeg
        val lon = fix.longitudeDeg
        if (lat !in AREA_LATITUDE || lon !in AREA_LONGITUDE) {
            return ProjectPosition(null, null, null, setOf(PositionUnavailable.OUTSIDE_CRS_AREA))
        }
        val unavailable = mutableSetOf<PositionUnavailable>()
        // Hasta S008 T8 solo se calculan los SC UTM; los demás, sin coordenadas.
        val utm = crs.utmZone?.let { Utm.forward(lat, lon, it) }

        // Jalón vertical, como en S002/RF-32: el suelo está bajo el receptor sobre la normal.
        val altitude = fix.altitudeM
        val separation = fix.geoidSeparationM
        val groundHeight = if (altitude != null && separation != null) {
            altitude + separation - VerticalPole.antennaHeightM(poleHeightM, model)
        } else {
            null
        }
        if (groundHeight == null) unavailable += PositionUnavailable.NO_HEIGHT
        val undulation = geoid.undulationM(lat, lon)
        if (undulation == null && groundHeight != null) unavailable += PositionUnavailable.OUTSIDE_GEOID
        val orthometric = if (groundHeight != null && undulation != null) groundHeight - undulation else null

        return ProjectPosition(utm?.easting, utm?.northing, orthometric, unavailable)
    }

    companion object {
        /** Ámbito de ETRS89 / UTM 29 a 31 en la Península y Baleares, con margen para el huso forzado. */
        val AREA_LATITUDE = 35.0..44.5
        val AREA_LONGITUDE = -10.0..5.0
    }
}
