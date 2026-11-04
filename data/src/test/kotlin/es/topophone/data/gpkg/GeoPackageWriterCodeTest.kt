package es.topophone.data.gpkg

import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.core.formats.csv.AttributeColumns
import es.topophone.core.model.codes.AttributeType
import es.topophone.core.model.codes.CodeSymbol
import es.topophone.core.model.codes.PointAttribute
import es.topophone.core.model.codes.PointCode
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
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith

/** El codigo y los atributos de los puntos como columnas tipadas del GeoPackage exportado (S024/RF-21). */
@RunWith(AndroidJUnit4::class)
class GeoPackageWriterCodeTest {

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

    private fun point(name: String, code: PointCode?) = Point(
        id = "id-$name",
        projectId = "p1",
        name = name,
        description = "",
        origin = PointOrigin.MANUAL,
        coordinates = PointCoordinates(GeodeticPosition(40.4168, -3.7036, null), 440_300.0, 4_474_250.0, null),
        measurement = null,
        typed = null,
        edited = false,
        hidden = false,
        copyOf = null,
        createdAtMs = 0,
        deletedAtMs = null,
        code = code,
    )

    private val arq = PointCode(
        "ARQ",
        "Arqueta",
        0x1E88E5,
        CodeSymbol.CIRCLE,
        listOf(
            PointAttribute("Material", AttributeType.LIST, "PVC"),
            PointAttribute("Diametro", AttributeType.NUMBER, "0.123456789012345"),
            PointAttribute("Unidades", AttributeType.INTEGER, "3"),
            PointAttribute("Nota", AttributeType.TEXT, ""),
        ),
    )
    private val points = listOf(point("1", arq), point("2", null))
    private val target: File by lazy { File(folder.root, "salida.gpkg") }
    private val writer = GeoPackageWriter(clock = { 1_792_346_639_419 })

    private fun write() = writer.write(
        points,
        project,
        GpkgExportCrs.PROJECT,
        target,
        attributes = AttributeColumns.of(points),
    )

    private fun <T> query(sql: String, read: (android.database.Cursor) -> T): List<T> =
        SQLiteDatabase.openDatabase(target.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            db.rawQuery(sql, null).use { c -> buildList { while (c.moveToNext()) add(read(c)) } }
        }

    @Test
    fun `S024 RF-21 la capa lleva el codigo y una columna tipada por atributo`() {
        write().shouldNotBeNull()

        val columns = query("PRAGMA table_info(points)") { it.getString(1) to it.getString(2) }.toMap()
        columns["Code"] shouldBe "TEXT"
        columns["Material"] shouldBe "TEXT"
        columns["Diametro"] shouldBe "REAL"
        columns["Unidades"] shouldBe "INTEGER"
        columns["Nota"] shouldBe "TEXT"
    }

    @Test
    fun `S024 RF-21 los valores se guardan del tipo de su columna y sin redondear`() {
        write()

        val rows =
            query(
                "SELECT Name, Code, Material, CAST(Diametro AS TEXT), Unidades, Nota, typeof(Diametro), typeof(Unidades) " +
                    "FROM points ORDER BY fid",
            ) {
                listOf(
                    it.getString(0),
                    it.getString(1),
                    it.getString(2),
                    it.getString(3),
                    it.getString(4),
                    it.getString(5),
                    it.getString(6),
                    it.getString(7),
                )
            }
        rows[0] shouldBe listOf("1", "ARQ", "PVC", "0.123456789012345", "3", null, "real", "integer")
        rows[1] shouldBe listOf("2", null, null, null, null, null, "null", "null")
        query("SELECT Diametro FROM points WHERE Name = '1'") { it.getDouble(0) }.single() shouldBe 0.123456789012345
    }

    @Test
    fun `S024 RF-21 un atributo cuyo nombre choca con una columna fija recibe un nombre distinto`() {
        val clashing =
            listOf(point("1", PointCode("X", "", 0, CodeSymbol.CIRCLE, listOf(PointAttribute("name", AttributeType.TEXT, "otro")))))

        writer.write(clashing, project, GpkgExportCrs.PROJECT, target, attributes = AttributeColumns.of(clashing)).shouldNotBeNull()

        query("SELECT Name FROM points") { it.getString(0) }.single() shouldBe "1"
        query("SELECT \"name (atributo)\" FROM points") { it.getString(0) }.single() shouldBe "otro"
    }

    @Test
    fun `S024 RF-21 sin atributos la capa no cambia`() {
        writer.write(points, project, GpkgExportCrs.PROJECT, target).shouldNotBeNull()

        val names = query("PRAGMA table_info(points)") { it.getString(1) }
        names.contains("Material") shouldBe false
        names.contains("Code") shouldBe true
        query("SELECT Code FROM points ORDER BY fid") { it.getString(0) } shouldBe listOf("ARQ", null)
    }
}
