package es.topophone.core.formats.kml.export

/** En qué fichero se exporta: un KML suelto o un KMZ, que puede llevar las fotos de los puntos (S031/RF-20, RF-28). */
enum class KmlFormat { KML, KMZ }

/** Lo que elige el usuario al exportar a KML o KMZ (S031/RF-20, RF-26, RF-28). */
data class KmlExportOptions(
    val format: KmlFormat = KmlFormat.KML,
    /** Escribir la altura del proyecto en modo absoluto (S031/RF-26); desmarcada por defecto. */
    val includeHeight: Boolean = false,
    /** Llevar las fotos de los puntos dentro de un KMZ (S031/RF-28); desmarcada por defecto y solo con KMZ. */
    val includePhotos: Boolean = false,
)

/** Un vértice en longitud y latitud (grados) con su altura en metros si la lleva. */
data class KmlVertex(val lon: Double, val lat: Double, val height: Double? = null)

/** La geometría de una marca exportada: un punto, una línea o un polígono de un solo contorno (S031/RF-21, RF-22). */
sealed interface KmlGeometry {
    data class Point(val vertex: KmlVertex) : KmlGeometry

    data class Line(val vertices: List<KmlVertex>) : KmlGeometry

    /** El contorno exterior en sentido antihorario y con su primer vértice repetido al final (RF-22). */
    data class Polygon(val ring: List<KmlVertex>) : KmlGeometry
}

/**
 * Una marca exportada (S031/RF-21 a RF-23): [data] son sus datos extendidos por orden y [photos] los nombres de las fotos que lleva en el KMZ
 * (RF-28). [absolute] escribe la altura de sus vértices en modo absoluto; sin ella, la marca va pegada al suelo (RF-26, RF-27).
 */
class KmlMark(
    val name: String,
    val description: String,
    val styleId: String?,
    val data: List<Pair<String, String>>,
    val geometry: KmlGeometry,
    val absolute: Boolean = false,
    val photos: List<String> = emptyList(),
)

/** Una carpeta con las marcas de un código, o con todas si no hay códigos (S031/RF-23). */
class KmlFolder(val name: String, val marks: List<KmlMark>)

/** El estilo de un código: su color ARGB opaco (S031/RF-23). */
class KmlStyleDef(val id: String, val argb: Int)

/** El documento que se escribe: su nombre, los estilos de los códigos y las carpetas (S031/RF-21 a RF-23). */
class KmlDocument(val name: String, val styles: List<KmlStyleDef>, val folders: List<KmlFolder>)
