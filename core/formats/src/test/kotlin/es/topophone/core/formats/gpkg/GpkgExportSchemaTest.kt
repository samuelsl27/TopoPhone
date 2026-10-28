package es.topophone.core.formats.gpkg

import es.topophone.core.formats.csv.ExportColumn
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** El esquema del fichero exportado: las tablas de GeoPackage y la capa de puntos con una columna por atributo (S053/RF-4, RF-6). */
class GpkgExportSchemaTest {

    @Test
    fun `S053 RF-6 el fichero se declara GeoPackage 1_3 con el application_id GPKG`() {
        GpkgExportSchema.APPLICATION_ID shouldBe 0x47504B47
        GpkgExportSchema.APPLICATION_ID shouldBe "GPKG".fold(0) { acc, c -> acc * 256 + c.code }
        GpkgExportSchema.USER_VERSION shouldBe 10300
    }

    @Test
    fun `S053 RF-6 las tablas obligatorias de sistemas contenidos y columnas de geometria se crean en ese orden`() {
        val tables = GpkgExportSchema.createTables

        tables.size shouldBe 3
        tables[0].startsWith("CREATE TABLE gpkg_spatial_ref_sys") shouldBe true
        tables[1].startsWith("CREATE TABLE gpkg_contents") shouldBe true
        tables[2].startsWith("CREATE TABLE gpkg_geometry_columns") shouldBe true
        tables[0].contains("srs_id INTEGER PRIMARY KEY") shouldBe true
        tables[1].contains("table_name TEXT NOT NULL PRIMARY KEY") shouldBe true
        tables[2].contains("z TINYINT NOT NULL") shouldBe true
    }

    @Test
    fun `S053 RF-4 la capa de puntos tiene fid, la geometria y una columna por cada columna de Todas las columnas con su tipo`() {
        val ddl = GpkgExportSchema.createPointsTable()

        ddl.startsWith("""CREATE TABLE "points" (fid INTEGER PRIMARY KEY AUTOINCREMENT, geom POINT, """) shouldBe true
        for (column in ExportColumn.entries) {
            ddl.contains(""""${column.header}" ${GpkgPointAttributes.typeOf(column).sql}""") shouldBe true
        }
        // el orden de las columnas es el de Todas las columnas
        val positions = ExportColumn.entries.map { ddl.indexOf("\"${it.header}\" ") }
        positions shouldBe positions.sorted()
    }

    @Test
    fun `S053 RF-4 el nombre de una columna con espacios va entre comillas dobles`() {
        GpkgExportSchema.createPointsTable().contains(""""Ellipsoidal height" REAL""") shouldBe true
        GpkgExportSchema.createPointsTable().contains(""""Tilt mode verified" BOOLEAN""") shouldBe true
    }

    @Test
    fun `S053 RF-4 la sentencia de insercion lleva un marcador para la geometria y uno por atributo en el mismo orden`() {
        val sql = GpkgExportSchema.insertPoint()

        sql.startsWith("""INSERT INTO "points" (geom, "Name", "Description", """) shouldBe true
        sql.count { it == '?' } shouldBe 1 + ExportColumn.entries.size
        sql.endsWith("?)") shouldBe true
    }

    @Test
    fun `S053 RF-6 las filas de contenidos y de columnas de geometria describen una capa de puntos con Z opcional`() {
        GpkgExportSchema.INSERT_CONTENTS.contains("'features'") shouldBe true
        GpkgExportSchema.INSERT_GEOMETRY_COLUMNS.contains("'POINT'") shouldBe true
        GpkgExportSchema.INSERT_GEOMETRY_COLUMNS.endsWith(", 2, 0)") shouldBe true // z = 2: unos puntos tienen altura y otros no
        GpkgExportSchema.POINTS_TABLE shouldBe "points"
        GpkgExportSchema.GEOMETRY_COLUMN shouldBe "geom"
    }
}
