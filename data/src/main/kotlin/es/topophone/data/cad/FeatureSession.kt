package es.topophone.data.cad

import es.topophone.core.formats.gpkg.GpkgCrs
import es.topophone.core.model.cad.CadOrigin
import es.topophone.core.model.cad.GpkgIgnoredReason
import es.topophone.core.model.cad.GpkgLayerInfo
import es.topophone.core.model.cad.GpkgValue
import es.topophone.core.survey.LayerBox

/**
 * Un objeto de una capa: su clave, su blob de geometría de GeoPackage y sus atributos en el orden de [GpkgLayerInfo.columns] (S048/RF-17,
 * RF-20). [ignored] es el motivo por el que la sesión ya sabe que no se puede importar —un registro borrado en el `.dbf` o un `MultiPatch`
 * de un Shapefile (S030/RF-16)—; entonces no hace falta su geometría.
 */
class GpkgFeature(val fid: Long, val geometry: ByteArray?, val values: List<GpkgValue>, val ignored: GpkgIgnoredReason? = null)

/**
 * Lo que necesitan el importador y el recuento por zona de un fichero abierto de solo lectura (S048/RF-5 a RF-16, S030/D1): las capas con
 * geometría, el sistema de cada una y sus objetos, y de ahí las cabeceras con la envolvente y las geometrías por clave. La cumplen
 * `GpkgSession` (un GeoPackage) y `ShapefileSession` (un Shapefile): el resto de la importación no sabe de qué formato viene.
 */
interface FeatureSession : AutoCloseable {
    /** De qué formato es el fichero: el origen que se guarda con el dibujo (S048/RF-26, S030/RF-26). */
    val origin: CadOrigin

    val layers: List<GpkgLayerInfo>

    /** Lo que se dejó fuera del fichero y se avisa (tablas sin geometría, ficheros huérfanos…) (S048/RF-6, S030/RF-5). */
    val leftOutTables: List<String>

    /** El sistema de coordenadas que declara la capa, frente a los de la app (S048/RF-8, RF-9). */
    fun crs(layer: GpkgLayerInfo): GpkgCrs

    /**
     * Recorre los objetos de [layer], uno a uno y sin cargarlos todos. Con [box] y un índice espacial, solo los objetos cuya envolvente corta
     * el recuadro (en el sistema de la capa); sin índice, todos: el prefiltro lo hace quien llama.
     */
    fun forEachFeature(layer: GpkgLayerInfo, box: LayerBox? = null, action: (GpkgFeature) -> Unit)

    /** Recorre la clave y la cabecera del blob de geometría —con su envolvente— de cada objeto con geometría; [head] es null si es nula. */
    fun forEachHead(layer: GpkgLayerInfo, action: (fid: Long, head: ByteArray?) -> Unit)

    /** Recorre las envolventes de un índice espacial; false, sin llamar a [action], si la capa no lo tiene. */
    fun forEachIndexRow(layer: GpkgLayerInfo, action: (fid: Long, minX: Double, maxX: Double, minY: Double, maxY: Double) -> Unit): Boolean

    /** Las geometrías completas de esos objetos. [geometry] es null si es nula. */
    fun forEachGeometry(layer: GpkgLayerInfo, ids: LongArray, action: (fid: Long, geometry: ByteArray?) -> Unit)
}
