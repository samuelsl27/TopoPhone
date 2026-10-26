package es.topophone.app.ui.stakeout

import es.topophone.core.model.point.LineStakeoutMode
import es.topophone.core.model.point.PointCoordinates

/**
 * El objetivo de un replanteo de línea en el momento de guardar el punto (S041/RF-28): el punto de diseño con sus coordenadas de
 * suelo y su cota, de dónde viene (modo, PK y desplazamiento lateral) y cómo calcular el error lateral de lo medido.
 *
 * @property elevationM la cota de diseño usada: la escrita a mano, o la de la línea en el PK del objetivo; null si no hay.
 * @property lateralErrorOf el error lateral de un punto medido: su distancia lateral a la línea menos el desplazamiento; null si
 *   queda fuera de la línea o del tramo y no tiene lado.
 */
class StakeObjective(
    val design: PointCoordinates,
    val elevationM: Double?,
    val mode: LineStakeoutMode,
    val chainageM: Double?,
    val lateralOffsetM: Double?,
    val lateralErrorOf: (PointCoordinates) -> Double?,
)

/** Da el objetivo de ahora cuando el recolector termina una medida; null si la guía aún no ha dado ninguno (S041/RF-27, RF-28). */
fun interface StakeObjectiveProvider {
    fun current(): StakeObjective?
}
