package es.topophone.core.formats.gpkg

import es.topophone.core.model.project.GeodeticDatum
import es.topophone.core.model.project.ProjectCrs

/** Una fila de `gpkg_spatial_ref_sys`: el sistema de coordenadas de una capa tal como lo declara el fichero (S053/RF-6, RF-8). */
data class GpkgSrsDefinition(
    val srsId: Int,
    val name: String,
    val organization: String,
    val organizationCode: Int,
    val definition: String,
    val description: String? = null,
) {
    /** Sin autoridad ni código reconocido: lleva su definición completa y nada más (RF-8). */
    val isCustom: Boolean get() = organization == GpkgSrsDefinitions.NONE && srsId >= GpkgSrsDefinitions.CUSTOM_SRS_ID
}

/**
 * Los sistemas de coordenadas que escribe la exportación (S053/RF-6 a RF-8, plan A3): las definiciones WKT1 de los sistemas de S008 y de
 * sus geográficas salen de pyproj ([GpkgSrsDefinitionsData], generado por `tools/formatos/generar_gpkg_exportado.py`), las tres filas
 * obligatorias de GeoPackage y, para un sistema sin código reconocido, su definición completa sin autoridad.
 */
object GpkgSrsDefinitions {
    internal const val NONE = "NONE"
    private const val EPSG = "EPSG"
    private const val WGS84 = 4326
    private const val ETRS89_GEOGRAPHIC = 4258
    private const val REGCAN95_GEOGRAPHIC = 4081

    /** El `srs_id` de un sistema definido a mano: GeoPackage reserva los bajos para los códigos de las autoridades (RF-8). */
    const val CUSTOM_SRS_ID = 100_000

    private val byCode: Map<Int, GpkgSrsDefinition> = GpkgSrsDefinitionsData.ALL.associate { (code, name, wkt) ->
        code to GpkgSrsDefinition(code, name, EPSG, code, wkt)
    }

    /** Las tres filas que exige GeoPackage: sin sistema cartesiano, sin sistema geográfico y WGS 84 (RF-6). */
    val mandatory: List<GpkgSrsDefinition> = listOf(
        GpkgSrsDefinition(-1, "Undefined Cartesian SRS", NONE, -1, "undefined", "undefined Cartesian coordinate reference system"),
        GpkgSrsDefinition(0, "Undefined geographic SRS", NONE, 0, "undefined", "undefined geographic coordinate reference system"),
        byCode.getValue(WGS84).copy(description = "longitude/latitude coordinates in decimal degrees on the WGS 84 spheroid"),
    )

    /** El sistema del proyecto con su código EPSG y su definición (RF-6). */
    fun of(crs: ProjectCrs): GpkgSrsDefinition = byCode.getValue(crs.epsg)

    /**
     * Las geográficas en las que están la latitud y la longitud de los puntos (RF-7, D3): las del datum de los cálculos. El móvil las calcula en
     * ETRS89 o REGCAN95 también en un proyecto ED50, así que ese declara ETRS89.
     */
    fun geographicOf(crs: ProjectCrs): GpkgSrsDefinition =
        byCode.getValue(if (crs.datum == GeodeticDatum.REGCAN95) REGCAN95_GEOGRAPHIC else ETRS89_GEOGRAPHIC)

    /** Un sistema sin código reconocido con su [definition] completa (WKT) y sin autoridad (RF-8). */
    fun custom(name: String, definition: String): GpkgSrsDefinition =
        GpkgSrsDefinition(CUSTOM_SRS_ID, name, NONE, CUSTOM_SRS_ID, definition)
}
