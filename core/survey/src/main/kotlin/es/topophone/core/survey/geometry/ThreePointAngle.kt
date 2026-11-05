package es.topophone.core.survey.geometry

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot

/** Cada uno de los tres puntos del ángulo (S026/RF-13). */
enum class AnglePoint { A, B, C }

sealed interface AngleResult {
    /** El ángulo en B entre las direcciones a A y a C, de 0 a π, con los azimuts y las distancias de BA y BC (S026/RF-14). */
    data class Angle(
        val angleRad: Double,
        val azimuthBaRad: Double,
        val azimuthBcRad: Double,
        val distanceBa: Double,
        val distanceBc: Double,
        val notices: Set<GeometryNotice>,
    ) : AngleResult

    /** Dos de los tres puntos tienen las mismas coordenadas horizontales (S026/RF-15). */
    data class Coincident(val pairs: List<Pair<AnglePoint, AnglePoint>>) : AngleResult

    /** Algún punto no tiene Este y Norte ni posición geodésica. */
    data object NoPosition : AngleResult
}

/** El ángulo que forman tres puntos en el punto central B (S026/RF-14, RF-15). Solo calcula. */
object ThreePointAngle {
    private const val COINCIDENT_M = 1e-9

    fun of(a: GeometryVertex, b: GeometryVertex, c: GeometryVertex, scale: SegmentScale): AngleResult {
        val plane = PlanarFrame.of(listOf(a, b, c)) ?: return AngleResult.NoPosition
        val bax = plane.east[0] - plane.east[1]
        val bay = plane.north[0] - plane.north[1]
        val bcx = plane.east[2] - plane.east[1]
        val bcy = plane.north[2] - plane.north[1]
        val acx = plane.east[2] - plane.east[0]
        val acy = plane.north[2] - plane.north[0]
        val pairs = buildList {
            if (hypot(bax, bay) < COINCIDENT_M) add(AnglePoint.A to AnglePoint.B)
            if (hypot(bcx, bcy) < COINCIDENT_M) add(AnglePoint.B to AnglePoint.C)
            if (hypot(acx, acy) < COINCIDENT_M) add(AnglePoint.A to AnglePoint.C)
        }
        if (pairs.isNotEmpty()) return AngleResult.Coincident(pairs)
        // atan2 del producto vectorial y del escalar: estable cerca de 0 y de π, donde el coseno pierde la precisión.
        val angle = atan2(abs(bax * bcy - bay * bcx), bax * bcx + bay * bcy)
        val distanceBa = hypot(bax, bay) / scale.segment(b, a)
        val distanceBc = hypot(bcx, bcy) / scale.segment(b, c)
        val notices = if (plane.local) scale.notices + GeometryNotice.LOCAL_PLANE else scale.notices
        return AngleResult.Angle(
            angleRad = angle,
            azimuthBaRad = GeometryCalculator.azimuth(bax, bay),
            azimuthBcRad = GeometryCalculator.azimuth(bcx, bcy),
            distanceBa = distanceBa,
            distanceBc = distanceBc,
            notices = notices,
        )
    }
}
