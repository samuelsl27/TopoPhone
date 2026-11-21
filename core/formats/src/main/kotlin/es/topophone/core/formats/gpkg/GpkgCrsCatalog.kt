package es.topophone.core.formats.gpkg

import es.topophone.core.formats.crs.LibraryCrsRecognition
import es.topophone.core.formats.crs.RecognizedCrs
import es.topophone.core.model.cad.CadSourceCrs
import es.topophone.core.model.project.GeodeticDatum
import es.topophone.core.model.project.ProjectCrs

/** La unidad de las coordenadas de una capa, que fija su sistema (S048/RF-10). */
enum class GpkgCrsUnit { METRE, DEGREE }

/** Lo que dice el GeoPackage del sistema de coordenadas de una capa, frente a los sistemas de la app (S048/RF-8, RF-9). */
sealed interface GpkgCrs {
    /**
     * Un sistema de los que ofrece la app (S008), con el que se reproyecta a ciegas. [wgs84] es EPSG:4326, que se trata como ETRS89
     * y se avisa de que pueden diferir unos 0,9 m (decisión D3 del plan).
     */
    data class Supported(val epsg: Int, val source: CadSourceCrs, val unit: GpkgCrsUnit, val wgs84: Boolean = false) : GpkgCrs

    /**
     * Un sistema con autoridad y código que la app no ofrece: la capa espera a que el usuario elija uno (RF-9). [name] es el nombre que
     * trae el fichero, para mostrarlo (un Shapefile no siempre tiene código: S030/RF-9).
     */
    data class Unavailable(
        val authority: String,
        val code: Int,
        val name: String? = null,
        /** Varios SC de la biblioteca se llaman como el del fichero (S057/RF-5): el usuario elige entre ellos. */
        val candidates: List<RecognizedCrs> = emptyList(),
    ) : GpkgCrs

    /**
     * Un SC de la biblioteca de S034 que no es de los de España (S057/RF-4, RF-5): se reconoce por su código EPSG o por su nombre. Todavía no
     * es un [CadSourceCrs]: su definición se construye con el datum de las correcciones y el centro del proyecto (S057/RF-2).
     */
    data class Library(val epsg: Int, val name: String) : GpkgCrs

    /** `srs_id` 0 o −1, sin definición o sin fila en `gpkg_spatial_ref_sys`: el fichero no declara el sistema (RF-9). */
    data object Undeclared : GpkgCrs
}

/**
 * Del `srs_id` y la fila de `gpkg_spatial_ref_sys` de una capa al sistema de la app (S048/RF-8 a RF-10). Reconoce exactamente los
 * [ProjectCrs] de S008 por su código EPSG: ETRS89 UTM 29 a 31, REGCAN95 UTM 27 y 28, ED50 UTM 29 a 31 y las geográficas ETRS89 y
 * REGCAN95; más EPSG:4326, que se trata como ETRS89. La lista se contrasta con pyproj en `tools`.
 */
object GpkgCrsCatalog {
    const val WGS84_EPSG = 4326
    private const val EPSG = "EPSG"
    private const val NONE = "NONE"

    /**
     * El sistema de una capa. [authority] y [authorityCode] son las columnas `organization` y `organization_coordsys_id` de su fila de
     * `gpkg_spatial_ref_sys`, o null si no la tiene. Los `srs_id` 0 y −1 son siempre «sin sistema».
     */
    fun resolve(srsId: Int, authority: String?, authorityCode: Int?): GpkgCrs {
        if (authority == null || authorityCode == null || srsId <= 0 || authority.equals(NONE, ignoreCase = true)) return GpkgCrs.Undeclared
        if (!authority.equals(EPSG, ignoreCase = true)) return GpkgCrs.Unavailable(authority.uppercase(), authorityCode)
        return resolveEpsg(authorityCode) ?: library(authorityCode) ?: GpkgCrs.Unavailable(EPSG, authorityCode)
    }

    /** El SC de la biblioteca con el código EPSG dado, o null si no lo tiene (S057/RF-4). */
    fun library(epsg: Int): GpkgCrs.Library? = LibraryCrsRecognition.byEpsg(epsg)?.let { GpkgCrs.Library(it.epsg, it.name) }

    /** El sistema de la app con el código EPSG dado, o null si no es uno de los suyos. */
    fun resolveEpsg(epsg: Int): GpkgCrs.Supported? {
        if (epsg == WGS84_EPSG) return wgs84()
        return ProjectCrs.entries.firstOrNull { it.epsg == epsg }?.let(::supported)
    }

    private fun wgs84() = GpkgCrs.Supported(WGS84_EPSG, CadSourceCrs.Geographic(GeodeticDatum.ETRS89), GpkgCrsUnit.DEGREE, wgs84 = true)

    /** Un sistema elegido por el usuario para una capa sin sistema o con uno no disponible (RF-9). */
    fun supported(crs: ProjectCrs): GpkgCrs.Supported {
        val zone = crs.utmZone
        return if (zone != null) {
            GpkgCrs.Supported(crs.epsg, CadSourceCrs.Utm(crs.datum, zone), GpkgCrsUnit.METRE)
        } else {
            GpkgCrs.Supported(crs.epsg, CadSourceCrs.Geographic(crs.datum), GpkgCrsUnit.DEGREE)
        }
    }

    /** Los sistemas que el usuario puede elegir para una capa: los de la app, sin repetir. */
    val choices: List<GpkgCrs.Supported> get() = ProjectCrs.entries.map(::supported)
}
