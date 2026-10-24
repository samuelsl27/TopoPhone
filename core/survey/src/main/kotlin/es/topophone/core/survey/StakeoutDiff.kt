package es.topophone.core.survey

import es.topophone.core.model.point.PointCoordinates
import es.topophone.core.model.point.StakeoutRef

/**
 * La referencia de un punto replanteado al diseño (S018/A5, RF-28): lo medido menos el diseño en Este, Norte, distancia
 * horizontal y cota, sin redondear. La guía dice lo que hay que moverse (diseño menos punta); lo guardado, cuánto se
 * desvió lo medido (medido menos diseño).
 */
object StakeoutDiff {
    fun ref(designId: String, designName: String, design: PointCoordinates, measured: PointCoordinates): StakeoutRef {
        // La guía de la punta medida al diseño es "diseño menos medido": las diferencias de lo guardado son las contrarias.
        val g = StakeoutGeometry.guidance(design, measured, headingRad = null)
        return StakeoutRef(
            designId = designId,
            designName = designName,
            design = design,
            dEastM = 0.0 - g.dEastM, // sin el cero negativo
            dNorthM = 0.0 - g.dNorthM,
            dHorizontalM = g.distanceM,
            dHeightM = g.dHeightM, // punta menos diseño: aquí, medido menos diseño
        )
    }
}
