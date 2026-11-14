package es.topophone.core.survey.cogo

import es.topophone.core.model.geometry.DistanceMode
import es.topophone.core.survey.geometry.GeometryNotice
import kotlin.math.PI
import kotlin.math.hypot

/**
 * La inversa entre dos puntos (S038/RF-6 a RF-8). Longitudes en metros y azimuts en radianes de cuadrícula.
 *
 * @property horizontalDistance la distancia horizontal activa: la de cuadrícula o la de terreno según el modo.
 * @property azimuthRad el azimut directo (de A a B) y el inverso; null si los puntos coinciden.
 * @property slantDistance y [slopePercent] solo con altura en los dos puntos; la pendiente es null con distancia 0 (S038/RF-7).
 */
data class InverseResult(
    val gridDistance: Double,
    val groundDistance: Double,
    val horizontalDistance: Double,
    val azimuthRad: Double?,
    val reverseAzimuthRad: Double?,
    val dEasting: Double,
    val dNorthing: Double,
    val dHeight: Double?,
    val slantDistance: Double?,
    val slopePercent: Double?,
    val coincident: Boolean,
    val factor: Double,
    val mode: DistanceMode,
    val notices: Set<GeometryNotice>,
)

object Inverse {
    /** La inversa de [a] a [b] con la escala de terreno [scale] (el factor del primer punto, S038/RF-5). */
    fun of(a: CogoPoint, b: CogoPoint, scale: CogoScale): InverseResult {
        val dE = b.easting - a.easting
        val dN = b.northing - a.northing
        val grid = hypot(dE, dN)
        val ground = grid / scale.factor
        val azimuth = if (grid == 0.0) null else CogoAngles.azimuth(dE, dN)
        val horizontal = scale.toActive(grid)
        val dh = if (a.height != null && b.height != null) b.height - a.height else null
        return InverseResult(
            gridDistance = grid,
            groundDistance = ground,
            horizontalDistance = horizontal,
            azimuthRad = azimuth,
            reverseAzimuthRad = azimuth?.let { CogoAngles.normalize(it + PI) },
            dEasting = dE,
            dNorthing = dN,
            dHeight = dh,
            slantDistance = dh?.let { hypot(horizontal, it) },
            slopePercent = if (dh != null && horizontal > 0.0) dh / horizontal * 100.0 else null,
            coincident = grid == 0.0,
            factor = scale.factor,
            mode = scale.mode,
            notices = scale.notices,
        )
    }
}
