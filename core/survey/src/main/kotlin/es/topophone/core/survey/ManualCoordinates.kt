package es.topophone.core.survey

import es.topophone.core.geodesy.Ed50
import es.topophone.core.geodesy.Ellipsoid
import es.topophone.core.geodesy.GeoidModel
import es.topophone.core.geodesy.GeoidModels
import es.topophone.core.geodesy.Utm
import es.topophone.core.model.point.EntryForm
import es.topophone.core.model.point.GeodeticPosition
import es.topophone.core.model.point.HeightKind
import es.topophone.core.model.point.PointCoordinates
import es.topophone.core.model.point.TypedCoordinates
import es.topophone.core.model.project.CrsArea
import es.topophone.core.model.project.GeodeticDatum
import es.topophone.core.model.project.HeightSystem
import es.topophone.core.model.project.ProjectCrs

/** Un punto escrito a mano: sus coordenadas, o por qué no se puede guardar (S011/RF-19). */
sealed interface ManualResult {
    data class Computed(val coordinates: PointCoordinates) : ManualResult

    data class Rejected(val reason: PositionUnavailable) : ManualResult
}

/**
 * De las coordenadas escritas de un punto manual al punto del suelo y al SC del proyecto (S011/RF-17 a RF-20):
 * 1. latitud y longitud del datum del proyecto, por la UTM inversa si se escribieron Este y Norte;
 * 2. en ED50, a ETRS89 por la rejilla SPED2ETV2 en su sentido directo;
 * 3. fuera del ámbito del SC no se guarda;
 * 4. altura elipsoidal: la escrita, o la altitud más la ondulación del geoide del ámbito; vacía, sin altura;
 * 5. el resto de coordenadas del proyecto con [ProjectPositionCalculator.fromGround]. Lo escrito en el SC del
 *    proyecto se guarda tal cual, sin la ida y vuelta.
 */
class ManualCoordinates(
    private val calculator: ProjectPositionCalculator = ProjectPositionCalculator(),
    private val peninsulaGeoid: () -> GeoidModel = { GeoidModels.egm08RednapPeninsula },
    private val canariasGeoid: () -> GeoidModel = { GeoidModels.egm08RednapCanarias },
) {
    fun compute(typed: TypedCoordinates, crs: ProjectCrs, heightSystem: HeightSystem): ManualResult {
        val zone = crs.utmZone
        val projected = typed.form == EntryForm.PROJECT_CRS && zone != null
        val ellipsoid = if (crs.datum == GeodeticDatum.ED50) Ellipsoid.INTERNATIONAL_1924 else Ellipsoid.GRS80
        val (datumLat, datumLon) = if (projected) {
            Utm.inverse(typed.first, typed.second, zone!!, ellipsoid).let { it.latitudeDeg to it.longitudeDeg }
        } else {
            typed.first to typed.second
        }
        val (lat, lon) = if (crs.datum == GeodeticDatum.ED50) {
            Ed50.toEtrs89(datumLat, datumLon) ?: return ManualResult.Rejected(PositionUnavailable.OUTSIDE_ED50_GRID)
        } else {
            datumLat to datumLon
        }
        if (!crs.area.contains(lat, lon)) return ManualResult.Rejected(PositionUnavailable.OUTSIDE_CRS_AREA)

        val height = typed.height
        val altitudeTyped = height != null && typed.heightKind == HeightKind.PROJECT && heightSystem == HeightSystem.ORTHOMETRIC
        val ellipsoidal = when {
            height == null -> null // S011/RF-18
            altitudeTyped -> {
                val geoid = if (crs.area == CrsArea.CANARIAS) canariasGeoid() else peninsulaGeoid()
                height + (geoid.undulationM(lat, lon) ?: return ManualResult.Rejected(PositionUnavailable.OUTSIDE_GEOID))
            }
            else -> height
        }
        val ground = GeodeticPosition(lat, lon, ellipsoidal)
        val position = calculator.fromGround(ground, crs, heightSystem)
        val projectHeight = if (height != null && typed.heightKind == HeightKind.PROJECT) height else position.height
        return ManualResult.Computed(
            PointCoordinates(
                ground = ground,
                easting = if (projected) typed.first else position.easting,
                northing = if (projected) typed.second else position.northing,
                height = projectHeight,
            ),
        )
    }
}
