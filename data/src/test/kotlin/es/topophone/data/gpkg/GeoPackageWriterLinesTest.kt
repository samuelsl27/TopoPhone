package es.topophone.data.gpkg

import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.core.model.codes.AttributeType
import es.topophone.core.model.codes.CodeGeometry
import es.topophone.core.model.codes.CodeSymbol
import es.topophone.core.model.codes.PointAttribute
import es.topophone.core.model.codes.PointCode
import es.topophone.core.model.line.LineVertex
import es.topophone.core.model.line.ProjectLine
import es.topophone.core.model.point.GeodeticPosition
import es.topophone.core.model.point.Point
import es.topophone.core.model.point.PointCoordinates
import es.topophone.core.model.point.PointOrigin
import es.topophone.core.model.project.AngularUnit
import es.topophone.core.model.project.CorrectionsDatum
import es.topophone.core.model.project.LinearUnit
import es.topophone.core.model.project.Project
import es.topophone.core.model.project.ProjectCrs
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith

/** Las líneas y los polígonos del proyecto como capas del GeoPackage exportado (S025/RF-26). */
@RunWith(AndroidJUnit4::class)
class GeoPackageWriterLinesTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val project = Project(
        id = "p1",
        name = "Obra",
        author = "",
        description = "",
        crs = ProjectCrs.ETRS89_UTM30,
        linearUnit = LinearUnit.METRE,
        angularUnit = AngularUnit.DMS,
        correctionsDatum = CorrectionsDatum.ETRS89,
        folderId = null,
        createdAtMs = 0,
        modifiedAtMs = 0,
        openedAtMs = null,
        deletedAtMs = null,
    )

    private fun point(name: String, easting: Double, northing: Double, height: Double? = 650.0) = Point(
        id = "id-$name",
        projectId = "p1",
        name = name,
        description = "",
        origin = PointOrigin.MANUAL,
        coordinates = PointCoordinates(GeodeticPosition(40.4168, -3.7036, null), easting, northing, height),
        measurement = null,
        typed = null,
        edited = false,
        hidden = false,
        copyOf = null,
        createdAtMs = 0,
        deletedAtMs = null,
    )

    private val points = listOf(
        point("1", 440_000.0, 4_474_000.0),
        point("2", 440_010.0, 4_474_000.0),
        point("3", 440_010.0, 4_474_010.0),
        point("4", 440_000.0, 4_474_010.0, height = null),
    )
    private val bord = PointCode(
        "BORD",
        "Bordillo",
        0,
        CodeSymbol.CIRCLE,
        listOf(
            PointAttribute("Alto", AttributeType.NUMBER, "0.123456789012345"),
            PointAttribute("Material", AttributeType.TEXT, "Granito"),
        ),
    )

    private fun line(id: String, name: String, closed: Boolean, vararg pointIds: String, code: PointCode? = bord) = ProjectLine(
        id = id,
        projectId = "p1",
        name = name,
        code = code,
        geometry = if (closed) CodeGeometry.POLYGON else CodeGeometry.LINE,
        closed = closed,
        hidden = false,
        active = false,
        vertices = pointIds.map { LineVertex("id-$it") },
        createdAtMs = 0,
    )

    private val target: File by lazy { File(folder.root, "salida.gpkg") }
    private val writer = GeoPackageWriter(clock = { 1_792_346_639_419 })

    private fun write(lines: List<ProjectLine>, geographic: Boolean = false) = writer.write(
        points,
        project,
        if (geographic) GpkgExportCrs.GEOGRAPHIC else GpkgExportCrs.PROJECT,
        target,
        extraLayers = listOf(GpkgLineLayers(lines, points, geographic, clock = { 1_792_346_639_419 })),
    )

    private fun <T> query(sql: String, read: (android.database.Cursor) -> T): List<T> =
        SQLiteDatabase.openDatabase(target.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            db.rawQuery(sql, null).use { c -> buildList { while (c.moveToNext()) add(read(c)) } }
        }

    @Test
    fun `S025 RF-26 las lineas abiertas van a la capa lines y las cerradas a polygons`() {
        write(listOf(line("l1", "BORD_1", false, "1", "2", "3"), line("l2", "PARC_1", true, "1", "2", "3", "4"))).shouldNotBeNull()

        query("SELECT table_name, data_type FROM gpkg_contents ORDER BY table_name") { it.getString(0) to it.getString(1) } shouldBe
            listOf("lines" to "features", "points" to "features", "polygons" to "features")
        query("SELECT Name, Code, Description, Vertices FROM lines") {
            listOf(it.getString(0), it.getString(1), it.getString(2), it.getInt(3))
        }
            .single() shouldBe listOf("BORD_1", "BORD", "Bordillo", 3)
        query("SELECT Name, Vertices FROM polygons") { it.getString(0) to it.getInt(1) }.single() shouldBe ("PARC_1" to 4)
        query("SELECT table_name, geometry_type_name FROM gpkg_geometry_columns ORDER BY table_name") {
            it.getString(0) to it.getString(1)
        } shouldBe
            listOf("lines" to "LINESTRING", "points" to "POINT", "polygons" to "POLYGON")
    }

    @Test
    fun `S025 RF-26 los atributos de los codigos son columnas tipadas y sin redondear`() {
        write(listOf(line("l1", "BORD_1", false, "1", "2")))

        val types = query("PRAGMA table_info(lines)") { it.getString(1) to it.getString(2) }.toMap()
        types["Alto"] shouldBe "REAL"
        types["Material"] shouldBe "TEXT"
        query("SELECT Alto, Material FROM lines") { it.getDouble(0) to it.getString(1) }.single() shouldBe (0.123456789012345 to "Granito")
    }

    @Test
    fun `S025 RF-26 la geometria lleva las coordenadas de los puntos en el sistema de la capa`() {
        write(listOf(line("l1", "BORD_1", false, "1", "2")))

        val blob = query("SELECT geom FROM lines") { it.getBlob(0) }.single()
        val buffer = ByteBuffer.wrap(blob, 8, blob.size - 8).order(ByteOrder.LITTLE_ENDIAN)
        buffer.get()
        buffer.getInt() shouldBe 1002
        buffer.getInt() shouldBe 2
        listOf(buffer.getDouble(), buffer.getDouble(), buffer.getDouble()) shouldBe listOf(440_000.0, 4_474_000.0, 650.0)
    }

    @Test
    fun `S025 RF-26 en geograficas los vertices van en longitud y latitud`() {
        write(listOf(line("l1", "BORD_1", false, "1", "2")), geographic = true)

        val blob = query("SELECT geom FROM lines") { it.getBlob(0) }.single()
        val buffer = ByteBuffer.wrap(blob, 8, blob.size - 8).order(ByteOrder.LITTLE_ENDIAN)
        buffer.get()
        buffer.getInt()
        buffer.getInt()
        buffer.getDouble() shouldBe -3.7036
        buffer.getDouble() shouldBe 40.4168
    }

    @Test
    fun `S025 RF-26 un vertice sin altura deja la geometria en dos dimensiones`() {
        write(listOf(line("l2", "PARC_1", true, "1", "2", "3", "4")))

        val blob = query("SELECT geom FROM polygons") { it.getBlob(0) }.single()
        ByteBuffer.wrap(blob, 8, blob.size - 8).order(ByteOrder.LITTLE_ENDIAN).also { it.get() }.getInt() shouldBe 3
    }

    @Test
    fun `S025 RF-26 sin lineas el fichero no gana capas`() {
        write(emptyList()).shouldNotBeNull()

        query("SELECT table_name FROM gpkg_contents") { it.getString(0) } shouldBe listOf("points")
    }

    @Test
    fun `S025 RF-26 una linea con menos vertices de los que da su forma no se escribe`() {
        write(listOf(line("l1", "CORTA", false, "1"), line("l2", "TRIANGULO", true, "1", "2")))

        query("SELECT table_name FROM gpkg_contents") { it.getString(0) } shouldBe listOf("points")
    }

    @Test
    fun `S025 RF-26 el recuadro de la capa es el de sus vertices`() {
        write(listOf(line("l1", "BORD_1", false, "1", "3")))

        query("SELECT min_x, min_y, max_x, max_y FROM gpkg_contents WHERE table_name = 'lines'") {
            listOf(it.getDouble(0), it.getDouble(1), it.getDouble(2), it.getDouble(3))
        }.single() shouldBe listOf(440_000.0, 4_474_000.0, 440_010.0, 4_474_010.0)
    }
}
