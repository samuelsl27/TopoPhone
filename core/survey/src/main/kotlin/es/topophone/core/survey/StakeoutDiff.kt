package es.topophone.core.survey

import es.topophone.core.model.point.PointCoordinates
import es.topophone.core.model.point.StakeoutRef
import kotlin.math.abs

/**
 * La referencia de un punto replanteado al diseño (S018/A5, RF-28; S019/A3): lo medido menos el diseño en Este, Norte,
 * distancia horizontal y cota, sin redondear. La guía dice lo que hay que moverse (diseño menos punta); lo guardado, cuánto se
 * desvió lo medido (medido menos diseño).
 *
 * Desde S019 la cota se mide contra la cota de diseño usada, que puede ser la escrita a mano y no la del punto (RF-13), y se
 * guardan las tolerancias vigentes y si el replanteo cumple (RF-14).
 */
object StakeoutDiff {
    /**
     * @param elevationM la cota de diseño usada; por defecto, la altura del punto de diseño (como en S018). Null: no hay cota
     *   y no hay diferencia de cota.
     * @param toleranceHM y [toleranceVM] las tolerancias vigentes al medir (m); sin ellas no se evalúa el replanteo (RF-26).
     */
    fun ref(
        designId: String,
        designName: String,
        design: PointCoordinates,
        measured: PointCoordinates,
        elevationM: Double? = design.height,
        toleranceHM: Double? = null,
        toleranceVM: Double? = null,
    ): StakeoutRef {
        // La guía de la punta medida al diseño es "diseño menos medido": las diferencias de lo guardado son las contrarias.
        val g = StakeoutGeometry.guidance(design, measured, headingRad = null)
        val measuredHeight = measured.height
        val dHeight = if (elevationM != null && measuredHeight != null) measuredHeight - elevationM else null
        val within = if (toleranceHM != null && toleranceVM != null) within(g.distanceM, dHeight, toleranceHM, toleranceVM) else null
        return StakeoutRef(
            designId = designId,
            designName = designName,
            design = design,
            dEastM = 0.0 - g.dEastM, // sin el cero negativo
            dNorthM = 0.0 - g.dNorthM,
            dHorizontalM = g.distanceM,
            dHeightM = dHeight, // medido menos la cota usada
            designElevationM = elevationM,
            toleranceHM = toleranceHM,
            toleranceVM = toleranceVM,
            withinTolerance = within,
        )
    }

    /**
     * Si un replanteo cumple (S019/RF-14): la distancia horizontal [dHorizontalM] cabe en [toleranceHM] y, con cota
     * ([dHeightM] no nulo), el valor absoluto de la diferencia de cota cabe en [toleranceVM]. Los límites están dentro.
     */
    fun within(dHorizontalM: Double, dHeightM: Double?, toleranceHM: Double, toleranceVM: Double): Boolean =
        dHorizontalM <= toleranceHM && (dHeightM == null || abs(dHeightM) <= toleranceVM)
}
