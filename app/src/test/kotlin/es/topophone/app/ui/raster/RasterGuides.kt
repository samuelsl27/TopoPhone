package es.topophone.app.ui.raster

import es.topophone.core.model.cad.CadSourceCrs
import es.topophone.core.model.project.HeightSystem
import es.topophone.core.model.project.LinearUnit
import es.topophone.core.model.project.ProjectCrs
import es.topophone.core.model.surface.RasterInfo
import es.topophone.core.model.surface.RasterTile
import es.topophone.core.model.surface.RasterValueType
import es.topophone.core.model.surface.Surface
import es.topophone.core.model.surface.SurfaceExtent
import es.topophone.core.model.surface.SurfaceHeight
import es.topophone.core.model.surface.SurfaceHeightSource
import es.topophone.core.model.surface.SurfaceKind
import es.topophone.core.survey.RasterElevation
import es.topophone.core.survey.RasterTileSource

/**
 * Un ráster de cotas de verdad (`RasterElevation` sobre un bloque de celdas) que da la cota que se le pida, para probar el replanteo y
 * el recolector contra un ráster con los mismos escenarios que con un TIN (S045/RF-19, RF-24, RF-25): una malla de 3 por 3 celdas de
 * 100 km en el huso 30 que cubre toda la Península, a una cota constante, sin dato o con el bloque sin cargar.
 */
object RasterGuides {
    private const val CELL_M = 100_000.0
    private const val ORIGIN_E = 350_000.0
    private const val ORIGIN_N = 4_550_000.0

    /** La superficie de tipo ráster con la que se arma la guía: el mismo nombre y fichero que usan los tests con un TIN. */
    fun surface(projectId: String, importedAtMs: Long, id: String = "s1") = Surface(
        id, projectId, "terreno", "obra.xml", importedAtMs, CadSourceCrs.Project, LinearUnit.METRE,
        SurfaceExtent(ORIGIN_E, ORIGIN_E + 2 * CELL_M, ORIGIN_N - 2 * CELL_M, ORIGIN_N), 90.0, 120.0, 0, 0, 0,
        kind = SurfaceKind.RASTER,
        raster = RasterInfo(
            3, 3, ORIGIN_E, ORIGIN_N, CELL_M, CELL_M, RasterValueType.FLOAT64, HeightSystem.ORTHOMETRIC, 9, 0,
        ),
    )

    /**
     * La cota que se pide: [SurfaceHeight.Value] es un ráster constante en esa cota, [SurfaceHeight.Outside] uno sin dato en la zona y
     * [SurfaceHeight.Loading] uno cuyo bloque no está en memoria. [onQuery] avisa de cada posición consultada.
     */
    fun source(height: SurfaceHeight, projectId: String = "p1", onQuery: (Double, Double) -> Unit = { _, _ -> }): SurfaceHeightSource {
        val tiles = RasterTileSource { column, row ->
            if (column != 0 || row != 0) {
                null
            } else {
                when (height) {
                    is SurfaceHeight.Value -> RasterTile.ofDoubles(DoubleArray(RasterTile.CELLS) { height.metres })
                    SurfaceHeight.Outside -> RasterTile.ofDoubles(DoubleArray(RasterTile.CELLS) { Double.NaN })
                    SurfaceHeight.Loading -> null
                }
            }
        }
        val elevation = RasterElevation(ProjectCrs.ETRS89_UTM30, HeightSystem.ORTHOMETRIC, surface(projectId, 0L), tiles)
        return SurfaceHeightSource { e, n ->
            onQuery(e, n)
            elevation.heightAt(e, n)
        }
    }
}
