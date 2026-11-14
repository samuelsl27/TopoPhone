package es.topophone.core.survey.cogo

import es.topophone.core.model.cogo.CogoRef
import es.topophone.core.model.point.EntryForm
import es.topophone.core.model.point.HeightKind
import es.topophone.core.model.point.Point
import es.topophone.core.model.point.PointOrigin
import es.topophone.core.model.point.TypedCoordinates
import es.topophone.core.model.project.Project
import es.topophone.core.survey.ManualCoordinates
import es.topophone.core.survey.ManualResult
import es.topophone.core.survey.PositionUnavailable

sealed interface CogoPointResult {
    data class Built(val point: Point) : CogoPointResult

    /** El punto cae fuera del ámbito del sistema del proyecto o del geoide y no se puede guardar (S038/RF-21). */
    data class Rejected(val reason: PositionUnavailable) : CogoPointResult
}

/**
 * El punto del proyecto que se guarda desde un resultado de COGO (S038/RF-22), como [es.topophone.core.survey.AxisPointBuilder]: de origen
 * `COGO`, con su Este y Norte escritos en el sistema del proyecto (se guardan tal cual, sin ida y vuelta, como un punto manual), la altura
 * en el sistema de alturas del proyecto o, sin ella, sin altura (S038/RF-18), sin jalón ni calidad, y el rastro del cálculo.
 */
class CogoPointBuilder(private val manual: ManualCoordinates = ManualCoordinates()) {
    fun build(
        id: String,
        project: Project,
        name: String,
        easting: Double,
        northing: Double,
        height: Double?,
        ref: CogoRef,
        createdAtMs: Long,
    ): CogoPointResult {
        val typed = TypedCoordinates(EntryForm.PROJECT_CRS, easting, northing, height, HeightKind.PROJECT)
        val coordinates = when (val result = manual.compute(typed, project.crs, project.heightSystem)) {
            is ManualResult.Rejected -> return CogoPointResult.Rejected(result.reason)
            is ManualResult.Computed -> result.coordinates
        }
        return CogoPointResult.Built(
            Point(
                id = id,
                projectId = project.id,
                name = name,
                description = "",
                origin = PointOrigin.COGO,
                coordinates = coordinates,
                measurement = null,
                typed = typed,
                edited = false,
                hidden = false,
                copyOf = null,
                createdAtMs = createdAtMs,
                deletedAtMs = null,
                cogo = ref,
            ),
        )
    }
}
