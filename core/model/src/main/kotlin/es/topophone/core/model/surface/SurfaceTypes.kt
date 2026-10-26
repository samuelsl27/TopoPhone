package es.topophone.core.model.surface

import es.topophone.core.model.cad.CadSourceCrs
import es.topophone.core.model.project.LinearUnit

/** El recuadro de una superficie en el sistema del proyecto, en metros (S042/RF-8, RF-13). */
data class SurfaceExtent(val minEasting: Double, val maxEasting: Double, val minNorthing: Double, val maxNorthing: Double) {
    /** El centro del recuadro. */
    val centerEasting: Double get() = (minEasting + maxEasting) / 2
    val centerNorthing: Double get() = (minNorthing + maxNorthing) / 2

    /** La posición cae dentro del recuadro, borde incluido. */
    fun contains(easting: Double, northing: Double): Boolean = easting in minEasting..maxEasting && northing in minNorthing..maxNorthing
}

/**
 * Una superficie importada de un LandXML en un proyecto (S042). Las coordenadas y las cotas guardadas están ya en el sistema y en
 * los metros del proyecto; [source] y [unit] son lo que el usuario eligió al importar (RF-6, RF-7). [holeCount] son los triángulos
 * que el fichero marcaba como invisibles (RF-12). La cota no cambia de sistema de alturas (suposición de la spec).
 */
data class Surface(
    val id: String,
    val projectId: String,
    val name: String,
    val fileName: String,
    val importedAtMs: Long,
    val source: CadSourceCrs,
    val unit: LinearUnit,
    val extent: SurfaceExtent,
    val minElevationM: Double,
    val maxElevationM: Double,
    val vertexCount: Int,
    val triangleCount: Int,
    val holeCount: Int,
)

/**
 * La cota de una superficie en una posición (S042/RF-22, RF-23): su valor en metros, [Outside] si la posición está fuera del
 * contorno o en un hueco, o [Loading] mientras la parte de la superficie que la contiene aún no está en memoria. Cargando nunca se
 * presenta como fuera de la superficie.
 */
sealed interface SurfaceHeight {
    data class Value(val metres: Double) : SurfaceHeight

    data object Outside : SurfaceHeight

    data object Loading : SurfaceHeight

    /** La cota en metros, o null si no la hay (fuera o cargando). */
    val metresOrNull: Double? get() = (this as? Value)?.metres

    /** La misma cota con el desplazamiento vertical sumado (RF-24); fuera y cargando no cambian. */
    fun withOffset(offsetM: Double): SurfaceHeight = if (this is Value) Value(metres + offsetM) else this
}
