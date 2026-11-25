package es.topophone.app.ui.surface

import es.topophone.core.geodesy.GeoBox
import es.topophone.core.model.cad.CadSourceCrs
import es.topophone.core.model.point.GeodeticPosition
import es.topophone.core.model.project.HeightSystem
import es.topophone.core.model.project.LinearUnit
import es.topophone.core.model.project.ProjectCrs
import es.topophone.core.model.project.TerrainPlane
import es.topophone.core.model.surface.ElevationRamp
import es.topophone.core.model.surface.RasterTile
import es.topophone.core.model.surface.Surface
import es.topophone.core.model.surface.SurfaceExtent
import es.topophone.core.model.surface.SurfaceKind
import es.topophone.core.survey.CadPlacement
import es.topophone.core.survey.ProjectPositionCalculator
import es.topophone.core.survey.RasterElevation
import es.topophone.core.survey.RasterTileSource
import es.topophone.core.survey.RasterView
import es.topophone.core.survey.RasterViewSelector
import es.topophone.core.survey.SurfaceProjection
import es.topophone.core.survey.SurfaceProjector
import es.topophone.core.survey.SurfaceViewSelector
import es.topophone.core.survey.TerrainView
import es.topophone.core.survey.TileBox
import es.topophone.core.survey.TinView
import es.topophone.data.surface.SurfaceRepository

/** El proyecto que dibuja sus superficies: su SC, su sistema de alturas y su plano de terreno, que un ráster necesita (S045, S058). */
data class SurfaceProjectFrame(val crs: ProjectCrs, val heightSystem: HeightSystem, val terrain: TerrainPlane?)

/**
 * Construye lo que el mapa dibuja de las superficies visibles cuando la cámara se para (S061, plan A3 a A5): el contorno de todas (el
 * de un TIN guardado, el de un ráster su recuadro), y en la vista, la malla de un TIN o la imagen de un ráster si caben, coloreados con
 * la rampa común de las visibles. Los contornos ya convertidos a longitud y latitud se guardan para no repetirlo al mover el mapa.
 */
