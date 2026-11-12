package es.topophone.core.formats.shp.export

import es.topophone.core.model.project.GeodeticDatum
import es.topophone.core.model.project.ProjectCrs

/**
 * El contenido del `.prj` de cada Shapefile que exporta S055 (RF-15, RF-16, plan A4): el WKT1 de ESRI de los sistemas de S008 y de las
 * geográficas de su datum, que sale de pyproj ([ShpPrjDefinitionsData], generado por `tools/formatos/generar_shp_exportado.py`) y que lee
 * QGIS, GDAL, ArcGIS y pyproj. Un sistema que no tuviera definición da `null` y la exportación no escribe su `.prj` (RF-16).
 *
 * @param byCode el WKT de cada código EPSG; solo cambia en los tests, para probar un sistema sin definición.
 */
class ShpPrjDefinitions(private val byCode: Map<Int, String> = ShpPrjDefinitionsData.ALL.associate { (code, _, wkt) -> code to wkt }) {

    /** El `.prj` del sistema del proyecto (S055/RF-15), o `null` si no tiene definición. */
    fun of(crs: ProjectCrs): String? = byCode[crs.epsg]

    /**
     * El `.prj` de las geográficas en las que están la latitud y la longitud de los puntos: las del datum de los cálculos, ETRS89 o REGCAN95.
     * Un proyecto ED50 exporta ETRS89, como el GeoPackage (S053/D3). `null` si no tiene definición.
     */
    fun geographicOf(crs: ProjectCrs): String? = byCode[geographicEpsg(crs)]

    companion object {
        private const val ETRS89_GEOGRAPHIC = 4258
        private const val REGCAN95_GEOGRAPHIC = 4081

        /** El código EPSG de las geográficas de [crs]. */
        fun geographicEpsg(crs: ProjectCrs): Int = if (crs.datum == GeodeticDatum.REGCAN95) REGCAN95_GEOGRAPHIC else ETRS89_GEOGRAPHIC
    }
}
