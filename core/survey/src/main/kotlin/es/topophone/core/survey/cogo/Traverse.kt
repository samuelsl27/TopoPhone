package es.topophone.core.survey.cogo

import es.topophone.core.model.cogo.LegKind
import es.topophone.core.model.cogo.TraverseLeg
import es.topophone.core.survey.geometry.GeometryNotice
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/** Por qué una poligonal deja de calcularse en un tramo (S038/RF-10, RF-31). */
enum class TraverseStop {
    /** Al tramo le falta la dirección o la distancia, o no es un número. */
    INCOMPLETE_LEG,

    /** El tramo es por ángulo o deflexión y no hay punto de referencia. */
    NO_REFERENCE,

    /** La visual atrás es indefinida: la referencia coincide con el inicio o el tramo anterior mide 0 (S038/RF-31). */
    BACKSIGHT_UNDEFINED,
}

/**
 * El punto al final del tramo [leg] (desde 1), con la dirección y la distancia de cuadrícula que se usaron (S038/RF-11). [height] es
 * null si el punto de inicio no tiene altura o algún tramo hasta él no lleva desnivel escrito.
 */
data class TraverseStep(
    val leg: Int,
    val easting: Double,
    val northing: Double,
    val azimuthRad: Double,
    val gridDistance: Double,
    val height: Double? = null,
)

/**
 * El error de cierre (S038/RF-12): el último punto calculado menos el punto de cierre, en Este y Norte; el lineal; la longitud total de
 * los tramos en cuadrícula; y la precisión relativa 1:N, con `N = totalLength / linear` ([ratio]), que es null si cierra (error lineal
 * por debajo del micrómetro). Solo se informa: ningún punto se corrige.
 */
data class ClosureError(val dEasting: Double, val dNorthing: Double, val linear: Double, val totalLength: Double, val ratio: Double?)

/**
 * Lo que da una poligonal: un punto por cada tramo calculado y, si se paró antes de acabar, en qué tramo ([stoppedAt], desde 1) y por
 * qué. Desde ese tramo no se calcula ninguno más (S038/RF-31).
 */
data class TraverseResult(
    val steps: List<TraverseStep>,
    val stoppedAt: Int?,
    val stopReason: TraverseStop?,
    val totalGridLength: Double,
    val notices: Set<GeometryNotice>,
    val closure: ClosureError? = null,
)

/**
 * Una poligonal: puntos sucesivos desde un punto de inicio con la dirección y la distancia de cada tramo (S038/RF-10, RF-11). Sin
 * compensación (S038, fuera de alcance). Todo en el plano del SC; las distancias escritas se pasan a cuadrícula con [CogoScale].
 *
 * La dirección de un tramo sale de su clase: un azimut; el ángulo a la derecha o a la izquierda desde la visual atrás; o la deflexión a
 * la derecha o a la izquierda desde la prolongación de la visual atrás (que apunta al lado contrario). La visual atrás del primer tramo
 * va al punto de referencia y la de los siguientes, al punto anterior.
 */
object Traverse {

    /** Por debajo de esto el error lineal es ruido del cálculo: la poligonal cierra y no hay 1:N. */
    const val CLOSES_BELOW_M = 1e-6

    /** La poligonal de [legs] desde [start]; con [closure], su error de cierre si se calcularon todos los tramos. */
    fun compute(
        start: CogoPoint,
        reference: CogoPoint?,
        legs: List<TraverseLeg>,
        scale: CogoScale,
        closure: CogoPoint? = null,
    ): TraverseResult {
        val steps = ArrayList<TraverseStep>(legs.size)
        var east = start.easting
        var north = start.northing
        var height = start.height
        var previousAzimuth: Double? = null
        var previousGrid = 0.0
        var total = 0.0
        var stop: Pair<Int, TraverseStop>? = null

        for ((index, leg) in legs.withIndex()) {
            val number = index + 1
            val value = leg.angleRad
            val distance = leg.distanceM
            if (value == null || distance == null) {
                stop = number to TraverseStop.INCOMPLETE_LEG
                break
            }
            val azimuth = if (leg.kind == LegKind.AZIMUTH) {
                value
            } else {
                val back = when {
                    number == 1 && reference == null -> {
                        stop = number to TraverseStop.NO_REFERENCE
                        break
                    }
                    number == 1 -> bearing(east, north, reference!!)
                    previousGrid == 0.0 -> null
                    else -> CogoAngles.normalize(previousAzimuth!! + PI)
                }
                if (back == null) {
                    stop = number to TraverseStop.BACKSIGHT_UNDEFINED
                    break
                }
                direction(leg.kind, value, back)
            }
            val grid = scale.toGrid(distance)
            east += grid * sin(azimuth)
            north += grid * cos(azimuth)
            height = if (height != null && leg.dHeightM != null) height + leg.dHeightM!! else null
            steps += TraverseStep(number, east, north, azimuth, grid, height)
            previousAzimuth = azimuth
            previousGrid = grid
            total += grid
        }
        val error = if (closure != null &&
            stop == null &&
            steps.isNotEmpty()
        ) {
            closureError(east - closure.easting, north - closure.northing, total)
        } else {
            null
        }
        return TraverseResult(steps, stop?.first, stop?.second, total, scale.notices, error)
    }

    private fun closureError(de: Double, dn: Double, total: Double): ClosureError {
        val linear = hypot(de, dn)
        return ClosureError(de, dn, linear, total, if (linear < CLOSES_BELOW_M) null else total / linear)
    }

    /** El azimut de la visual atrás de la referencia desde el punto actual; null si coinciden. */
    private fun bearing(east: Double, north: Double, reference: CogoPoint): Double? {
        val de = reference.easting - east
        val dn = reference.northing - north
        return if (hypot(de, dn) == 0.0) null else CogoAngles.azimuth(de, dn)
    }

    /** El azimut del tramo a partir del de su visual atrás [back] (S038/RF-10). */
    private fun direction(kind: LegKind, value: Double, back: Double): Double = CogoAngles.normalize(
        when (kind) {
            LegKind.AZIMUTH -> value
            LegKind.ANGLE_RIGHT -> back + value
            LegKind.ANGLE_LEFT -> back - value
            LegKind.DEFLECTION_RIGHT -> back + PI + value
            LegKind.DEFLECTION_LEFT -> back + PI - value
        },
    )
}
