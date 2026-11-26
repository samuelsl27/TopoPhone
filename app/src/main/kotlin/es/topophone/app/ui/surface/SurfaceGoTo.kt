package es.topophone.app.ui.surface

import es.topophone.core.geodesy.GeoBox
import es.topophone.core.model.project.ProjectCrs
import es.topophone.core.model.surface.SurfaceExtent
import es.topophone.core.survey.CadPlacement

/** El encuadre de «Ir a la superficie» (S061/RF-14, plan A6). */
object SurfaceGoTo {
    /** Lo que se amplía la extensión por cada lado, en fracción de su ancho y su alto. */
    const val MARGIN = 0.1

    /**
     * La extensión [extent] de una superficie (en el SC del proyecto [crs]) con un margen de [MARGIN] por lado, en grados: el recuadro
     * de sus cuatro esquinas. Null si alguna cae fuera del ámbito del sistema.
     */
    fun bounds(crs: ProjectCrs, extent: SurfaceExtent): GeoBox? {
        val de = (extent.maxEasting - extent.minEasting) * MARGIN
        val dn = (extent.maxNorthing - extent.minNorthing) * MARGIN
        val corners = listOf(
            extent.minEasting - de to extent.minNorthing - dn,
            extent.maxEasting + de to extent.minNorthing - dn,
            extent.maxEasting + de to extent.maxNorthing + dn,
            extent.minEasting - de to extent.maxNorthing + dn,
        ).map { (e, n) -> CadPlacement.geographic(crs, e, n) ?: return null }
        return GeoBox(corners.minOf { it.first }, corners.minOf { it.second }, corners.maxOf { it.first }, corners.maxOf { it.second })
    }
}
