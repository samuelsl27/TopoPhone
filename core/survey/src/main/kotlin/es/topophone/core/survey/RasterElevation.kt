package es.topophone.core.survey

import es.topophone.core.geodesy.Ed50
import es.topophone.core.geodesy.Ellipsoid
import es.topophone.core.geodesy.GeoidModel
import es.topophone.core.geodesy.GeoidModels
import es.topophone.core.geodesy.Utm
import es.topophone.core.model.cad.CadSourceCrs
import es.topophone.core.model.project.CrsArea
import es.topophone.core.model.project.GeodeticDatum
import es.topophone.core.model.project.HeightSystem
import es.topophone.core.model.project.LibraryCrs
import es.topophone.core.model.project.ProjectCrs
import es.topophone.core.model.project.SpainCrs
import es.topophone.core.model.surface.RasterInfo
import es.topophone.core.model.surface.RasterTile
import es.topophone.core.model.surface.Surface
import es.topophone.core.model.surface.SurfaceHeight
import es.topophone.core.model.surface.SurfaceHeightSource
import es.topophone.core.model.surface.SurfaceKind
import es.topophone.core.survey.geometry.GroundPosition
import kotlin.math.cos
import kotlin.math.floor

/**
 * De dónde salen los bloques de un ráster (S045, plan A4): el bloque de la columna [tileColumn] y la fila [tileRow] de la cuadrícula de
 * bloques, o null si todavía no está en memoria.
 */
fun interface RasterTileSource {
    fun tile(tileColumn: Int, tileRow: Int): RasterTile?
}

/**
 * La cota de un ráster de cotas en una posición del proyecto (S045/RF-9, RF-10, RF-21 a RF-23, plan A3), sin remuestrear el ráster:
 * 1. la posición pasa a latitud y longitud y al sistema del ráster;
 * 2. se interpola bilinealmente entre los cuatro centros de celda más próximos (nunca se extrapola, y la media celda del borde está
 *    fuera, con 1 mm de tolerancia sobre el terreno);
 * 3. la cota pasa a metros y al sistema de alturas del proyecto, con la ondulación del geoide en la posición (H = h − N, h = H + N).
 *
 * Fuera de la extensión, con una celda sin dato o sin geoide en la zona es [SurfaceHeight.Outside]; si falta un bloque en memoria,
 * [SurfaceHeight.Loading]. No usa Android ni bloquea: sirve a 10 Hz.
 */
