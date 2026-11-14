package es.topophone.core.survey.cogo

import es.topophone.core.model.cogo.IntersectionSide
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

/** Por qué una intersección no tiene solución (S038/RF-17). */
enum class NoSolutionReason {
    /** Las dos circunferencias tienen el mismo centro. */
    CONCENTRIC,

    /** Las circunferencias no llegan a cortarse. */
    TOO_FAR,

    /** Una circunferencia queda dentro de la otra sin tocarla. */
    TOO_CLOSE,

    /** Las dos direcciones son paralelas o coinciden. */
    PARALLEL,

    /** La dirección pasa a más distancia del primer punto que la que se escribió. */
    LINE_MISSES,
}

/**
 * Un punto que cumple las condiciones de una intersección (S038/RF-14 a RF-16): su [side] («izquierda», «derecha», «primera», «segunda»
 * o la única), su Este y Norte, y las dos distancias del modo activo, con signo: la que va a cada punto de entrada, o la distancia al
 * punto sobre su dirección (negativa si queda detrás).
 */
data class IntersectionSolution(
    val side: IntersectionSide,
    val easting: Double,
    val northing: Double,
    val distance1: Double,
    val distance2: Double,
) {
    /** Queda detrás de alguno de los puntos (distancia negativa, S038/RF-15). */
    val behind: Boolean get() = distance1 < 0.0 || distance2 < 0.0
}

sealed interface IntersectionResult {
    data class Solutions(val list: List<IntersectionSolution>) : IntersectionResult

    data class NoSolution(val reason: NoSolutionReason) : IntersectionResult
}

/**
 * Intersecciones de COGO (S038/RF-13 a RF-17). Las distancias que se escriben y las que se devuelven son del modo activo de
 * [CogoScale]; los azimuts son de cuadrícula.
 */
object Intersection {
    /** Dentro de esta distancia dos circunferencias, o una circunferencia y una recta, se consideran tangentes (1 µm). */
    const val TANGENT_TOLERANCE_M = 1e-6

    /**
     * Las circunferencias de centro [first] y radio [r1] y de centro [second] y radio [r2]: dos soluciones, a la izquierda y a la
     * derecha del sentido de [first] a [second], una si son tangentes y ninguna si no se cortan o son concéntricas.
     */
    fun distanceDistance(first: CogoPoint, second: CogoPoint, r1: Double, r2: Double, scale: CogoScale): IntersectionResult {
        val g1 = scale.toGrid(r1)
        val g2 = scale.toGrid(r2)
        val dx = second.easting - first.easting
        val dy = second.northing - first.northing
        val d = hypot(dx, dy)
        if (d == 0.0) return IntersectionResult.NoSolution(NoSolutionReason.CONCENTRIC)
        if (d > g1 + g2 + TANGENT_TOLERANCE_M) return IntersectionResult.NoSolution(NoSolutionReason.TOO_FAR)
        if (d < abs(g1 - g2) - TANGENT_TOLERANCE_M) return IntersectionResult.NoSolution(NoSolutionReason.TOO_CLOSE)
        val ux = dx / d
        val uy = dy / d
        val along = (g1 * g1 - g2 * g2 + d * d) / (2 * d)
        val mx = first.easting + along * ux
        val my = first.northing + along * uy
        val tangent = abs(d - (g1 + g2)) <= TANGENT_TOLERANCE_M || abs(d - abs(g1 - g2)) <= TANGENT_TOLERANCE_M
        val h2 = ((g1 + g2) * (g1 + g2) - d * d) * (d * d - (g1 - g2) * (g1 - g2)) / (4 * d * d)
        if (tangent || h2 <= 0.0) {
            return IntersectionResult.Solutions(listOf(IntersectionSolution(IntersectionSide.SINGLE, mx, my, r1, r2)))
        }
        val h = sqrt(h2)
        // Izquierda del sentido de first a second: el vector unitario girado 90° en sentido antihorario.
        return IntersectionResult.Solutions(
            listOf(
                IntersectionSolution(IntersectionSide.LEFT, mx - h * uy, my + h * ux, r1, r2),
                IntersectionSolution(IntersectionSide.RIGHT, mx + h * uy, my - h * ux, r1, r2),
            ),
        )
    }

    /** Dos direcciones con sus azimuts son paralelas si el seno de su diferencia es menor que esto. */
    const val PARALLEL_SINE = 1e-12

    /**
     * Las rectas que salen de [first] con el azimut [azimuth1] y de [second] con [azimuth2]: la solución con la distancia desde cada
     * punto, negativa si queda detrás (S038/RF-15); ninguna si son paralelas o coinciden.
     */
    fun directionDirection(first: CogoPoint, second: CogoPoint, azimuth1: Double, azimuth2: Double, scale: CogoScale): IntersectionResult {
        val s1x = sin(azimuth1)
        val s1y = cos(azimuth1)
        val s2x = sin(azimuth2)
        val s2y = cos(azimuth2)
        val den = s1x * s2y - s1y * s2x
        if (abs(den) < PARALLEL_SINE) return IntersectionResult.NoSolution(NoSolutionReason.PARALLEL)
        val wx = second.easting - first.easting
        val wy = second.northing - first.northing
        val t1 = (wx * s2y - wy * s2x) / den
        val t2 = (wx * s1y - wy * s1x) / den
        val solution = IntersectionSolution(
            IntersectionSide.SINGLE,
            first.easting + t1 * s1x,
            first.northing + t1 * s1y,
            scale.toActive(t1),
            scale.toActive(t2),
        )
        return IntersectionResult.Solutions(listOf(solution))
    }

    /**
     * La circunferencia de centro [first] y radio [radius] con la recta que sale de [second] con el azimut [azimuth]: dos, una o
     * ninguna solución (S038/RF-16), ordenadas por su distancia [IntersectionSolution.distance2] al segundo punto, negativa si queda
     * detrás. [IntersectionSolution.distance1] es el radio escrito.
     */
    fun distanceDirection(first: CogoPoint, second: CogoPoint, radius: Double, azimuth: Double, scale: CogoScale): IntersectionResult {
        val g = scale.toGrid(radius)
        val sx = sin(azimuth)
        val sy = cos(azimuth)
        val wx = first.easting - second.easting
        val wy = first.northing - second.northing
        val foot = wx * sx + wy * sy
        val h = abs(wx * sy - wy * sx)
        if (h > g + TANGENT_TOLERANCE_M) return IntersectionResult.NoSolution(NoSolutionReason.LINE_MISSES)
        val half = sqrt(maxOf(g * g - h * h, 0.0))
        val roots = if (abs(g - h) <= TANGENT_TOLERANCE_M) listOf(foot) else listOf(foot - half, foot + half)
        val sides = if (roots.size == 1) listOf(IntersectionSide.SINGLE) else listOf(IntersectionSide.FIRST, IntersectionSide.SECOND)
        return IntersectionResult.Solutions(
            roots.zip(sides).map { (t, side) ->
                IntersectionSolution(side, second.easting + t * sx, second.northing + t * sy, radius, scale.toActive(t))
            },
        )
    }
}
