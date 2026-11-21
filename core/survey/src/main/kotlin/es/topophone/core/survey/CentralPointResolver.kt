package es.topophone.core.survey

import es.topophone.core.gnss.nmea.GgaFix
import es.topophone.core.model.point.EntryForm
import es.topophone.core.model.point.HeightKind
import es.topophone.core.model.point.TypedCoordinates
import es.topophone.core.model.project.CentralPoint
import es.topophone.core.model.project.HeightSystem
import es.topophone.core.model.project.ProjectCrs

/** Por qué no se puede fijar el punto central (S006/RF-34). */
enum class CentralPointRejection { NO_POSITION, OUTSIDE_CRS_AREA, OUTSIDE_ED50_GRID, INVALID_NUMBER }

/** El punto central fijado, o el motivo de que no se pueda (S006/RF-33, RF-34). */
sealed interface CentralPointResult {
    data class Fixed(val point: CentralPoint) : CentralPointResult

    data class Rejected(val reason: CentralPointRejection) : CentralPointResult
}

/**
 * Del dato que da el usuario al punto central: latitud y longitud del suelo sin redondear (S006/RF-32). Es solo
 * horizontal, así que no interviene el geoide. Lo escrito en el SC del proyecto sigue la misma ruta que un punto manual
 * (S011/RF-17) y todo debe caer en el ámbito del SC del proyecto, como la posición de RF-30.
 */
class CentralPointResolver(private val manual: ManualCoordinates = ManualCoordinates()) {

    /** Coordenadas escritas: Este y Norte en el SC del proyecto, o latitud y longitud de su datum (S006/RF-33). */
    fun fromTyped(form: EntryForm, first: Double, second: Double, crs: ProjectCrs): CentralPointResult {
        if (!first.isFinite() || !second.isFinite()) return CentralPointResult.Rejected(CentralPointRejection.INVALID_NUMBER)
        val typed = TypedCoordinates(form, first, second, height = null, heightKind = HeightKind.PROJECT)
        return when (val result = manual.compute(typed, crs, HeightSystem.ELLIPSOIDAL)) {
            is ManualResult.Computed -> CentralPointResult.Fixed(
                CentralPoint(result.coordinates.ground.latitudeDeg, result.coordinates.ground.longitudeDeg),
            )
            is ManualResult.Rejected -> CentralPointResult.Rejected(rejection(result.reason))
        }
    }

    private fun rejection(reason: PositionUnavailable) = if (reason ==
        PositionUnavailable.OUTSIDE_ED50_GRID
    ) {
        CentralPointRejection.OUTSIDE_ED50_GRID
    } else {
        CentralPointRejection.OUTSIDE_CRS_AREA
    }

    /** Un toque en el mapa (S006/RF-33). */
    fun fromTap(latitudeDeg: Double, longitudeDeg: Double, crs: ProjectCrs): CentralPointResult = inArea(latitudeDeg, longitudeDeg, crs)

    /** La posición actual del receptor o de una reproducción, en cualquier solución; `null` o sin posición, no hay (S006/RF-34). */
    fun fromFix(fix: GgaFix?, crs: ProjectCrs): CentralPointResult {
        if (fix == null || !fix.hasPosition) return CentralPointResult.Rejected(CentralPointRejection.NO_POSITION)
        return inArea(fix.latitudeDeg, fix.longitudeDeg, crs)
    }

    private fun inArea(latitudeDeg: Double, longitudeDeg: Double, crs: ProjectCrs): CentralPointResult = when {
        !latitudeDeg.isFinite() || !longitudeDeg.isFinite() -> CentralPointResult.Rejected(CentralPointRejection.INVALID_NUMBER)
        !crs.containsPosition(latitudeDeg, longitudeDeg) -> CentralPointResult.Rejected(CentralPointRejection.OUTSIDE_CRS_AREA)
        else -> CentralPointResult.Fixed(CentralPoint(latitudeDeg, longitudeDeg))
    }
}
