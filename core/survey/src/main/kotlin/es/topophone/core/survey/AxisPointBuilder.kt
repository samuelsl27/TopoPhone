package es.topophone.core.survey

import es.topophone.core.model.point.AxisRef
import es.topophone.core.model.point.EntryForm
import es.topophone.core.model.point.HeightKind
import es.topophone.core.model.point.Point
import es.topophone.core.model.point.PointOrigin
import es.topophone.core.model.point.TypedCoordinates
import es.topophone.core.model.project.Project

sealed interface AxisPointResult {
    data class Built(val point: Point) : AxisPointResult

    /** El punto cae fuera del ámbito del sistema del proyecto o del geoide y no se puede guardar. */
    data class Rejected(val reason: PositionUnavailable) : AxisPointResult
}

/**
 * El punto del proyecto que se guarda desde un PK y un desplazamiento de un eje (S049/RF-22, plan A7): de origen `ON_ALIGNMENT`, con su
 * Este y Norte escritos en el sistema del proyecto (se guardan tal cual, sin ida y vuelta, como un punto manual), la cota de la rasante en
 * el sistema de alturas del proyecto, o sin altura si el eje no tiene rasante, y el eje, el PK y el desplazamiento de donde salió.
 */
class AxisPointBuilder(private val manual: ManualCoordinates = ManualCoordinates()) {
    fun build(
        id: String,
        project: Project,
        name: String,
        alignmentName: String,
        point: AxisPoint,
        elevationM: Double?,
        createdAtMs: Long,
    ): AxisPointResult {
        val typed = TypedCoordinates(EntryForm.PROJECT_CRS, point.easting, point.northing, elevationM, HeightKind.PROJECT)
        val coordinates = when (val result = manual.compute(typed, project.crs, project.heightSystem)) {
            is ManualResult.Rejected -> return AxisPointResult.Rejected(result.reason)
            is ManualResult.Computed -> result.coordinates
        }
        return AxisPointResult.Built(
            Point(
                id = id,
                projectId = project.id,
                name = name,
                description = "",
                origin = PointOrigin.ON_ALIGNMENT,
                coordinates = coordinates,
                measurement = null,
                typed = typed,
                edited = false,
                hidden = false,
                copyOf = null,
                createdAtMs = createdAtMs,
                deletedAtMs = null,
                onAxis = AxisRef(alignmentName, point.chainage, point.offset),
            ),
        )
    }
}
