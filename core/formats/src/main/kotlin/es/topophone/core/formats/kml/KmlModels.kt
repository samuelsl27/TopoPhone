package es.topophone.core.formats.kml

import es.topophone.core.model.cad.GpkgIgnoredReason
import java.io.InputStream

/**
 * Una parte de la geometría de una marca: un punto, una línea, un anillo o el contorno o un hueco de un polígono (S031/RF-8). [contour] es
 * `true` en el exterior y los huecos de un polígono; [absolute] dice que su `altitudeMode` es `absolute` (RF-14).
 */
class KmlPart(val vertices: KmlVertices, val contour: Boolean, val absolute: Boolean) {
    /** La parte lleva Z: su modo es absoluto y todos sus vértices traen altitud (RF-13, RF-14). */
    val hasZ: Boolean get() = absolute && vertices.alt != null
}

/** Las partes válidas de una marca de un tipo (puntos o líneas) y cuántas geometrías vacías tenía (S031/RF-9). */
class KmlGeometryGroup(val parts: List<KmlPart>, val empty: Int) {
    val isNothing: Boolean get() = parts.isEmpty() && empty == 0
}

/**
 * Una marca con geometría tal como sale del KML (S031/RF-5, RF-8, RF-10 a RF-12). [path] es la ruta de sus carpetas, vacía si no está en
 * ninguna; [data] son sus datos extendidos por nombre, en orden de aparición y con el último valor si un nombre se repite, y los colores son
 * ARGB opacos o `null` si su estilo no los declara.
 */
class KmlPlacemark(
    val path: List<String>,
    val name: String,
    val description: String,
    val data: Map<String, String>,
    val points: KmlGeometryGroup,
    val lines: KmlGeometryGroup,
    val iconColor: Int?,
    val lineColor: Int?,
)

/** Los ficheros de un KMZ: los nombres de sus entradas y cómo abrir una (S031/RF-2b). */
class KmzEntries(val names: List<String>, val open: (String) -> InputStream?)

/** Lo que el lector cuenta además de las marcas: los elementos que no se importan, por motivo (S031/RF-9). */
class KmlParseResult(val ignored: Map<GpkgIgnoredReason, Int>)

/** Un KML o un KMZ cuyo XML está cortado o mal formado (S031/RF-3). */
class KmlDamagedException(message: String, cause: Throwable? = null) : Exception(message, cause)
