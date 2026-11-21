package es.topophone.data.gpkg

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.core.formats.gpkg.GpkgGeometry
import es.topophone.core.formats.gpkg.GpkgGeometryReader
import es.topophone.core.geodesy.GeoPoint
import es.topophone.core.geodesy.catalog.CrsBuildResult
import es.topophone.core.geodesy.catalog.CrsCatalog
import es.topophone.core.geodesy.catalog.build
import es.topophone.core.model.point.GeodeticPosition
import es.topophone.core.model.point.Point
import es.topophone.core.model.point.PointCoordinates
import es.topophone.core.model.point.PointOrigin
import es.topophone.core.model.project.AngularUnit
import es.topophone.core.model.project.CorrectionsDatum
import es.topophone.core.model.project.HeightSystem
import es.topophone.core.model.project.LibraryCrs
import es.topophone.core.model.project.LinearUnit
import es.topophone.core.model.project.Project
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.io.File
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith

/** Exportar a GeoPackage un proyecto en un SC de la biblioteca: lo declara con su código EPSG y su definición y escribe sus coordenadas (S057/RF-22, RF-25). */
@RunWith(AndroidJUnit4::class)
class GpkgExportLibraryTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val lambert = LibraryCrs(
        (CrsCatalog.bundled.build(2154, CorrectionsDatum.ETRS89, GeoPoint(48.0, 2.0)) as CrsBuildResult.Ready).definition,
    )

    private fun project(crs: LibraryCrs = lambert) = Project(
        id = "p1",
        name = "Obra",
        author = "",
        description = "",
        crs = crs,
        linearUnit = LinearUnit.METRE,
        angularUnit = AngularUnit.DMS,
        correctionsDatum = CorrectionsDatum.ETRS89,
        folderId = null,
        createdAtMs = 0,
        modifiedAtMs = 0,
        openedAtMs = null,
        deletedAtMs = null,
        heightSystem = HeightSystem.ELLIPSOIDAL,
    )

    private val point = Point(
        id = "id-1",
        projectId = "p1",
        name = "1",
        description = "",
        origin = PointOrigin.MANUAL,
        coordinates = PointCoordinates(GeodeticPosition(48.1, 2.3, 100.0), 652_000.123456789, 6_862_000.987654321, 100.0),
        measurement = null,
        typed = null,
        edited = false,
        hidden = false,
        copyOf = null,
        createdAtMs = 1,
        deletedAtMs = null,
    )

    private val writer = GeoPackageWriter(clock = { 1L })
    private fun target() = File(folder.root, "salida.gpkg")

    private fun <T> query(sql: String, read: (Cursor) -> T): List<T> {
        val db = SQLiteDatabase.openDatabase(target().path, null, SQLiteDatabase.OPEN_READONLY)
        return db.use {
            it.rawQuery(sql, null).use { cursor ->
                val out = ArrayList<T>()
                while (cursor.moveToNext()) out += read(cursor)
                out
            }
        }
    }

    @Test
    fun `S057 RF-22 las coordenadas del proyecto se declaran con el codigo EPSG del SC y su definicion de pyproj`() {
        val summary = writer.write(listOf(point), project(), GpkgExportCrs.PROJECT, target()).shouldNotBeNull()

        summary.srsId shouldBe 2154
        query("SELECT srs_id FROM gpkg_spatial_ref_sys ORDER BY srs_id") { it.getInt(0) } shouldBe listOf(-1, 0, 2154, 4326)
        query("SELECT organization || ':' || organization_coordsys_id FROM gpkg_spatial_ref_sys WHERE srs_id = 2154") {
            it.getString(0)
        } shouldBe
            listOf("EPSG:2154")
        val definition = query("SELECT definition FROM gpkg_spatial_ref_sys WHERE srs_id = 2154") { it.getString(0) }.single()
        definition.startsWith("PROJCS[\"RGF93") shouldBe true
        definition.endsWith("AUTHORITY[\"EPSG\",\"2154\"]]") shouldBe true
        query("SELECT srs_id FROM gpkg_geometry_columns") { it.getInt(0) } shouldBe listOf(2154)
    }

    @Test
    fun `S057 RF-22 el punto se escribe con el Este y el Norte del proyecto sin redondear`() {
        writer.write(listOf(point), project(), GpkgExportCrs.PROJECT, target())

        val blob = query("SELECT geom FROM points") { it.getBlob(0) }.single()
        val parts = GpkgGeometryReader.read(blob).shouldBeInstanceOf<GpkgGeometry.Parts>().parts
        parts.single().x[0] shouldBe 652_000.123456789
        parts.single().y[0] shouldBe 6_862_000.987654321
    }

    @Test
    fun `S057 RF-22 en geograficas el punto se escribe con longitud y latitud del datum de las correcciones y su SC`() {
        val summary = writer.write(listOf(point), project(), GpkgExportCrs.GEOGRAPHIC, target()).shouldNotBeNull()

        summary.srsId shouldBe 4258
        val blob = query("SELECT geom FROM points") { it.getBlob(0) }.single()
        val part = GpkgGeometryReader.read(blob).shouldBeInstanceOf<GpkgGeometry.Parts>().parts.single()
        part.x[0] shouldBe (2.3 plusOrMinus 1e-12)
        part.y[0] shouldBe (48.1 plusOrMinus 1e-12)
    }
}
