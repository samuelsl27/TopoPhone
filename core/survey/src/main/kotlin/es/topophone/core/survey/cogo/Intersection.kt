package es.topophone.core.survey.cogo

import es.topophone.core.model.cogo.IntersectionSide
import kotlin.math.abs
import kotlin.math.hypot
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
}
