package es.topophone.core.geodesy

import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log2
import kotlin.math.pow
import kotlin.math.tan

/** Punto en coordenadas geográficas, en grados. */
data class GeoPoint(val latitudeDeg: Double, val longitudeDeg: Double)

/**
 * Web Mercator (EPSG:3857), la proyección del mapa: esfera con el semieje de WGS84 sobre latitudes
 * geodésicas. A zoom z el mundo mide 512·2^z píxeles, como con las teselas de MapLibre, y un píxel
 * es un dp (1/160 de pulgada) (S009/RF-10, RF-12, RF-19).
 */
object WebMercator {
    const val RADIUS_M = 6_378_137.0
    const val TILE_PX = 512.0

    /** Zoom máximo del mapa: 100 dp miden menos de 1,7 m en toda España (S009/RF-12). */
    const val MAX_ZOOM = 22.0

    private const val WORLD_M = 2 * PI * RADIUS_M
    private const val METRES_PER_DP = 0.0254 / 160

    /** x, y en metros de EPSG:3857. */
    fun forward(latitudeDeg: Double, longitudeDeg: Double): Pair<Double, Double> =
        RADIUS_M * Math.toRadians(longitudeDeg) to RADIUS_M * ln(tan(PI / 4 + Math.toRadians(latitudeDeg) / 2))

    fun inverse(x: Double, y: Double): GeoPoint =
        GeoPoint(Math.toDegrees(2 * atan(exp(y / RADIUS_M)) - PI / 2), Math.toDegrees(x / RADIUS_M))

    /** Metros del terreno, sobre el paralelo de GRS80, que mide un píxel horizontal en [latitudeDeg] a zoom [zoom]. */
    fun metresPerPixel(latitudeDeg: Double, zoom: Double): Double = WORLD_M / (TILE_PX * 2.0.pow(zoom)) * groundPerProjected(latitudeDeg)

    /** Zoom con el que la escala nominal en [latitudeDeg] es 1:[scaleDenominator]. */
    fun zoomForScale(scaleDenominator: Double, latitudeDeg: Double): Double =
        log2(WORLD_M * groundPerProjected(latitudeDeg) / (TILE_PX * scaleDenominator * METRES_PER_DP))

    /** Denominador de la escala nominal en [latitudeDeg] a zoom [zoom]. */
    fun scaleDenominator(latitudeDeg: Double, zoom: Double): Double = metresPerPixel(latitudeDeg, zoom) / METRES_PER_DP

    /** Metros del terreno por metro de EPSG:3857 a lo largo del paralelo: N·cos φ / R. */
    private fun groundPerProjected(latitudeDeg: Double): Double {
        val phi = Math.toRadians(latitudeDeg)
        return Geodesy.primeVerticalRadius(phi) * cos(phi) / RADIUS_M
    }
}