class RasterElevation(
    private val crs: ProjectCrs,
    private val projectHeightSystem: HeightSystem,
    raster: Surface,
    private val tiles: RasterTileSource,
    private val peninsulaGeoid: () -> GeoidModel = { GeoidModels.egm08RednapPeninsula },
    private val canariasGeoid: () -> GeoidModel = { GeoidModels.egm08RednapCanarias },
    /** S058/RF-17: un ráster «en las coordenadas del proyecto» de un proyecto en terreno está en terreno. */
    private val terrain: TerrainView = TerrainView.GRID,
) : SurfaceHeightSource {
    private val info: RasterInfo = requireNotNull(raster.raster) { "La superficie no es un ráster" }.also {
        require(raster.kind == SurfaceKind.RASTER) { "La superficie no es un ráster" }
    }
    private val source: CadSourceCrs = raster.source
    private val unitMetres = raster.unit.metres
    private val edgeMetres = EDGE_TOLERANCE_M

    /** El SC de origen del ráster si es uno de la biblioteca (S057/RF-15). */
    private val librarySource: SourceGround? = (source as? CadSourceCrs.Library)?.let { SourceGround(it.definition) }

    /** El motor del proyecto si es un SC de la biblioteca: pasa Este y Norte al suelo y da el geoide del proyecto (S057/RF-15, RF-19). */
    private val libraryEngine: CoordinateEngine? = (crs as? LibraryCrs)?.let { ProjectPositionCalculator().engineFor(it) }

    /** La malla está en grados: un ráster en geográficas, o en el sistema del proyecto si este es geográfico. */
    private val degrees = source is CadSourceCrs.Geographic ||
        (source is CadSourceCrs.Library && !source.definition.projected) ||
        (source == CadSourceCrs.Project && !crs.isProjected)

    /** Metros por unidad de la malla de un SC proyectado de la biblioteca; el resto de ráster va en metros (S045, S057/RF-15). */
    private val gridUnitMetres = (source as? CadSourceCrs.Library)?.takeIf { it.definition.projected }?.definition?.unitMetres ?: 1.0

    override fun heightAt(easting: Double, northing: Double): SurfaceHeight {
        if (!easting.isFinite() || !northing.isFinite()) return SurfaceHeight.Outside
        // 1. De Este y Norte del proyecto a latitud y longitud, y al ETRS89 que usan el geoide y los cambios de datum.
        val datumLat: Double
        val datumLon: Double
        if (crs is LibraryCrs) {
            val ground = GroundPosition.of(crs, easting, northing) ?: return SurfaceHeight.Outside
            datumLat = ground.first
            datumLon = ground.second
        } else if (crs.utmZone == null) {
            datumLat = northing
            datumLon = easting
        } else {
            val geo = Utm.inverse(easting, northing, crs.utmZone!!, ellipsoidOf(crs.datum))
            datumLat = geo.latitudeDeg
            datumLon = geo.longitudeDeg
        }
        val etrs = if (crs.datum ==
            GeodeticDatum.ED50
        ) {
            Ed50.toEtrs89(datumLat, datumLon) ?: return SurfaceHeight.Outside
        } else {
            datumLat to datumLon
        }

        // 2. Al sistema del ráster.
        val x: Double
        val y: Double
        when (val s = source) {
            CadSourceCrs.Project -> {
                val ground = terrain.coordinates(easting, northing)
                x = ground.easting
                y = ground.northing
            }
            is CadSourceCrs.Utm -> {
                val (lat, lon) = latLonIn(s.datum, datumLat, datumLon, etrs) ?: return SurfaceHeight.Outside
                val utm = Utm.forward(lat, lon, s.zone, ellipsoidOf(s.datum))
                x = utm.easting
                y = utm.northing
            }
            is CadSourceCrs.Geographic -> {
                val (lat, lon) = latLonIn(s.datum, datumLat, datumLon, etrs) ?: return SurfaceHeight.Outside
                x = lon
                y = lat
            }
            is CadSourceCrs.Library -> {
                val point =
                    requireNotNull(librarySource).fromGround(etrs.first, etrs.second, gridUnitMetres) ?: return SurfaceHeight.Outside
                x = point.first
                y = point.second
            }
        }

        // 3. A coordenadas de celda, con la media celda del borde fuera y 1 mm de tolerancia.
        val stepXMetres = if (degrees) {
            info.stepX * DEG_TO_RAD * EARTH_RADIUS_M * cos(etrs.first * DEG_TO_RAD)
        } else {
            info.stepX *
                gridUnitMetres
        }
        val toleranceCells = edgeMetres / stepXMetres
        var gx = (x - info.originX) / info.stepX
        var gy = (info.originY - y) / info.stepY
        if (gx.isNaN() || gy.isNaN()) return SurfaceHeight.Outside
        if (gx < -toleranceCells || gy < -toleranceCells || gx > info.columns - 1 + toleranceCells || gy > info.rows - 1 + toleranceCells) {
            return SurfaceHeight.Outside
        }
        gx = gx.coerceIn(0.0, (info.columns - 1).toDouble())
        gy = gy.coerceIn(0.0, (info.rows - 1).toDouble())
        val i = if (info.columns == 1) 0 else minOf(floor(gx).toInt(), info.columns - 2)
        val j = if (info.rows == 1) 0 else minOf(floor(gy).toInt(), info.rows - 2)
        val fx = gx - i
        val fy = gy - j

        // 4. Las cuatro celdas; un bloque que falta es «cargando» y una celda sin dato, «fuera».
        val i1 = minOf(i + 1, info.columns - 1)
        val j1 = minOf(j + 1, info.rows - 1)
        val c00 = cell(i, j) ?: return SurfaceHeight.Loading
        val c10 = cell(i1, j) ?: return SurfaceHeight.Loading
        val c01 = cell(i, j1) ?: return SurfaceHeight.Loading
        val c11 = cell(i1, j1) ?: return SurfaceHeight.Loading
        if (c00.isNaN() || c10.isNaN() || c01.isNaN() || c11.isNaN()) return SurfaceHeight.Outside
        // Dos interpolaciones lineales en lugar de cuatro pesos: en un ráster constante, o en el centro de una celda, el resultado es
        // exactamente el valor de la celda, sin el error de redondeo de sumar los pesos.
        val value = lerp(lerp(c00, c10, fx), lerp(c01, c11, fx), fy)

        // 5. A metros y al sistema de alturas del proyecto.
        var metres = value * unitMetres
        if (info.heightSystem != projectHeightSystem) {
            val undulation = if (libraryEngine != null) {
                (libraryEngine.undulation(etrs.first, etrs.second) as? Undulation.Value)?.metres
            } else {
                val geoid = if ((crs as? SpainCrs)?.area == CrsArea.CANARIAS) canariasGeoid() else peninsulaGeoid()
                geoid.undulationM(etrs.first, etrs.second)
            } ?: return SurfaceHeight.Outside
            metres = if (info.heightSystem == HeightSystem.ELLIPSOIDAL) metres - undulation else metres + undulation
        }
        return SurfaceHeight.Value(metres)
    }

    private fun lerp(a: Double, b: Double, t: Double): Double = if (t == 0.0) {
        a
    } else if (t == 1.0) {
        b
    } else {
        a + t * (b - a)
    }

    /** El valor de la celda ([col], [row]); null si su bloque no está en memoria, `NaN` si no tiene dato. */
    private fun cell(col: Int, row: Int): Double? {
        val tile = tiles.tile(col / RasterTile.EDGE, row / RasterTile.EDGE) ?: return null
        return tile.valueAt(col % RasterTile.EDGE, row % RasterTile.EDGE)
    }

    /** Latitud y longitud de la posición en el datum [datum]: la del proyecto si es el mismo, ED50 por la rejilla; null fuera de ella. */
    private fun latLonIn(datum: GeodeticDatum, datumLat: Double, datumLon: Double, etrs: Pair<Double, Double>): Pair<Double, Double>? =
        when {
            datum == crs.datum -> datumLat to datumLon
            datum == GeodeticDatum.ED50 -> Ed50.fromEtrs89(etrs.first, etrs.second)
            else -> etrs
        }

    private fun ellipsoidOf(datum: GeodeticDatum) = if (datum == GeodeticDatum.ED50) Ellipsoid.INTERNATIONAL_1924 else Ellipsoid.GRS80

    private companion object {
        /** La tolerancia de borde de S042: una posición a menos de 1 mm de la última celda cuenta como dentro. */
        const val EDGE_TOLERANCE_M = 0.001
        const val DEG_TO_RAD = Math.PI / 180.0
        const val EARTH_RADIUS_M = 6_371_008.8
    }
}
