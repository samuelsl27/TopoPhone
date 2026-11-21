package es.topophone.core.survey

import es.topophone.core.geodesy.GeoidModel
import es.topophone.core.geodesy.GeoidModels
import es.topophone.core.gnss.nmea.GgaFix
import es.topophone.core.model.point.GeodeticPosition
import es.topophone.core.model.project.HeightSystem
import es.topophone.core.model.project.LibraryCrs
import es.topophone.core.model.project.ProjectCrs
import es.topophone.core.model.project.SpainCrs

/** Por qué falta alguna coordenada de la posición en el SC del proyecto (S006/RF-30, S008/RF-8). */
enum class PositionUnavailable { NO_HEIGHT, OUTSIDE_CRS_AREA, OUTSIDE_GEOID, OUTSIDE_ED50_GRID, OUTSIDE_DATUM_GRID, MISSING_GRID }

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
    /** Con [PositionUnavailable.MISSING_GRID]: el nombre de la rejilla que falta (S034/RF-18). */
    val missingGrid: String? = null,
)

/**
 * Del fix GGA y la altura del jalón al punto del suelo en el SC del proyecto (S006/RF-29, S008):
 * UTM en el huso del proyecto aunque el punto caiga en otro (huso forzado) o geográficas; ED50 por la
 * rejilla del IGN; altitud con el geoide de su ámbito o altura elipsoidal; y factores de la proyección.
 */
class ProjectPositionCalculator(
    private val peninsulaGeoid: () -> GeoidModel = { GeoidModels.egm08RednapPeninsula },
    private val canariasGeoid: () -> GeoidModel = { GeoidModels.egm08RednapCanarias },
    private val profile: AntennaProfile = AntennaProfile.RS3,
    private val libraryResources: () -> LibraryResources = { LibraryResources.current },
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
            altitude + separation - PoleGeometry.antennaHeightM(poleHeightM, profile.offsetM)
        } else {
            null
        }
        return fromGround(GeodeticPosition(fix.latitudeDeg, fix.longitudeDeg, groundHeight), crs, heightSystem)
    }

    /** El motor que calcula el SC [crs]: el de S008 para los de España y el de la biblioteca para los demás (S034/RF-25, RF-26). */
    fun engineFor(crs: ProjectCrs): CoordinateEngine = when (crs) {
        is SpainCrs -> SpainEngine(crs, peninsulaGeoid, canariasGeoid)
        is LibraryCrs -> LibraryEngines.of(crs, libraryResources())
    }

    /**
     * Del punto del suelo (geográficas ETRS89 o REGCAN95 y altura elipsoidal) al SC del proyecto: lo que hace
     * [compute] tras restar el jalón, para los puntos manuales y al corregir el jalón (S011/RF-14, RF-17).
     */
    fun fromGround(ground: GeodeticPosition, crs: ProjectCrs, heightSystem: HeightSystem = HeightSystem.ORTHOMETRIC): ProjectPosition =
        engineFor(crs).fromGround(ground, heightSystem)
}
