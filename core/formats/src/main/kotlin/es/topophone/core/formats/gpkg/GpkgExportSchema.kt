package es.topophone.core.formats.gpkg

import es.topophone.core.formats.csv.AttributeColumn
import es.topophone.core.formats.csv.ExportColumn
import java.util.Locale

/**
 * El esquema del GeoPackage que exporta S053 (plan «Modelo de datos del fichero»): las tablas obligatorias de GeoPackage 1.3 y la capa de
 * puntos, con una columna por cada columna de "Todas las columnas" de S012 con su tipo (RF-4). El fichero lleva el `application_id` `GPKG`
 * y la `user_version` 10300, el índice espacial no se escribe (D5) y la capa declara la Z como opcional: unos puntos tienen altura y otros no.
 */
object GpkgExportSchema {
    /** `PRAGMA application_id`: "GPKG" como entero. */
    const val APPLICATION_ID = 0x47504B47

    /** `PRAGMA user_version`: GeoPackage 1.3.0. */
    const val USER_VERSION = 10300

    const val POINTS_TABLE = "points"
    const val GEOMETRY_COLUMN = "geom"

    /** Las columnas de atributos de la capa de puntos, en el orden de "Todas las columnas" (RF-4). */
    val columns: List<ExportColumn> get() = ExportColumn.entries

    /** Las tablas obligatorias, en el orden en que se crean (la de contenidos apunta a la de sistemas y la de geometrías a las dos). */
    val createTables: List<String> = listOf(
        "CREATE TABLE gpkg_spatial_ref_sys (srs_name TEXT NOT NULL, srs_id INTEGER PRIMARY KEY, organization TEXT NOT NULL, " +
            "organization_coordsys_id INTEGER NOT NULL, definition TEXT NOT NULL, description TEXT)",
        "CREATE TABLE gpkg_contents (table_name TEXT NOT NULL PRIMARY KEY, data_type TEXT NOT NULL, identifier TEXT UNIQUE, " +
            "description TEXT DEFAULT '', last_change DATETIME NOT NULL, min_x DOUBLE, min_y DOUBLE, max_x DOUBLE, max_y DOUBLE, " +
            "srs_id INTEGER, CONSTRAINT fk_gc_r_srs_id FOREIGN KEY (srs_id) REFERENCES gpkg_spatial_ref_sys(srs_id))",
        "CREATE TABLE gpkg_geometry_columns (table_name TEXT NOT NULL, column_name TEXT NOT NULL, geometry_type_name TEXT NOT NULL, " +
            "srs_id INTEGER NOT NULL, z TINYINT NOT NULL, m TINYINT NOT NULL, " +
            "CONSTRAINT pk_geom_cols PRIMARY KEY (table_name, column_name), CONSTRAINT uk_gc_table_name UNIQUE (table_name), " +
            "CONSTRAINT fk_gc_tn FOREIGN KEY (table_name) REFERENCES gpkg_contents(table_name), " +
            "CONSTRAINT fk_gc_srs FOREIGN KEY (srs_id) REFERENCES gpkg_spatial_ref_sys (srs_id))",
    )

    const val INSERT_SRS = "INSERT INTO gpkg_spatial_ref_sys (srs_name, srs_id, organization, organization_coordsys_id, definition, " +
        "description) VALUES (?, ?, ?, ?, ?, ?)"

    /** Parámetros: nombre de la tabla, identificador, descripción, fecha del último cambio, extensión (mínimo X, mínimo Y, máximo X, máximo Y) y `srs_id`. */
    const val INSERT_CONTENTS = "INSERT INTO gpkg_contents (table_name, data_type, identifier, description, last_change, min_x, min_y, " +
        "max_x, max_y, srs_id) VALUES (?, 'features', ?, ?, ?, ?, ?, ?, ?, ?)"

    /** Parámetros: tabla, columna y `srs_id`; `z = 2` (opcional) y `m = 0` (prohibida). */
    const val INSERT_GEOMETRY_COLUMNS = "INSERT INTO gpkg_geometry_columns (table_name, column_name, geometry_type_name, srs_id, z, m) " +
        "VALUES (?, ?, 'POINT', ?, 2, 0)"

    /**
     * La capa de puntos: `fid`, la geometría y una columna con el encabezado del CSV y su tipo por cada atributo (RF-4), seguidas de una
     * columna tipada por cada atributo de los códigos de [codeAttributes] (S024/RF-21).
     */
    fun createPointsTable(codeAttributes: List<AttributeColumn> = emptyList()): String {
        val fixed = columns.map { "${quote(it.header)} ${GpkgPointAttributes.typeOf(it).sql}" }
        val names = attributeNames(codeAttributes)
        val dynamic = codeAttributes.mapIndexed { i, a -> "${quote(names[i])} ${GpkgPointAttributes.typeOf(a).sql}" }
        return "CREATE TABLE ${quote(POINTS_TABLE)} (fid INTEGER PRIMARY KEY AUTOINCREMENT, $GEOMETRY_COLUMN POINT, " +
            (fixed + dynamic).joinToString(", ") + ")"
    }

    /** La inserción de un punto: la geometría, los atributos en el orden de [columns] y los de [codeAttributes] (S024/RF-21). */
    fun insertPoint(codeAttributes: List<AttributeColumn> = emptyList()): String {
        val names = (columns.map { it.header } + attributeNames(codeAttributes)).joinToString(", ") { quote(it) }
        val markers = List(columns.size + codeAttributes.size + 1) { "?" }.joinToString(", ")
        return "INSERT INTO ${quote(POINTS_TABLE)} ($GEOMETRY_COLUMN, $names) VALUES ($markers)"
    }

    /**
     * Los nombres de las columnas de [codeAttributes], distintos entre sí y de las demás de la capa (SQLite no distingue mayúsculas): el
     * que choca con otra recibe « (atributo)» y, si aún choca, un número (S024/RF-21).
     */
    fun attributeNames(codeAttributes: List<AttributeColumn>): List<String> {
        val taken = (columns.map { it.header } + listOf("fid", GEOMETRY_COLUMN)).mapTo(HashSet()) { it.lowercase(Locale.ROOT) }
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
