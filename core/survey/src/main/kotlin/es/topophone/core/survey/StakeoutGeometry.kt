package es.topophone.core.survey

import es.topophone.core.geodesy.GeodeticPoint
import es.topophone.core.geodesy.LocalTangentPlane
import es.topophone.core.model.point.PointCoordinates
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Lo que dice la guía del replanteo desde la punta hasta el punto de diseño (S018/RF-6, RF-7, RF-12).
 *
 * @property distanceM distancia horizontal.
 * @property dEastM diferencia de Este: lo que hay que moverse hacia el Este (diseño menos punta); negativa, hacia el Oeste.
 * @property dNorthM lo mismo hacia el Norte.
 * @property bearingRad rumbo al punto desde el norte de cuadrícula en sentido horario, en `[0, 2π)`; null con distancia cero.
 * @property aheadM cuánto hay que avanzar (negativo, retroceder) según el rumbo del usuario; null sin él.
 * @property rightM cuánto hay que ir a la derecha (negativo, a la izquierda) según el rumbo del usuario; null sin él.
 * @property dHeightM altura de la punta menos la del diseño; null si alguna no tiene.
 */
data class Guidance(
    val distanceM: Double,
    val dEastM: Double,
    val dNorthM: Double,
    val bearingRad: Double?,
    val aheadM: Double?,
    val rightM: Double?,
    val dHeightM: Double?,
)

/**
 * La geometría de la guía, pura y sin Android (S018/A1). En un SC proyectado son diferencias de cuadrícula; en uno
 * geográfico, que no tiene Este ni Norte, las del plano tangente local de la punta (como el modo automático, S017/RF-21).
 */
object StakeoutGeometry {
    /** Por debajo de esta distancia (1 nm) no hay rumbo: con la misma posición no hay hacia dónde ir. */
    private const val NO_DIRECTION_M = 1e-9

    /**
     * La guía de [tip] a [design], con el rumbo del usuario [headingRad] (desde el norte de cuadrícula, en sentido
     * horario) o sin él.
     */
    fun guidance(design: PointCoordinates, tip: PointCoordinates, headingRad: Double?): Guidance {
        val (dEast, dNorth) = planarDifference(design, tip)
        val distance = hypot(dEast, dNorth)
        val bearing = if (distance > NO_DIRECTION_M) atan2(dEast, dNorth).let { if (it < 0.0) it + 2.0 * PI else it } else null
        var ahead: Double? = null
        var right: Double? = null
        if (headingRad != null) {
            // Sin rumbo al punto (ya está encima) no hay que avanzar ni ir a ningún lado.
            val relative = if (bearing == null) 0.0 else bearing - headingRad
            ahead = if (bearing == null) 0.0 else distance * cos(relative)
            right = if (bearing == null) 0.0 else distance * sin(relative)
        }
        val designHeight = design.height
        val tipHeight = tip.height
        val dHeight = if (designHeight != null && tipHeight != null) tipHeight - designHeight else null
        return Guidance(distance, dEast, dNorth, bearing, ahead, right, dHeight)
    }

    private fun planarDifference(design: PointCoordinates, tip: PointCoordinates): Pair<Double, Double> {
        val designEast = design.easting
        val designNorth = design.northing
        val tipEast = tip.easting
        val tipNorth = tip.northing
        if (designEast != null && designNorth != null && tipEast != null && tipNorth != null) {
            return (designEast - tipEast) to (designNorth - tipNorth)
        }
        val tipHeight = tip.ground.ellipsoidalHeightM ?: 0.0
        val from = GeodeticPoint(tip.ground.latitudeDeg, tip.ground.longitudeDeg, tipHeight)
        // Sin la altura del diseño se supone la de la punta: la planimetría no depende de ella.
        val to = GeodeticPoint(design.ground.latitudeDeg, design.ground.longitudeDeg, design.ground.ellipsoidalHeightM ?: tipHeight)
        val enu = LocalTangentPlane.between(from, to)
        return enu.east to enu.north
    }
}
