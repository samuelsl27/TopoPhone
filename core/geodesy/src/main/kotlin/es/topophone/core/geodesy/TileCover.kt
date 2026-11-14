package es.topophone.core.geodesy

import es.topophone.core.model.offline.TileKind
import es.topophone.core.model.offline.TileSourceSpec
import kotlin.math.PI
import kotlin.math.asinh
import kotlin.math.floor
import kotlin.math.log2
import kotlin.math.tan

/** Una caja en latitud y longitud, en grados. Nunca cruza el antimeridiano (el mapa es de España). */
data class GeoBox(val south: Double, val west: Double, val north: Double, val east: Double) {
    init {
        require(south <= north) { "El sur debe quedar por debajo del norte" }
        require(west <= east) { "El oeste debe quedar a la izquierda del este" }
    }
}

/** Una tesela: nivel, columna y fila, con el origen arriba a la izquierda (esquema XYZ). */
data class TileId(val level: Int, val x: Int, val y: Int)

/** Las teselas de un nivel que cubren una caja: de la columna [xMin] a la [xMax] y de la fila [yMin] a la [yMax]. */
data class TileRange(val level: Int, val xMin: Int, val xMax: Int, val yMin: Int, val yMax: Int) {
    val count: Long get() = (xMax - xMin + 1).toLong() * (yMax - yMin + 1)

    /** Las teselas por filas, de arriba abajo. */
    fun tiles(): Sequence<TileId> = sequence {
        for (y in yMin..yMax) for (x in xMin..xMax) yield(TileId(level, x, y))
    }
}

/** La caja de una tesela en EPSG:3857, en metros. */
data class TileBox3857(val minX: Double, val minY: Double, val maxX: Double, val maxY: Double)

/**
 * Las teselas que MapLibre descarga para una región de pirámide de teselas, calculadas aparte para dar el número exacto antes de descargar
 * (S037/RF-7, RF-11, constitución nº 2). Es la convención de MapLibre 13.6.1, medida en el emulador en T1 (plan, «Resultados del spike»):
 * - El nivel de tesela de un zoom `z` del mapa es `z + log2(512 / tamaño)`, redondeado (la mitad hacia arriba) en las fuentes ráster y truncado en
 *   las vectoriales, y acotado al `minzoom` y `maxzoom` de la fuente.
 * - Los niveles son todos los enteros entre el del zoom mínimo y el del máximo.
 * - A cada nivel, las columnas son de `floor(x oeste)` a `floor(x este)` y las filas de `floor(y norte)` a `floor(y sur)`.
 * Un borde que cae exactamente en el límite de una tesela incluye la tesela de al lado; la referencia de `tools/mapas_offline` evita esos casos.
 */
object TileCover {
    /** Latitud máxima de Web Mercator: la del cuadrado del mundo. */
    private const val MAX_LATITUDE_DEG = 85.0511287798066
    private const val WORLD_M = 2 * PI * WebMercator.RADIUS_M

    /** El nivel de tesela que se pide a un zoom del mapa, sin acotar a la fuente. */
    fun coveringLevel(mapZoom: Double, spec: TileSourceSpec): Int {
        val level = mapZoom + log2(512.0 / spec.tileSize)
        return if (spec.kind == TileKind.RASTER) floor(level + 0.5).toInt() else floor(level).toInt()
    }

    /** Los niveles de tesela de una región entre [mapZoomMin] y [mapZoomMax], acotados a la fuente; vacío si la fuente no sirve ninguno. */
    fun levels(mapZoomMin: Double, mapZoomMax: Double, spec: TileSourceSpec): List<Int> {
        val low = maxOf(coveringLevel(mapZoomMin, spec), spec.minZoom)
        val high = minOf(coveringLevel(mapZoomMax, spec), spec.maxZoom)
        return if (low <= high) (low..high).toList() else emptyList()
    }

    /** Las filas y columnas que cubren [box] a cada nivel. */
    fun ranges(box: GeoBox, mapZoomMin: Double, mapZoomMax: Double, spec: TileSourceSpec): List<TileRange> =
        levels(mapZoomMin, mapZoomMax, spec).map { level ->
            val last = (1 shl level) - 1
            TileRange(
                level = level,
                xMin = floor(tileX(box.west, level)).toInt().coerceIn(0, last),
                xMax = floor(tileX(box.east, level)).toInt().coerceIn(0, last),
                yMin = floor(tileY(box.north, level)).toInt().coerceIn(0, last),
                yMax = floor(tileY(box.south, level)).toInt().coerceIn(0, last),
            )
        }

    fun count(box: GeoBox, mapZoomMin: Double, mapZoomMax: Double, spec: TileSourceSpec): Long =
        ranges(box, mapZoomMin, mapZoomMax, spec).sumOf { it.count }

    /** El número de teselas de todas las fuentes de teselas de una fuente descargable (el IGN suma su relieve). */
    fun count(box: GeoBox, mapZoomMin: Double, mapZoomMax: Double, specs: List<TileSourceSpec>): Long =
        specs.sumOf { count(box, mapZoomMin, mapZoomMax, it) }

    /** La columna fraccionaria de una longitud al nivel [level]. */
    fun tileX(longitudeDeg: Double, level: Int): Double = (longitudeDeg + 180.0) / 360.0 * (1L shl level)

    /** La fila fraccionaria de una latitud al nivel [level], con la latitud acotada a la de Web Mercator. */
    fun tileY(latitudeDeg: Double, level: Int): Double {
        val phi = Math.toRadians(latitudeDeg.coerceIn(-MAX_LATITUDE_DEG, MAX_LATITUDE_DEG))
        return (1.0 - asinh(tan(phi)) / PI) / 2.0 * (1L shl level)
    }

    /** La caja de la tesela [x], [y] del nivel [level] en EPSG:3857. */
    fun bounds3857(level: Int, x: Int, y: Int): TileBox3857 {
        val size = WORLD_M / (1L shl level)
        return TileBox3857(
            minX = -WORLD_M / 2 + x * size,
            minY = WORLD_M / 2 - (y + 1) * size,
            maxX = -WORLD_M / 2 + (x + 1) * size,
            maxY = WORLD_M / 2 - y * size,
        )
    }
}
