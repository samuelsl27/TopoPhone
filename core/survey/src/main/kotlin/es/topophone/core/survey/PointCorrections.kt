package es.topophone.core.survey

import es.topophone.core.model.point.EntryForm
import es.topophone.core.model.point.GeodeticPosition
import es.topophone.core.model.point.HeightKind
import es.topophone.core.model.point.Point
import es.topophone.core.model.point.PointCoordinates
import es.topophone.core.model.point.PointOrigin
import es.topophone.core.model.point.TypedCoordinates
import es.topophone.core.model.project.HeightSystem
import es.topophone.core.model.project.ProjectCrs

/** Un punto con el jalón corregido y si ha conservado sus coordenadas editadas (S011/RF-14). */
data class PoleCorrection(val point: Point, val keptEditedCoordinates: Boolean)

/**
 * Cambios de un punto que no tocan su observación bruta: la antena, las frases NMEA y la calidad (RNF-T6).
 * El historial de cada cambio lo guarda el repositorio.
 */
class PointCorrections(
    private val calculator: ProjectPositionCalculator = ProjectPositionCalculator(),
    private val model: ReceiverModel = ReceiverModel.RS3,
) {
    /**
     * El punto con otra altura del jalón: el suelo, bajo la antena guardada (jalón vertical, S002/RF-32), y sus
     * coordenadas del proyecto se recalculan (S011/RF-14). Si sus coordenadas están editadas, se mantienen y solo
     * cambia la medida. Null en un punto manual, que no tiene jalón (S011/RF-15).
     */
    fun withPoleHeight(point: Point, poleHeightM: Double, crs: ProjectCrs, heightSystem: HeightSystem): PoleCorrection? {
        val measurement = point.measurement ?: return null
        val antenna = measurement.antenna
        val groundHeight = antenna.ellipsoidalHeightM?.minus(VerticalPole.antennaHeightM(poleHeightM, model))
        val ground = GeodeticPosition(antenna.latitudeDeg, antenna.longitudeDeg, groundHeight)
        val position = calculator.fromGround(ground, crs, heightSystem)
        val measured = PointCoordinates(ground, position.easting, position.northing, position.height)
        val corrected = point.copy(
            measurement = measurement.copy(poleHeightM = poleHeightM, coordinates = measured),
            coordinates = if (point.edited) point.coordinates else measured,
        )
        return PoleCorrection(corrected, keptEditedCoordinates = point.edited)
    }

    /** Las coordenadas de la medida, sin lo escrito ni la marca de editado (S011/RF-13); null en un punto manual. */
    fun revertToMeasurement(point: Point): Point? {
        val measurement = point.measurement ?: return null
        return point.copy(coordinates = measurement.coordinates, typed = null, edited = false)
    }

    /**
     * Copia de [point] como punto manual (S011/RF-21): las mismas coordenadas y descripción, sin la medida ni su
     * calidad, porque esa medida se hizo una sola vez. Lo escrito es el del original o, si no tiene, sus
     * coordenadas del proyecto.
     */
    fun duplicate(point: Point, id: String, name: String, nowMs: Long): Point = Point(
        id = id,
        projectId = point.projectId,
        name = name,
        description = point.description,
        origin = PointOrigin.MANUAL,
        coordinates = point.coordinates,
        measurement = null,
        typed = point.typed ?: projectTyped(point.coordinates),
        edited = false,
        hidden = false,
        copyOf = point.name,
        createdAtMs = nowMs,
        deletedAtMs = null,
    )

    private fun projectTyped(coordinates: PointCoordinates): TypedCoordinates? {
        val easting = coordinates.easting ?: return null
        val northing = coordinates.northing ?: return null
        return TypedCoordinates(EntryForm.PROJECT_CRS, easting, northing, coordinates.height, HeightKind.PROJECT)
    }
}
