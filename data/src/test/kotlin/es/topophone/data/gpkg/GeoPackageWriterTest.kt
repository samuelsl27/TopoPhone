package es.topophone.data.gpkg

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.core.formats.csv.BaseProjection
import es.topophone.core.formats.gpkg.GpkgCrs
import es.topophone.core.formats.gpkg.GpkgGeometry
import es.topophone.core.formats.gpkg.GpkgGeometryReader
import es.topophone.core.formats.gpkg.GpkgSrsDefinitions
import es.topophone.core.model.point.GeodeticPosition
import es.topophone.core.model.point.Measurement
import es.topophone.core.model.point.MeasurementMode
import es.topophone.core.model.point.Point
import es.topophone.core.model.point.PointCoordinates
import es.topophone.core.model.point.PointOrigin
import es.topophone.core.model.point.PointQuality
import es.topophone.core.model.project.AngularUnit
import es.topophone.core.model.project.CorrectionsDatum
import es.topophone.core.model.project.HeightSystem
import es.topophone.core.model.project.LinearUnit
import es.topophone.core.model.project.Project
import es.topophone.core.model.project.ProjectCrs
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.io.ByteArrayInputStream
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith

/** Escribir los puntos de un proyecto a un GeoPackage y leerlos otra vez con otro lector (S053/RF-1 a RF-8, RF-11 a RF-13). */
@RunWith(AndroidJUnit4::class)
class GeoPackageWriterTest {

    @get:Rule
    val folder = TemporaryFolder()

    private fun project(crs: ProjectCrs = ProjectCrs.ETRS89_UTM30) = Project(
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
        heightSystem = if (crs.heightSystems.contains(HeightSystem.ORTHOMETRIC)) HeightSystem.ORTHOMETRIC else HeightSystem.ELLIPSOIDAL,
    )

    private val measured = Point.measured(
        id = "id-4",
        projectId = "p1",
        name = "4",
        description = "Pozo, \"nuevo\"",
        measurement = Measurement(
            antenna = GeodeticPosition(40.416815094, -3.703533016, 701.357),
            poleHeightM = 1.8,
            quality = PointQuality("FIX", 24, 0.6, 1.0, 0.0138, 0.021, MeasurementMode.INSTANT, 1, 0, fromReplay = true),
            rawNmea = "\$GNGGA,1*00",
            outOfTolerance = emptySet(),
            measuredFromMs = 1_792_346_614_419,
            measuredToMs = 1_792_346_639_419,
            coordinates = PointCoordinates(
                GeodeticPosition(40.416815094123456, -3.703533016654321, 699.423123456),
                440_313.1221195372,
                4_474_258.876880059,
                648.3113666754832,
            ),
        ),
        createdAtMs = 1_792_346_639_419,
    )

    private val manual = Point(
        id = "id-E1",
        projectId = "p1",
        name = "E1",
        description = "",
        origin = PointOrigin.MANUAL,
        coordinates = PointCoordinates(GeodeticPosition(40.4168, -3.7036, null), 440_300.0, 4_474_250.0, null),
        measurement = null,
        typed = null,
        edited = false,
        hidden = true,
        copyOf = null,
        createdAtMs = 1_792_346_700_000,
        deletedAtMs = null,
    )

    private fun outside() = manual.copy(
        id = "id-E2",
        name = "E2",
        coordinates = PointCoordinates(GeodeticPosition(60.0, 30.0, null), null, null, null),
    )

    private val writer = GeoPackageWriter(clock = { 1_792_346_639_419 })
    private fun target() = File(folder.root, "salida.gpkg")

    private fun write(
        points: List<Point>,
        project: Project = project(),
        crs: GpkgExportCrs = GpkgExportCrs.PROJECT,
        into: File = target(),
    ) = writer.write(points, project, crs, into)

