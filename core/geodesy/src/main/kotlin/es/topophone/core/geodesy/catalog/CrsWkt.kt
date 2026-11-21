package es.topophone.core.geodesy.catalog

import java.io.InputStream
import java.util.zip.GZIPInputStream

/**
 * Las definiciones WKT1 de los SC de la biblioteca para exportar (S057/RF-22, RF-23, RF-26). Las genera `tools/geodesia/catalogo_wkt.py` con
 * pyproj (ADR-3) en un recurso aparte, para que buscar SC no cargue su texto; se lee la primera vez que se exporta.
 *
 * Cada SC tiene dos definiciones: la de GeoPackage (`gpkg_spatial_ref_sys`, WKT1 de GDAL) y la del `.prj` de Shapefile (WKT1 de ESRI).
 */
class CrsWkt private constructor(private val rows: Map<Int, Pair<String, String>>) {
    /** El WKT1 de GDAL del SC, o `null` si PROJ no pudo expresarlo (la exportación lo avisa). */
    fun gpkg(epsg: Int): String? = rows[epsg]?.first?.takeIf { it.isNotEmpty() }

    /** El WKT1 de ESRI del SC (el del `.prj`), o `null` si PROJ no pudo expresarlo. */
    fun prj(epsg: Int): String? = rows[epsg]?.second?.takeIf { it.isNotEmpty() }

    companion object {
        private const val RESOURCE = "/catalog/crs_wkt.tsv.gz"

        /** Las definiciones incluidas en la app. */
        val bundled: CrsWkt by lazy {
            val stream = CrsWkt::class.java.getResourceAsStream(RESOURCE) ?: error("Falta el recurso $RESOURCE")
            stream.use { load(it) }
        }

        fun load(stream: InputStream): CrsWkt = parse(GZIPInputStream(stream).bufferedReader(Charsets.UTF_8).readText())

        fun parse(text: String): CrsWkt {
            val rows = HashMap<Int, Pair<String, String>>()
            for (line in text.lineSequence()) {
                if (line.isEmpty()) continue
                val columns = line.split('\t')
                require(columns.size == 3) { "Fila de definiciones con ${columns.size} columnas: ${line.take(40)}" }
                rows[columns[0].toInt()] = columns[1] to columns[2]
            }
            return CrsWkt(rows)
        }
    }
}
