package es.topophone.data.gpkg

import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.core.model.point.GeodeticPosition
import es.topophone.core.model.point.Point
import es.topophone.core.model.point.PointCoordinates
import es.topophone.core.model.point.PointOrigin
import es.topophone.core.model.project.AngularUnit
import es.topophone.core.model.project.CorrectionsDatum
import es.topophone.core.model.project.HeightSystem
import es.topophone.core.model.project.LinearUnit
import es.topophone.core.model.project.Project
import es.topophone.core.model.project.ProjectCrs
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import java.io.File
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith

/**
 * Los GeoPackage dorados de `fixtures/formatos/gpkg-exportado/`, escritos con los puntos de `esperado-puntos.csv` (calculados con pyproj):
 * lo que escribe `GeoPackageWriter` es lo que hay en cada fichero, que el test de `tools` lee con `sqlite3` y `pyproj` (S053/RF-5 a RF-7;
 * constitución nº 2). Con `TOPOPHONE_REGENERAR_DORADOS=1` se reescriben. Los ficheros no son iguales byte a byte entre versiones de SQLite: se
 * compara su contenido.
 */
@RunWith(AndroidJUnit4::class)
class GeoPackageWriterGoldenTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val root: File = generateSequence(File("").absoluteFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").exists() && File(it, "fixtures").isDirectory }
    private val golden = File(root, "fixtures/formatos/gpkg-exportado")

    private class Sample(val epsg: Int, val name: String, val lat: Double, val lon: Double, val east: Double, val north: Double)

    private fun samples(): List<Sample> = File(golden, "esperado-puntos.csv").readLines().drop(1).filter { it.isNotBlank() }.map {
        val c = it.split(",")
        Sample(c[0].toInt(), c[1], c[2].toDouble(), c[3].toDouble(), c[4].toDouble(), c[5].toDouble())
    }

    private fun project(crs: ProjectCrs) = Project(
        id = "p1",
        name = "Obra",
        author = "",
        description = "",
        crs = crs,
        linearUnit = LinearUnit.METRE,
        angularUnit = AngularUnit.DMS,
        correctionsDatum = if (crs.datum.name == "REGCAN95") CorrectionsDatum.REGCAN95 else CorrectionsDatum.ETRS89,
        folderId = null,
        createdAtMs = 0,
        modifiedAtMs = 0,
        openedAtMs = null,
        deletedAtMs = null,
        heightSystem = HeightSystem.ELLIPSOIDAL,
    )

    /** Los puntos de un sistema: con altura los pares y sin ella los impares, para tener las dos geometrías. */
    private fun points(epsg: Int): List<Point> = samples().filter { it.epsg == epsg }.mapIndexed { index, s ->
        Point(
            id = "id-${s.name}",
            projectId = "p1",
            name = s.name,
            description = "Punto de ${s.name}",
            origin = PointOrigin.MANUAL,
            coordinates = PointCoordinates(
                GeodeticPosition(s.lat, s.lon, 600.0 + index).takeIf { index % 2 == 0 } ?: GeodeticPosition(s.lat, s.lon, null),
                s.east,
                s.north,
                if (index % 2 == 0) 500.5 + index else null,
            ),
            measurement = null,
            typed = null,
            edited = false,
            hidden = false,
            copyOf = null,
            createdAtMs = 1_792_346_700_000,
            deletedAtMs = null,
        )
    }

    private data class Case(val file: String, val crs: ProjectCrs, val export: GpkgExportCrs)

    private val cases = listOf(
        Case("exportado-25829.gpkg", ProjectCrs.ETRS89_UTM29, GpkgExportCrs.PROJECT),
        Case("exportado-25830.gpkg", ProjectCrs.ETRS89_UTM30, GpkgExportCrs.PROJECT),
        Case("exportado-25831.gpkg", ProjectCrs.ETRS89_UTM31, GpkgExportCrs.PROJECT),
        Case("exportado-4082.gpkg", ProjectCrs.REGCAN95_UTM27, GpkgExportCrs.PROJECT),
        Case("exportado-4083.gpkg", ProjectCrs.REGCAN95_UTM28, GpkgExportCrs.PROJECT),
        Case("exportado-25830-geograficas.gpkg", ProjectCrs.ETRS89_UTM30, GpkgExportCrs.GEOGRAPHIC),
        Case("exportado-4083-geograficas.gpkg", ProjectCrs.REGCAN95_UTM28, GpkgExportCrs.GEOGRAPHIC),
    )

    private fun write(case: Case, into: File) {
        val list = points(case.crs.epsg)
        GeoPackageWriter(clock = { FIXED_TIME_MS }).write(list, project(case.crs), case.export, into).also {
            check(it != null && it.points == list.size)
        }
    }

    /** El contenido de un fichero sin la cabecera de SQLite ni las definiciones WKT, que cambian con PROJ. */
    private fun logical(file: File): List<String> {
        val db = SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY)
        return db.use {
            val out = ArrayList<String>()
            fun dump(label: String, sql: String) {
                it.rawQuery(sql, null).use { cursor ->
                    while (cursor.moveToNext()) {
                        out += label + ":" + (0 until cursor.columnCount).joinToString("|") { i ->
                            when (cursor.getType(i)) {
                                android.database.Cursor.FIELD_TYPE_BLOB -> cursor.getBlob(i).joinToString("") { b -> "%02x".format(b) }
                                android.database.Cursor.FIELD_TYPE_FLOAT -> cursor.getDouble(i).toString()
                                else -> cursor.getString(i).orEmpty()
                            }
                        }
                    }
                }
            }
            dump("app", "PRAGMA application_id")
            dump("ver", "PRAGMA user_version")
            dump(
                "srs",
                "SELECT srs_name, srs_id, organization, organization_coordsys_id, description FROM gpkg_spatial_ref_sys ORDER BY srs_id",
            )
            dump("contents", "SELECT * FROM gpkg_contents")
            dump("geomcols", "SELECT * FROM gpkg_geometry_columns")
            dump("columns", "SELECT name, type FROM pragma_table_info('points')")
            dump("points", "SELECT * FROM points ORDER BY fid")
            out
        }
    }

    @Test
    fun `S053 RF-5 los GeoPackage dorados son lo que escribe el escritor`() {
        val regenerate = System.getenv("TOPOPHONE_REGENERAR_DORADOS") == "1"
        val different = ArrayList<String>()
        for (case in cases) {
            val written = File(folder.root, case.file)
            write(case, written)
            val target = File(golden, case.file)
            if (regenerate) written.copyTo(target, overwrite = true)
            if (logical(written) != logical(target)) different += case.file
        }

        different.shouldBeEmpty()
    }

    @Test
    fun `S053 RF-5 cada dorado tiene los puntos del sistema que dice su nombre`() {
        for (case in cases) {
            val file = File(golden, case.file)
            val db = SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY)
            val count = db.use {
                it.rawQuery("SELECT COUNT(*) FROM points", null).use { c ->
                    c.moveToFirst()
                    c.getInt(0)
                }
            }

            count shouldBe points(case.crs.epsg).size
        }
    }

    private companion object {
        /** La fecha del último cambio de los dorados: fija para que el fichero no cambie cada vez que se escribe. */
        const val FIXED_TIME_MS = 1_792_346_639_419L
    }
}