    private fun <T> query(file: File, sql: String, read: (Cursor) -> T): List<T> {
        val db = SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY)
        return db.use {
            it.rawQuery(sql, null).use { cursor ->
                val out = ArrayList<T>()
                while (cursor.moveToNext()) out += read(cursor)
                out
            }
        }
    }

    private fun scalar(file: File, sql: String): String? = query(file, sql) { it.getString(0) }.singleOrNull()

    private fun real(file: File, sql: String): Double? = query(file, sql) { it.getDouble(0) }.singleOrNull()

    @Test
    fun `S053 RF-1 los puntos se escriben en una capa y el fichero se declara GeoPackage`() {
        val summary = write(listOf(measured, manual)).shouldNotBeNull()

        summary.points shouldBe 2
        summary.srsId shouldBe 25830
        summary.customDefinition shouldBe false
        scalar(target(), "PRAGMA application_id") shouldBe "1196444487"
        scalar(target(), "PRAGMA user_version") shouldBe "10300"
        query(target(), "SELECT name FROM sqlite_master WHERE type = 'table' ORDER BY name") { it.getString(0) }.also {
            it.containsAll(listOf("gpkg_contents", "gpkg_geometry_columns", "gpkg_spatial_ref_sys", "points")) shouldBe true
        }
        scalar(target(), "SELECT COUNT(*) FROM points") shouldBe "2"
    }

    @Test
    fun `S053 RF-1 los puntos ocultos se exportan y salen en el orden dado`() {
        write(listOf(measured, manual))

        query(target(), "SELECT Name FROM points ORDER BY fid") { it.getString(0) } shouldBe listOf("4", "E1")
        scalar(target(), """SELECT "Name" FROM points WHERE fid = 2""") shouldBe "E1" // E1 está oculto en el proyecto
    }

    @Test
    fun `S053 RF-2 solo se escriben los puntos que se dan`() {
        write(listOf(manual)).shouldNotBeNull().points shouldBe 1

        query(target(), "SELECT Name FROM points") { it.getString(0) } shouldBe listOf("E1")
    }

    @Test
    fun `S053 RF-3 sin lineas ni poligonos solo hay una capa y una fila de contenidos`() {
        write(listOf(measured))

        query(target(), "SELECT table_name FROM gpkg_contents") { it.getString(0) } shouldBe listOf("points")
        query(target(), "SELECT table_name FROM gpkg_geometry_columns") { it.getString(0) } shouldBe listOf("points")
    }

    @Test
    fun `S053 RF-3 una capa de mas se escribe en la misma transaccion y si falla no queda fichero`() {
        val extra = GpkgExtraLayer { db, _ -> db.execSQL("CREATE TABLE lines (fid INTEGER PRIMARY KEY)") }
        writer.write(listOf(measured), project(), GpkgExportCrs.PROJECT, target(), extraLayers = listOf(extra)).shouldNotBeNull()
        query(target(), "SELECT name FROM sqlite_master WHERE name = 'lines'") { it.getString(0) } shouldBe listOf("lines")

        val failing = GpkgExtraLayer { _, _ -> error("fallo de la capa") }
        val other = File(folder.root, "otra.gpkg")
        shouldThrow<GpkgExportException> {
            writer.write(listOf(measured), project(), GpkgExportCrs.PROJECT, other, extraLayers = listOf(failing))
        }
        other.exists() shouldBe false
    }

    @Test
    fun `S053 RF-4 los atributos llevan su tipo y lo que el punto no tiene queda vacio`() {
        write(listOf(measured, manual))

        query(target(), "SELECT typeof(Easting), typeof(Satellites), typeof(Replay), typeof(Name), typeof(Elevation) FROM points") { c ->
            (0 until 5).map { c.getString(it) }
        } shouldBe listOf(
            listOf("real", "integer", "integer", "text", "real"),
            listOf("real", "null", "null", "text", "null"),
        )
        scalar(target(), "SELECT Replay FROM points WHERE fid = 1") shouldBe "1"
        scalar(target(), "SELECT Description FROM points WHERE fid = 1") shouldBe "Pozo, \"nuevo\""
        scalar(target(), "SELECT \"Out of tolerance\" FROM points WHERE fid = 1") shouldBe ""
    }

    @Test
    fun `S053 RF-4 las columnas declaran el tipo de cada atributo en la tabla`() {
        write(listOf(measured))

        val types = query(target(), "PRAGMA table_info(points)") { it.getString(1) to it.getString(2) }.toMap()

        types["Name"] shouldBe "TEXT"
        types["Satellites"] shouldBe "INTEGER"
        types["Easting"] shouldBe "REAL"
        types["Replay"] shouldBe "BOOLEAN"
        types["geom"] shouldBe "POINT"
    }

    private fun geometryOf(fid: Int): GpkgGeometry =
        GpkgGeometryReader.read(query(target(), "SELECT geom FROM points WHERE fid = $fid") { it.getBlob(0) }.single())

    @Test
    fun `S053 RF-5 las coordenadas y la altura del proyecto se escriben sin redondear`() {
        write(listOf(measured, manual))

        val point = geometryOf(1).shouldBeInstanceOf<GpkgGeometry.Parts>().parts.single()
        point.x.single() shouldBe 440_313.1221195372
        point.y.single() shouldBe 4_474_258.876880059
        point.z.shouldNotBeNull().single() shouldBe 648.3113666754832
        // el punto sin altura no lleva Z
        geometryOf(2).shouldBeInstanceOf<GpkgGeometry.Parts>().parts.single().z.shouldBeNull()
    }

    @Test
    fun `S053 RF-6 la capa se declara en el sistema del proyecto con su codigo y su definicion`() {
        write(listOf(measured, manual))

        query(target(), "SELECT srs_id FROM gpkg_contents") { it.getInt(0) } shouldBe listOf(25830)
        query(target(), "SELECT srs_id, z, m FROM gpkg_geometry_columns") { Triple(it.getInt(0), it.getInt(1), it.getInt(2)) } shouldBe
            listOf(Triple(25830, 2, 0))
        query(target(), "SELECT srs_id FROM gpkg_spatial_ref_sys ORDER BY srs_id") { it.getInt(0) } shouldBe listOf(-1, 0, 4326, 25830)
        scalar(target(), "SELECT organization FROM gpkg_spatial_ref_sys WHERE srs_id = 25830") shouldBe "EPSG"
        scalar(target(), "SELECT definition FROM gpkg_spatial_ref_sys WHERE srs_id = 25830") shouldBe
            GpkgSrsDefinitions.of(ProjectCrs.ETRS89_UTM30).definition
        query(target(), "SELECT min_x, min_y, max_x, max_y FROM gpkg_contents") { c -> (0 until 4).map { c.getDouble(it) } } shouldBe
            listOf(listOf(440_300.0, 4_474_250.0, 440_313.1221195372, 4_474_258.876880059))
    }

    @Test
    fun `S053 RF-6 el lector de GeoPackage de S048 abre el fichero y reconoce capa y sistema`() {
        write(listOf(measured, manual))

        val bytes = target().readBytes()
        val opened = runBlocking { GeoPackageReader(folder.newFolder("cache")).open(bytes.size.toLong()) { ByteArrayInputStream(bytes) } }

        opened.shouldBeInstanceOf<GpkgOpenResult.Opened>().session.use { session ->
            val layer = session.layers.single()
            layer.table shouldBe "points"
            layer.featureCount shouldBe 2
            layer.srsId shouldBe 25830
            session.crs(layer).shouldBeInstanceOf<GpkgCrs.Supported>().epsg shouldBe 25830
        }
    }

    @Test
    fun `S053 RF-7 en geograficas x es la longitud, y la latitud y el sistema es el del datum`() {
        val summary = write(listOf(measured), crs = GpkgExportCrs.GEOGRAPHIC).shouldNotBeNull()

        summary.srsId shouldBe 4258
        val point = geometryOf(1).shouldBeInstanceOf<GpkgGeometry.Parts>().parts.single()
        point.x.single() shouldBe -3.703533016654321
        point.y.single() shouldBe 40.416815094123456
        point.z.shouldNotBeNull().single() shouldBe 648.3113666754832 // la altura del proyecto, también en geográficas
        // los atributos de los dos sistemas van siempre
        real(target(), "SELECT Easting FROM points") shouldBe 440_313.1221195372
        scalar(target(), "SELECT srs_id FROM gpkg_contents") shouldBe "4258"
    }

    @Test
    fun `S053 RF-7 un proyecto ED50 declara ETRS89 y uno de Canarias REGCAN95`() {
        write(listOf(measured), project(ProjectCrs.ED50_UTM30), GpkgExportCrs.GEOGRAPHIC).shouldNotBeNull().srsId shouldBe 4258
        write(listOf(measured), project(ProjectCrs.REGCAN95_UTM28), GpkgExportCrs.GEOGRAPHIC).shouldNotBeNull().srsId shouldBe 4081
        write(listOf(measured), project(ProjectCrs.REGCAN95_UTM28), GpkgExportCrs.PROJECT).shouldNotBeNull().srsId shouldBe 4083
    }

    @Test
    fun `S053 RF-6 un proyecto en geograficas se escribe en sus geograficas sea cual sea la eleccion`() {
        val geographic = project(ProjectCrs.ETRS89_GEOGRAPHIC)

        write(listOf(measured), geographic, GpkgExportCrs.PROJECT).shouldNotBeNull().srsId shouldBe 4258
        val point = geometryOf(1).shouldBeInstanceOf<GpkgGeometry.Parts>().parts.single()
        point.x.single() shouldBe -3.703533016654321
        point.y.single() shouldBe 40.416815094123456
    }

    @Test
    fun `S053 RF-8 un sistema sin codigo reconocido lleva su definicion completa y el resumen lo dice`() {
        val custom = GpkgSrsDefinitions.custom("Obra local", """PROJCS["Obra local",GEOGCS["ETRS89"],UNIT["metre",1]]""")

        val summary = writer.write(listOf(measured), project(), GpkgExportCrs.PROJECT, target(), srs = custom).shouldNotBeNull()

        summary.customDefinition shouldBe true
        summary.srsId shouldBe GpkgSrsDefinitions.CUSTOM_SRS_ID
        scalar(target(), "SELECT organization FROM gpkg_spatial_ref_sys WHERE srs_id = 100000") shouldBe "NONE"
        scalar(target(), "SELECT definition FROM gpkg_spatial_ref_sys WHERE srs_id = 100000") shouldBe custom.definition
        scalar(target(), "SELECT srs_id FROM gpkg_contents") shouldBe "100000"
    }

    @Test
    fun `S053 RF-11 sin puntos no se crea el fichero`() {
        write(emptyList()).shouldBeNull()

        target().exists() shouldBe false
    }

    @Test
    fun `S053 RF-12 si no se puede escribir se dice y no queda un fichero a medias`() {
        val impossible = File(folder.root, "no/existe/salida.gpkg")

        shouldThrow<GpkgExportException> { write(listOf(measured), into = impossible) }

        impossible.exists() shouldBe false
        File(folder.root, "no").exists() shouldBe false
    }

    @Test
    fun `S053 RF-12 un fichero que ya existia se sustituye entero`() {
        target().writeText("basura")

        write(listOf(measured, manual)).shouldNotBeNull().points shouldBe 2

        scalar(target(), "SELECT COUNT(*) FROM points") shouldBe "2"
    }

    @Test
    fun `S053 RF-8 un punto sin Este y Norte va con la geometria vacia y todos sus atributos`() {
        val summary = write(listOf(measured, outside())).shouldNotBeNull()

        summary.points shouldBe 2
        summary.withoutGeometry shouldBe 1
        geometryOf(2) shouldBe GpkgGeometry.Empty
        scalar(target(), "SELECT Name FROM points WHERE fid = 2") shouldBe "E2"
        real(target(), "SELECT Latitude FROM points WHERE fid = 2") shouldBe 60.0
        // la extensión sale solo de los puntos con geometría
        real(target(), "SELECT min_x FROM gpkg_contents") shouldBe 440_313.1221195372
    }

    @Test
    fun `S053 RF-7 en geograficas un punto sin Este y Norte tiene geometria porque la latitud y la longitud siempre estan`() {
        write(listOf(measured, outside()), crs = GpkgExportCrs.GEOGRAPHIC).shouldNotBeNull().withoutGeometry shouldBe 0
    }

    @Test
    fun `S053 RF-13 exportar no modifica los puntos`() {
        val points = listOf(measured, manual)
        val before = points.toList()

        write(points)

        points shouldBe before
        points[0] shouldBe measured
    }

    @Test
    fun `S053 RF-4 la base proyectada la calcula quien exporta y se escribe en su columna`() {
        val base = es.topophone.core.model.point.PointBase(
            GeodeticPosition(43.5578666032, -5.9130704113, 78.4338),
            es.topophone.core.model.point.BaseSource.CORRECTIONS,
            6,
            0.0,
            382_123.4567,
        )
        val withBase = measured.copy(measurement = measured.measurement!!.copy(base = base))

        writer.write(listOf(withBase), project(), GpkgExportCrs.PROJECT, target(), baseProjection = {
            BaseProjection(266_134.9877, 4_826_201.1235, 26.0123)
        })

        real(target(), "SELECT \"Base easting\" FROM points") shouldBe 266_134.9877
        real(target(), "SELECT \"Base latitude\" FROM points") shouldBe 43.5578666032
    }
}
