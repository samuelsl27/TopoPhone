package es.topophone.app.ui.collector

import es.topophone.core.model.point.PointCoordinates

/** Un punto que el recolector acaba de guardar: su id, su nombre y sus coordenadas, sin redondear (S052/RF-2). */
class SavedPoint(val id: String, val name: String, val coordinates: PointCoordinates)

/**
 * Quien quiere saber cuándo el recolector guarda un punto, por ejemplo una toma lineal para añadirlo (S052/RF-2). Se llama una vez por
 * punto guardado, justo después de guardarlo, en el hilo del recolector y antes de anunciar el punto guardado.
 */
fun interface PointSavedListener {
    suspend fun onSaved(point: SavedPoint)
}
