package es.topophone.data.surface

import es.topophone.core.model.surface.RasterTile
import es.topophone.core.model.surface.Surface
import es.topophone.core.model.surface.SurfaceHeight
import es.topophone.core.model.surface.SurfaceKind
import es.topophone.core.survey.RasterElevation
import es.topophone.core.survey.RasterTileSource
import es.topophone.core.survey.TerrainView
import es.topophone.core.survey.TinDirectory
import es.topophone.core.survey.TinElevation

/**
 * La cota de cualquier superficie del proyecto en una posición, esté activa o no (S061/RF-16, RF-18, plan A7): la que da la tarjeta al
 * tocar una superficie en el mapa. Lee de la base solo las teselas o los bloques que contienen la posición, con el mismo cálculo que
 * la superficie activa (S042/RF-22, S045/RF-21), así que da la misma cota. No aplica el desplazamiento vertical.
 */
class SurfaceProbe(private val repository: SurfaceRepository) {

    /**
     * La cota de [surface] en ([easting], [northing]) del proyecto: [SurfaceHeight.Value] o [SurfaceHeight.Outside] (fuera del contorno,
     * en un hueco o en una celda sin dato). Un ráster necesita el sistema del proyecto ([context]); sin él, [SurfaceHeight.Loading].
     */
    suspend fun heightAt(surface: Surface, easting: Double, northing: Double, context: RasterProjectContext?): SurfaceHeight =
        when (surface.kind) {
            SurfaceKind.TIN -> tin(surface, easting, northing)
            SurfaceKind.RASTER -> if (context == null) SurfaceHeight.Loading else raster(surface, easting, northing, context)
        }

    private suspend fun tin(surface: Surface, easting: Double, northing: Double): SurfaceHeight {
        if (!surface.extent.contains(easting, northing)) return SurfaceHeight.Outside
        val cells = repository.cells(surface.id)
        for (index in TinDirectory(cells.map { it.region }).candidates(easting, northing)) {
            val tile = repository.tile(cells[index].tileId) ?: continue
            TinElevation.at(tile, easting, northing)?.let { return SurfaceHeight.Value(it) }
        }
        return SurfaceHeight.Outside
    }

    /**
     * El ráster pide sus bloques por una fuente que no puede esperar a la base: se pregunta, se leen los bloques que faltaron y se
     * vuelve a preguntar. Una posición usa a lo sumo cuatro bloques, así que basta con unas pocas vueltas.
     */
    private suspend fun raster(surface: Surface, easting: Double, northing: Double, context: RasterProjectContext): SurfaceHeight {
        val loaded = HashMap<Long, RasterTile?>()
        repeat(MAX_ROUNDS) {
            val missing = ArrayList<Pair<Int, Int>>()
            val elevation = RasterElevation(
                context.crs,
                context.heightSystem,
                surface,
                RasterTileSource { column, row ->
                    val key = (column.toLong() shl 32) or row.toLong()
                    if (loaded.containsKey(key)) {
                        loaded[key]
                    } else {
                        missing += column to row
                        null
                    }
                },
                terrain = TerrainView.of(context.terrain, context.crs) ?: TerrainView.GRID,
            )
            val height = elevation.heightAt(easting, northing)
            if (height != SurfaceHeight.Loading) return height
            // Un bloque que tampoco está en la base no se puede leer: la posición no tiene cota.
            if (missing.isEmpty()) return SurfaceHeight.Outside
            for ((column, row) in missing) {
                loaded[(column.toLong() shl 32) or row.toLong()] = repository.rasterTile(surface.id, column, row)
            }
        }
        return SurfaceHeight.Outside
    }

    private companion object {
        const val MAX_ROUNDS = 6
    }
}
