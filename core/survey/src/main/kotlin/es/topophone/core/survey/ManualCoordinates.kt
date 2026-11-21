package es.topophone.core.survey

import es.topophone.core.model.point.EntryForm
import es.topophone.core.model.point.GeodeticPosition
import es.topophone.core.model.point.HeightKind
import es.topophone.core.model.point.PointCoordinates
import es.topophone.core.model.point.TypedCoordinates
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
class ManualCoordinates(private val calculator: ProjectPositionCalculator = ProjectPositionCalculator()) {
    fun compute(typed: TypedCoordinates, crs: ProjectCrs, heightSystem: HeightSystem): ManualResult {
        val engine = calculator.engineFor(crs)
        val projected = typed.form == EntryForm.PROJECT_CRS && crs.isUtm
        // 1 y 2: del SC del proyecto (Este y Norte, o latitud y longitud de su datum) al suelo; en ED50, por la rejilla en su sentido directo.
        val ground = when (
            val result = if (projected) {
                engine.groundFromGrid(
                    typed.first,
                    typed.second,
                )
            } else {
                engine.groundFromDatum(typed.first, typed.second)
            }
        ) {
            is GroundResult.Ok -> result
            is GroundResult.Unavailable -> return ManualResult.Rejected(result.reason)
        }
        val lat = ground.latitudeDeg
        val lon = ground.longitudeDeg
        if (!engine.containsPosition(lat, lon)) return ManualResult.Rejected(PositionUnavailable.OUTSIDE_CRS_AREA)

        val height = typed.height
        val altitudeTyped = height != null && typed.heightKind == HeightKind.PROJECT && heightSystem == HeightSystem.ORTHOMETRIC
        val ellipsoidal = when {
            height == null -> null // S011/RF-18
            altitudeTyped -> when (val undulation = engine.undulation(lat, lon)) {
                is Undulation.Value -> height + undulation.metres
                Undulation.Outside -> return ManualResult.Rejected(PositionUnavailable.OUTSIDE_GEOID)
                is Undulation.MissingGrid -> return ManualResult.Rejected(PositionUnavailable.MISSING_GRID)
            }
            else -> height
        }
        val groundPosition = GeodeticPosition(lat, lon, ellipsoidal)
        val position = engine.fromGround(groundPosition, heightSystem)
        val projectHeight = if (height != null && typed.heightKind == HeightKind.PROJECT) height else position.height
        return ManualResult.Computed(
            PointCoordinates(
                ground = groundPosition,
                easting = if (projected) typed.first else position.easting,
                northing = if (projected) typed.second else position.northing,
                height = projectHeight,
            ),
        )
    }
}
