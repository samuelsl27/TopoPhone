package es.topophone.app.ui.alignment

import es.topophone.core.model.point.AxisRef
import es.topophone.core.model.point.PointCoordinates

/**
 * Da al recolector, cuando termina una medida, dónde cae respecto al eje que se analiza: el eje, el PK, el desplazamiento y la diferencia
 * de cota con la referencia elegida (S054/RF-13). Se le da la posición medida, no la última de la guía. Null si la posición no se
 * proyecta sobre el eje (RF-14): el punto se guarda como un punto medido normal.
 */
fun interface AxisReferenceProvider {
    fun at(measured: PointCoordinates): AxisRef?
}