class SurfaceSceneBuilder(
    private val repository: SurfaceRepository,
    private val calculator: ProjectPositionCalculator = ProjectPositionCalculator(),
) {
    private val outlineCache = HashMap<String, List<Pair<DoubleArray, Boolean>>>()
    private var cachedFrame: SurfaceProjectFrame? = null

    /** La escena de [surfaces] (las visibles) en la vista [view]; null si no hay ninguna visible. */
    suspend fun build(frame: SurfaceProjectFrame, surfaces: List<Surface>, activeId: String?, view: GeoBox): SurfaceScene? {
        if (surfaces.isEmpty()) return null
        if (frame != cachedFrame) {
            outlineCache.clear()
            cachedFrame = frame
        }
        outlineCache.keys.retainAll(surfaces.map { it.id }.toSet())
        val ramp = ElevationRamp(surfaces.minOf { it.minElevationM }, surfaces.maxOf { it.maxElevationM })
        val projectView = toProject(frame.crs, view)
        val outlines = ArrayList<SurfaceOutlineLine>()
        val meshes = ArrayList<SurfaceMeshLayer>()
        val images = ArrayList<SurfaceRasterImage>()
        for (surface in surfaces) {
            val active = surface.id == activeId
            outline(frame, surface).forEach { (lonLat, _) -> outlines += SurfaceOutlineLine(lonLat, active) }
            if (projectView == null || !SurfaceViewSelector.intersects(SurfaceViewSelector.enlarge(projectView), surface.extent)) continue
            when (surface.kind) {
                SurfaceKind.TIN -> mesh(frame.crs, surface, projectView, ramp)?.let { meshes += it }
                SurfaceKind.RASTER -> image(frame, surface, projectView, ramp)?.let { images += it }
            }
        }
        val scene = SurfaceScene(outlines, meshes, images, null)
        return SurfaceScene(outlines, meshes, images, ramp.takeIf { scene.colored })
    }

    /** La vista del mapa en el SC del proyecto: el recuadro de sus cuatro esquinas; null si alguna cae fuera de su ámbito. */
    private fun toProject(crs: ProjectCrs, view: GeoBox): SurfaceExtent? {
        val corners = listOf(view.south to view.west, view.south to view.east, view.north to view.east, view.north to view.west)
        val e = DoubleArray(4)
        val n = DoubleArray(4)
        corners.forEachIndexed { i, (lat, lon) ->
            if (!crs.isProjected) {
                e[i] = lon
                n[i] = lat
            } else {
                val p = calculator.fromGround(GeodeticPosition(lat, lon, null), crs)
                e[i] = p.easting ?: return null
                n[i] = p.northing ?: return null
            }
        }
        return SurfaceExtent(e.min(), e.max(), n.min(), n.max())
    }

    private fun lonLatOf(crs: ProjectCrs, easting: DoubleArray, northing: DoubleArray): DoubleArray? {
        val out = DoubleArray(easting.size * 2)
        for (i in easting.indices) {
            val (lat, lon) = CadPlacement.geographic(crs, easting[i], northing[i]) ?: return null
            out[2 * i] = lon
            out[2 * i + 1] = lat
        }
        return out
    }

    /** Los anillos del contorno en longitud y latitud, con si son exteriores; de la caché si ya se calcularon (RF-6, RF-8, RF-13). */
    private suspend fun outline(frame: SurfaceProjectFrame, surface: Surface): List<Pair<DoubleArray, Boolean>> {
        outlineCache[surface.id]?.let { return it }
        val rings = when (surface.kind) {
            SurfaceKind.TIN -> repository.outline(surface.id)?.rings.orEmpty().mapNotNull { ring ->
                lonLatOf(frame.crs, ring.easting, ring.northing)?.let { it to ring.exterior }
            }
            SurfaceKind.RASTER -> {
                val info = surface.raster
                val box = info?.let { RasterViewSelector.extentOnly(it) }
                listOfNotNull(box?.let { rasterToLonLat(frame, surface, it.x, it.y) }?.let { it to true })
            }
        }
        outlineCache[surface.id] = rings
        return rings
    }

    /** La malla de un TIN en la vista, o null si en la vista hay demasiados triángulos y basta el contorno (RF-6, RF-7). */
    private suspend fun mesh(crs: ProjectCrs, surface: Surface, view: SurfaceExtent, ramp: ElevationRamp): SurfaceMeshLayer? {
        val cells = repository.cells(surface.id)
        val read = SurfaceViewSelector.tilesToRead(cells.map { TileBox(it.region, it.triangleCount) }, view) ?: return null
        val tiles = read.mapNotNull { repository.tile(cells[it].tileId) }
        val mesh = (SurfaceViewSelector.tin(tiles, view, ramp) as? TinView.Mesh)?.triangles ?: return null
        val lonLat = lonLatOf(crs, mesh.easting, mesh.northing) ?: return null
        return SurfaceMeshLayer(lonLat, mesh.argb)
    }

    /** La imagen de las celdas de un ráster en la vista, o null si hay demasiadas y basta el recuadro (RF-8, RF-9). */
    private suspend fun image(frame: SurfaceProjectFrame, surface: Surface, view: SurfaceExtent, ramp: ElevationRamp): SurfaceRasterImage? {
        val info = surface.raster ?: return null
        val elevation = RasterElevation(
            frame.crs,
            frame.heightSystem,
            surface,
            RasterTileSource { _, _ -> null },
            terrain = TerrainView.of(frame.terrain, frame.crs) ?: TerrainView.GRID,
        )
        // La vista en el SC del ráster: el recuadro de sus esquinas y de los puntos medios de sus lados.
        val xs = ArrayList<Double>()
        val ys = ArrayList<Double>()
        for (fe in listOf(0.0, 0.5, 1.0)) {
            for (fn in listOf(0.0, 0.5, 1.0)) {
                val e = view.minEasting + (view.maxEasting - view.minEasting) * fe
                val n = view.minNorthing + (view.maxNorthing - view.minNorthing) * fn
                elevation.rasterPosition(e, n)?.let {
                    xs += it[0]
                    ys += it[1]
                }
            }
        }
        if (xs.isEmpty()) return null
        val rasterView = SurfaceExtent(xs.min(), xs.max(), ys.min(), ys.max())
        val inView = RasterViewSelector.cells(info, rasterView) ?: return null
        if (inView.cellCount > RasterViewSelector.MAX_CELLS) return null
        // Los bloques de la vista ampliada se leen antes: el selector no puede esperar a la base de datos.
        val range = RasterViewSelector.cells(info, SurfaceViewSelector.enlarge(rasterView)) ?: inView
        val blocks = HashMap<Long, RasterTile?>()
        for (tr in range.firstRow / RasterTile.EDGE..range.lastRow / RasterTile.EDGE) {
            for (tc in range.firstColumn / RasterTile.EDGE..range.lastColumn / RasterTile.EDGE) {
                blocks[(tc.toLong() shl 32) or tr.toLong()] = repository.rasterTile(surface.id, tc, tr)
            }
        }
        val picture = RasterViewSelector.raster(info, rasterView, ramp) { tc, tr -> blocks[(tc.toLong() shl 32) or tr.toLong()] }
            as? RasterView.Image ?: return null
        val corners = rasterToLonLat(frame, surface, picture.cornerX, picture.cornerY) ?: return null
        return SurfaceRasterImage(surface.id, picture.width, picture.height, picture.argb, corners)
    }

    /** Puntos del SC del ráster a longitud y latitud, pasando por el SC del proyecto como al importarlo (S045, `RasterFootprint`). */
    private fun rasterToLonLat(frame: SurfaceProjectFrame, surface: Surface, x: DoubleArray, y: DoubleArray): DoubleArray? {
        val source = surface.source
        val k = (source as? CadSourceCrs.Library)?.takeIf { it.definition.projected }?.definition?.unitMetres ?: 1.0
        val terrain = TerrainView.of(frame.terrain, frame.crs) ?: TerrainView.GRID
        val projection = SurfaceProjector(frame.crs, source, LinearUnit.METRE, terrain = terrain)
            .convert(DoubleArray(x.size) { x[it] * k }, DoubleArray(y.size) { y[it] * k }, DoubleArray(x.size))
        val converted = projection as? SurfaceProjection.Converted ?: return null
        return lonLatOf(frame.crs, converted.easting, converted.northing)
    }
}
