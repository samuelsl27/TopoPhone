package es.topophone.core.survey

import kotlin.math.cos
import kotlin.math.sin

/** Hacia dónde hay que moverse a través del eje: hacia dentro o hacia fuera, o a un lado si el diseño está sobre el eje (S054/RF-6). */
enum class AcrossMove { NONE, INWARD, OUTWARD, LEFT, RIGHT }

/**
 * Lo que hay que moverse para llegar al punto de diseño, respecto al eje (S054/RF-6).
 *
 * @property alongM adelante (positivo, hacia los PK crecientes) o atrás (negativo).
 * @property acrossM hacia la derecha del eje (positivo) o hacia la izquierda (negativo).
 * @property across el sentido de [acrossM] como acercarse al eje o alejarse de él; con el diseño sobre el eje, izquierda o derecha.
 */
data class AxisGuidanceValue(val alongM: Double, val acrossM: Double, val across: AcrossMove)

/**
 * La guía hacia un punto de diseño de un eje con el azimut del eje en su PK (S054/RF-6, plan A3): descompone `diseño menos punta` en lo
 * que hay que moverse a lo largo del eje y a través de él.
 */
object AxisGuidance {
    fun of(target: AxisStakeResult.Target, tipEasting: Double, tipNorthing: Double): AxisGuidanceValue {
        val dEast = target.point.easting - tipEasting
        val dNorth = target.point.northing - tipNorthing
        val azimuth = target.point.azimuth
        val along = dEast * sin(azimuth) + dNorth * cos(azimuth)
        val across = dEast * cos(azimuth) - dNorth * sin(azimuth)
        return AxisGuidanceValue(along, across, move(target.offsetM, across))
    }

    private fun move(designOffsetM: Double, acrossM: Double): AcrossMove = when {
        acrossM == 0.0 -> AcrossMove.NONE
        designOffsetM == 0.0 -> if (acrossM > 0.0) AcrossMove.RIGHT else AcrossMove.LEFT
        // El eje queda al lado contrario del diseño: moverse hacia él es ir en sentido opuesto al desplazamiento.
        designOffsetM * acrossM < 0.0 -> AcrossMove.INWARD
        else -> AcrossMove.OUTWARD
    }
}
