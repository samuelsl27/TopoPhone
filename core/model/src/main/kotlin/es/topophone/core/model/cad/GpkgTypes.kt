package es.topophone.core.model.cad

import es.topophone.core.model.project.CentralPoint

/** La zona de la que se importan objetos de un GeoPackage: todo el contenido o un círculo alrededor de un punto (S048/RF-13). */
sealed interface ImportArea {
    /** «Todo el contenido». */
    data object All : ImportArea

    /** Los objetos con algún vértice a menos de [radiusM] metros de [center]. */
    data class Circle(val center: CentralPoint, val radiusM: Double) : ImportArea {
        init {
            require(radiusM in MIN_RADIUS_M..MAX_RADIUS_M) { "El radio debe estar entre $MIN_RADIUS_M y $MAX_RADIUS_M m: $radiusM" }
        }
    }

    companion object {
        const val MIN_RADIUS_M = 100.0
        const val MAX_RADIUS_M = 100_000.0
        const val DEFAULT_RADIUS_M = 5_000.0

        /** Más objetos que esto en la zona y no se importa (S048/RF-16). */
        const val MAX_OBJECTS = 500_000

        /** La zona propuesta alrededor del centro del proyecto: 5 km (S048/RF-13). */
        fun proposed(center: CentralPoint): ImportArea = Circle(center, DEFAULT_RADIUS_M)
    }
}

/** Lo que dice la lista de capas del tipo de geometría de una tabla (S048/RF-5). */
enum class GpkgLayerKind {
    POINTS,
    LINES,
    POLYGONS,
    MIXED,
    ;

    companion object {
        /** El tipo de `gpkg_geometry_columns.geometry_type_name`: `POINT`, `MULTIPOLYGON`, `GEOMETRY`… */
        fun of(geometryTypeName: String): GpkgLayerKind = when (geometryTypeName.trim().uppercase()) {
            "POINT", "MULTIPOINT" -> POINTS
            "LINESTRING", "MULTILINESTRING" -> LINES
            "POLYGON", "MULTIPOLYGON" -> POLYGONS
            else -> MIXED
        }
    }
}

/** Una columna de la tabla de una capa, con el tipo que declara el GeoPackage. */
data class GpkgColumn(val name: String, val type: String)

/**
 * Una capa con geometría de un GeoPackage (S048/RF-5): su tabla, su columna de geometría, el tipo que declara, cuántos objetos tiene,
 * su SRS (`srs_id`; 0 y −1 son «sin sistema») y las columnas de atributos, sin la clave ni la geometría.
 */
data class GpkgLayerInfo(
    val table: String,
    val geometryColumn: String,
    val geometryType: String,
    val featureCount: Long,
    val srsId: Int,
    val columns: List<GpkgColumn>,
    val hasSpatialIndex: Boolean = false,
) {
    val kind: GpkgLayerKind get() = GpkgLayerKind.of(geometryType)
}

/** Por qué no entra un objeto de un GeoPackage (S048/RF-11, RF-18). */
enum class GpkgIgnoredReason {
    EMPTY_GEOMETRY,
    CURVE_GEOMETRY,
    INVALID_GEOMETRY,
    OUTSIDE_CRS_AREA,
    OUTSIDE_ED50_GRID,
}

/** Lo que pasó con los objetos de una capa al importar (S048/RF-25). */
data class GpkgLayerSummary(
    val table: String,
    val imported: Int,
    val outsideArea: Int,
    val ignored: Map<GpkgIgnoredReason, Int> = emptyMap(),
    val omittedAttributes: Int = 0,
) {
    val ignoredTotal: Int get() = ignored.values.sum()
}

/** El resumen por capa que se muestra al terminar la importación (S048/RF-25). */
data class GpkgImportSummary(
    val layers: List<GpkgLayerSummary>,
    /** Tablas sin geometría o de teselas que se dejaron fuera (S048/RF-6). */
    val leftOutTables: List<String> = emptyList(),
    /** Capas en EPSG:4326 tratadas como ETRS89 (S048, decisión D3). */
    val wgs84Layers: List<String> = emptyList(),
) {
    val importedTotal: Int get() = layers.sumOf { it.imported }
    val outsideAreaTotal: Int get() = layers.sumOf { it.outsideArea }
    val ignoredTotal: Int get() = layers.sumOf { it.ignoredTotal }
}
