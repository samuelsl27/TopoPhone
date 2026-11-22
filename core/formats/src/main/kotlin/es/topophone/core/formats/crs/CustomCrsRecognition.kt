package es.topophone.core.formats.crs

import es.topophone.core.formats.shp.PrjReader
import es.topophone.core.formats.shp.ShpCrsCatalog
import es.topophone.core.geodesy.catalog.CustomCrsWkt
import es.topophone.core.model.project.CrsDefinition

/**
 * Reconoce el SC personalizado de un proyecto en el WKT de un `.prj` o de un GeoPackage (S035/RF-24): el fichero exportado desde ese proyecto vuelve a
 * ser el SC del proyecto, sin pedir al usuario que lo elija. Se compara por método, parámetros, unidad y elipsoide con el WKT que la app escribe para
 * esa definición, no por el nombre.
 */
object CustomCrsRecognition {

    /** ¿Es [wkt] el SC [definition] de un proyecto? `false` si el WKT no se lee o la definición no tiene forma en WKT1. */
    fun sameAs(wkt: String?, definition: CrsDefinition): Boolean {
        val candidate = wkt?.let(PrjReader::parse) ?: return false
        val reference = CustomCrsWkt.wkt1(definition)?.let(PrjReader::parse) ?: return false
        return ShpCrsCatalog.sameDefinition(candidate, reference)
    }
}
