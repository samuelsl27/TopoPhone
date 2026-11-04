package es.topophone.core.formats.gpkg

import es.topophone.core.formats.csv.AttributeColumn
import java.util.Locale

/**
 * Las capas de líneas y de polígonos del GeoPackage que exporta S025 (S025/RF-26, plan D13): `lines` (LineString) con las líneas abiertas y
 * `polygons` (Polygon) con las cerradas. Cada una lleva `fid`, la geometría, el nombre, el código, su descripción, el número de vértices y una
 * columna tipada por cada atributo de los códigos como en la capa de puntos (S024/RF-21).
 */
object GpkgLineSchema {
    const val LINES_TABLE = "lines"
    const val POLYGONS_TABLE = "polygons"

    /** Las columnas fijas de las dos capas, con su tipo SQL. */
    val fixed: List<Pair<String, GpkgAttributeType>> = listOf(
        "Name" to GpkgAttributeType.TEXT,
        "Code" to GpkgAttributeType.TEXT,
        "Description" to GpkgAttributeType.TEXT,
        "Vertices" to GpkgAttributeType.INTEGER,
    )

    /** `CREATE TABLE` de [table]; [geometryType] es `LINESTRING` o `POLYGON`. */
    fun createTable(table: String, geometryType: String, codeAttributes: List<AttributeColumn> = emptyList()): String {
        val names = attributeNames(codeAttributes)
        val dynamic = codeAttributes.mapIndexed { i, a -> "${quote(names[i])} ${GpkgPointAttributes.typeOf(a).sql}" }
        val columns = fixed.map { (name, type) -> "${quote(name)} ${type.sql}" } + dynamic
        return "CREATE TABLE ${quote(table)} (fid INTEGER PRIMARY KEY AUTOINCREMENT, ${GpkgExportSchema.GEOMETRY_COLUMN} $geometryType, " +
            columns.joinToString(", ") + ")"
    }

    /** La inserción de una fila: la geometría, las columnas fijas y las de [codeAttributes]. */
    fun insert(table: String, codeAttributes: List<AttributeColumn> = emptyList()): String {
        val names = (fixed.map { it.first } + attributeNames(codeAttributes)).joinToString(", ") { quote(it) }
        val markers = List(fixed.size + codeAttributes.size + 1) { "?" }.joinToString(", ")
        return "INSERT INTO ${quote(table)} (${GpkgExportSchema.GEOMETRY_COLUMN}, $names) VALUES ($markers)"
    }

    /** Parámetros: tabla, columna, tipo de geometría y `srs_id`; `z = 2` (opcional) y `m = 0`. */
    const val INSERT_GEOMETRY_COLUMNS = "INSERT INTO gpkg_geometry_columns (table_name, column_name, geometry_type_name, srs_id, z, m) " +
        "VALUES (?, ?, ?, ?, 2, 0)"

    /** Los nombres de las columnas de atributos, distintos entre sí y de las fijas (sin distinguir mayúsculas), como en la capa de puntos. */
    fun attributeNames(codeAttributes: List<AttributeColumn>): List<String> {
        val taken = (
            fixed.map {
                it.first
            } + listOf("fid", GpkgExportSchema.GEOMETRY_COLUMN)
            ).mapTo(HashSet()) { it.lowercase(Locale.ROOT) }
        return codeAttributes.map { column ->
            var name = column.header
            if (name.lowercase(Locale.ROOT) in taken) name = "${column.header} (atributo)"
            var n = 2
            while (name.lowercase(Locale.ROOT) in taken) name = "${column.header} (atributo $n)".also { n++ }
            taken += name.lowercase(Locale.ROOT)
            name
        }
    }

    private fun quote(name: String) = "\"$name\""
}
