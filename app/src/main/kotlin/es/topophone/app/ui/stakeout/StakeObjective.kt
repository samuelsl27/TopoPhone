package es.topophone.app.ui.stakeout

import es.topophone.core.model.point.LineStakeoutMode
import es.topophone.core.model.point.PointCoordinates

/**
 * El objetivo de un replanteo de línea o de eje en el momento de guardar el punto (S041/RF-28, S054/RF-10): el punto de diseño con sus
 * coordenadas de suelo y su cota, de dónde viene (modo, PK y desplazamiento lateral) y cómo calcular el error lateral de lo medido.
 *
 * @property mode el modo de línea; null en un replanteo por PK de un eje, que no tiene modos.
 * @property elevationM la cota de diseño usada: la escrita a mano, o la de la línea en el PK del objetivo; null si no hay.
 * @property lateralErrorOf el error lateral de un punto medido: su distancia lateral a la línea menos el desplazamiento; null si
 *   queda fuera de la línea o del tramo y no tiene lado.
 * @property thicknessM el espesor sumado a la rasante en un replanteo por PK; null en una línea (S054/RF-10).
 * @property chainageErrorOf la diferencia de PK de un punto medido respecto al PK de diseño, positiva hacia delante; solo en un
 *   replanteo por PK de un eje, donde [lateralErrorOf] es la diferencia de desplazamiento (S054/RF-10).
 * @property sectionCode el código de sección del punto de diseño en un replanteo por código (S050/RF-19).
 */
class StakeObjective(
    val design: PointCoordinates,
    val elevationM: Double?,
    val mode: LineStakeoutMode?,
    val chainageM: Double?,
    val lateralOffsetM: Double?,
    val lateralErrorOf: (PointCoordinates) -> Double?,
    val thicknessM: Double? = null,
    val chainageErrorOf: ((PointCoordinates) -> Double?)? = null,
    val sectionCode: String? = null,
)

/** Da el objetivo de ahora cuando el recolector termina una medida; null si la guía aún no ha dado ninguno (S041/RF-27, RF-28). */
fun interface StakeObjectiveProvider {
    fun current(): StakeObjective?
}
