package es.topophone.core.survey

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * El tramo de la línea que se replantea, de [startM] (A) a [endM] (B), como PK (S041/RF-16, RF-17). Con A mayor que B se recorre en
 * sentido inverso. [LineRange.whole] es la línea entera, para "A la línea" y "A lo largo de la línea".
 */
data class LineRange(val startM: Double, val endM: Double) {
    val low: Double get() = min(startM, endM)
    val high: Double get() = max(startM, endM)

    companion object {
        fun whole(line: StakeLine) = LineRange(0.0, line.lengthM)
    }
}

/** Dónde está la punta respecto al tramo, en el sentido de A hacia B: antes de A, dentro o después de B (S041/RF-14, RF-17). */
enum class RangePosition { BEFORE, WITHIN, AFTER }

/**
 * El objetivo del replanteo de una línea con la punta donde está (S041/RF-10, RF-11, RF-17, RF-23, RF-25).
 *
 * @property point el punto de diseño: el más cercano de la línea desplazado el desplazamiento lateral, o el extremo del tramo
 *   (también desplazado) si la punta queda fuera de él.
 * @property chainageM el PK del objetivo, sin el desplazamiento: el del punto más cercano, o el del extremo del tramo.
 * @property heightM la cota interpolada de la línea en [chainageM], o null si no tiene (RF-23).
 * @property location dónde está la punta respecto a la línea sin desplazar: la distancia lateral con signo y el PK reales.
 * @property lateralErrorM la distancia lateral menos el desplazamiento: lo que hay que corregir para estar donde se quiere
 *   (RF-13); null fuera del tramo, donde no hay lado (RF-14).
 * @property alongM el PK del objetivo respecto a A, en el sentido de A hacia B (RF-17).
 * @property remainingM lo que falta del objetivo hasta B (RF-10, RF-17).
 */
data class LineObjective(
    val point: PlanePoint,
    val chainageM: Double,
    val heightM: Double?,
    val location: LineLocation,
    val lateralErrorM: Double?,
    val position: RangePosition,
    val alongM: Double,
    val remainingM: Double,
    val offsetM: Double,
) {
    /** La distancia horizontal de la punta al objetivo: lo que mira la diana (RF-12). */
    fun distanceFrom(tipEasting: Double, tipNorthing: Double): Double = hypot(point.easting - tipEasting, point.northing - tipNorthing)
}

/** Los objetivos de "A la línea", "A lo largo de la línea" y "Tramo A–B" (S041/RF-10, RF-11, RF-17). */
object LineObjectives {
    /**
     * El objetivo de la punta en [location] (de [LineLocator.locate]) con el desplazamiento lateral [offsetM] (positivo a la
     * derecha del sentido de la línea) dentro de [range]. Sin tramo, la línea entera. Fuera del tramo, el objetivo es su extremo
     * más cercano y no hay error lateral (RF-14, RF-17).
     */
    fun of(line: StakeLine, location: LineLocation, offsetM: Double, range: LineRange = LineRange.whole(line)): LineObjective {
        val p = location.chainageM
        val before = location.position == LinePosition.BEFORE_START || p < range.low
        val after = location.position == LinePosition.AFTER_END || p > range.high
        val pk = when {
            before -> range.low
            after -> range.high
            else -> p
        }
        val forward = range.startM <= range.endM
        val position = when {
            !before && !after -> RangePosition.WITHIN
            before == forward -> RangePosition.BEFORE
            else -> RangePosition.AFTER
        }
        val point = if (position == RangePosition.WITHIN) displaced(line, location, offsetM) else line.pointAt(pk, offsetM)
        return LineObjective(
            point = point,
            chainageM = pk,
            heightM = line.heightAt(pk),
            location = location,
            lateralErrorM = if (position == RangePosition.WITHIN) location.lateralM - offsetM else null,
            position = position,
            alongM = abs(pk - range.startM),
            remainingM = abs(range.endM - pk),
            offsetM = offsetM,
        )
    }

    /** El punto más cercano desplazado hacia la derecha del tramo elegido, sin recortar ni unir esquinas (RF-11). */
    private fun displaced(line: StakeLine, location: LineLocation, offsetM: Double): PlanePoint {
        if (offsetM == 0.0) return location.nearest
        val a = line.segmentStart(location.segment)
        val b = line.segmentEnd(location.segment)
        val dx = line.easting(b) - line.easting(a)
        val dy = line.northing(b) - line.northing(a)
        val length = hypot(dx, dy)
        return PlanePoint(location.nearest.easting + offsetM * dy / length, location.nearest.northing - offsetM * dx / length)
    }
}
