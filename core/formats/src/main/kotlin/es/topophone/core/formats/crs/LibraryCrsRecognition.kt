package es.topophone.core.formats.crs

import es.topophone.core.geodesy.catalog.CrsCatalog
import es.topophone.core.geodesy.catalog.CrsWkt
import java.util.Locale

/** Un SC de la biblioteca de S034 reconocido en un fichero (S057/RF-4, RF-5): su código EPSG y su nombre. */
data class RecognizedCrs(val epsg: Int, val name: String)

/** Lo que da buscar en la biblioteca por el nombre del SC de un fichero (S057/RF-5). */
sealed interface NameMatch {
    /** Un solo SC de la biblioteca tiene ese nombre: se propone. */
    data class Single(val crs: RecognizedCrs) : NameMatch

    /** Varios SC de la biblioteca se llaman igual: el usuario elige entre [candidates] (S057/RF-6). */
    data class Several(val candidates: List<RecognizedCrs>) : NameMatch

    /** Ninguno: el usuario elige uno de la biblioteca (S057/RF-6). */
    data object None : NameMatch
}

/**
 * Reconoce el SC de un Shapefile, un GeoPackage o un GeoTIFF en la biblioteca (S057/RF-4, RF-5, RF-6): por el código EPSG que declara, y si no
 * lo trae (el caso habitual de un `.prj` de ArcGIS o de QGIS), por su nombre normalizado —en minúsculas y sin signos ni espacios— frente al
 * nombre de EPSG de cada SC y al que escribe ESRI en su WKT (`ETRS_1989_UTM_Zone_30N` por `ETRS89 / UTM zone 30N`).
 */
object LibraryCrsRecognition {
    private val catalog get() = CrsCatalog.bundled

    /** El SC con el código EPSG [epsg], o null si la biblioteca no lo tiene. */
    fun byEpsg(epsg: Int): RecognizedCrs? = catalog.crsByEpsg(epsg)?.let { RecognizedCrs(it.epsg, it.name) }

    /** Los SC de la biblioteca cuyo nombre, de EPSG o de ESRI, es [name] una vez normalizado. */
    fun byName(name: String): NameMatch {
        val key = normalized(name)
        if (key.isEmpty()) return NameMatch.None
        val found = namesIndex[key].orEmpty().sorted().mapNotNull(::byEpsg)
        return when (found.size) {
            0 -> NameMatch.None
            1 -> NameMatch.Single(found.single())
            else -> NameMatch.Several(found)
        }
    }

    /** Minúsculas y solo letras y cifras: `ETRS_1989_UTM_Zone_30N` y `ETRS 1989 UTM zone 30N` son lo mismo. */
    fun normalized(name: String): String = name.lowercase(Locale.ROOT).filter { it.isLetterOrDigit() }

    private val namesIndex: Map<String, Set<Int>> by lazy {
        val wkt = CrsWkt.bundled
        val index = HashMap<String, MutableSet<Int>>()
        for (crs in catalog.crs) {
            index.getOrPut(normalized(crs.name)) { mutableSetOf() }.add(crs.epsg)
            wkt.prj(crs.epsg)?.let(::esriName)?.let { index.getOrPut(normalized(it)) { mutableSetOf() }.add(crs.epsg) }
        }
        index
    }

    /** El nombre del SC que escribe el WKT1 de ESRI: el primer texto entre comillas. */
    private fun esriName(wkt: String): String? {
        val start = wkt.indexOf('"')
        if (start < 0) return null
        val end = wkt.indexOf('"', start + 1)
        return if (end > start) wkt.substring(start + 1, end) else null
    }
}
