package es.topophone.core.model.cogo

/** Un punto de entrada del borrador de la poligonal: uno del proyecto (con su nombre al elegirlo) o uno escrito a mano (S038/RF-3, RF-26). */
sealed interface DraftPoint {
    /** Un punto del proyecto; se lee con su estado de ahora, de modo que editarlo recalcula (S038/RF-27). */
    data class OfProject(val pointId: String, val name: String) : DraftPoint

    /** Un Este y un Norte escritos, en el SC del proyecto y en metros (S038/RF-3). */
    data class Typed(val easting: Double, val northing: Double) : DraftPoint
}

/**
 * El borrador de la poligonal de un proyecto (S038/RF-26 a RF-30): el punto de inicio, el de referencia y el de cierre, y los tramos
 * escritos en SI (radianes y metros; null lo que no se ha escrito o no es un número). No es ningún punto: vive en el proyecto, uno solo,
 * hasta que se guardan todos sus puntos o el usuario lo descarta.
 */
data class TraverseDraft(
    val projectId: String,
    val start: DraftPoint?,
    val reference: DraftPoint?,
    val closure: DraftPoint?,
    val legs: List<TraverseLeg>,
    val updatedAtMs: Long,
) {
    /** Sin nada escrito: no merece guardarse. */
    val isEmpty: Boolean get() = start == null && reference == null && closure == null && legs.isEmpty()
}
