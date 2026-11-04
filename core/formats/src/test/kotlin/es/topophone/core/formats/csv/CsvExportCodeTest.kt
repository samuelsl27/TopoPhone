package es.topophone.core.formats.csv

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
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** El codigo y los atributos en el CSV de "Todas las columnas" (S024/RF-21). */
class CsvExportCodeTest {

    private val project = Project(
        id = "p1",
        name = "Calle",
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
        coordinates = PointCoordinates(GeodeticPosition(40.4, -3.7, null), 440_000.0, 4_472_000.0, null),
        measurement = null,
        typed = null,
        edited = false,
        hidden = false,
        copyOf = null,
        createdAtMs = 0,
        deletedAtMs = null,
        code = code,
    )

    private fun code(name: String, vararg attributes: PointAttribute) =
        PointCode(name, "", 0x1E88E5, CodeSymbol.CIRCLE, attributes.toList())

    private fun csv(points: List<Point>, options: CsvOptions = CsvOptions()): List<List<String>> {
        val out = StringBuilder()
        CsvExport.write(points.asSequence(), project, options, out, attributes = AttributeColumns.of(points))
        val separator = options.separator.char
        return out.toString().trimEnd().split("\r\n").map { it.split(separator) }
    }

    private val arq = code(
        "ARQ",
        PointAttribute("Material", AttributeType.LIST, "PVC"),
        PointAttribute("Diametro", AttributeType.NUMBER, "0.123456789012345"),
    )
    private val poz = code(
        "POZ",
        PointAttribute("Diametro", AttributeType.NUMBER, "1.5"),
        PointAttribute("Profundidad", AttributeType.INTEGER, "3"),
    )

    @Test
    fun `S024 RF-21 todas las columnas llevan el codigo y una columna por atributo de los puntos exportados`() {
        val rows = csv(listOf(point("1", arq), point("2", poz), point("3", null)))

        val header = rows.first()
        val codeIndex = header.indexOf("Code")
        header.drop(codeIndex) shouldBe listOf("Code", "Material", "Diametro", "Profundidad")
        rows[1].drop(codeIndex) shouldBe listOf("ARQ", "PVC", "0.123456789012345", "")
        rows[2].drop(codeIndex) shouldBe listOf("POZ", "", "1.5", "3")
        rows[3].drop(codeIndex) shouldBe listOf("", "", "", "")
    }

    @Test
    fun `S024 RF-21 sin ningun codigo el fichero lleva la columna Code vacia y ninguna de atributos`() {
        val rows = csv(listOf(point("1", null)))

        rows.first().last() shouldBe "Code"
        rows[1].last() shouldBe ""
    }

    @Test
    fun `S024 RF-21 un nombre con dos tipos da dos columnas y la segunda dice el tipo`() {
        val rows = csv(
            listOf(
                point("1", code("A", PointAttribute("Valor", AttributeType.NUMBER, "1.5"))),
                point("2", code("B", PointAttribute("valor", AttributeType.TEXT, "x"))),
            ),
        )

        val header = rows.first()
        header.drop(header.indexOf("Code")) shouldBe listOf("Code", "Valor", "valor (text)")
        rows[1].takeLast(2) shouldBe listOf("1.5", "")
        rows[2].takeLast(2) shouldBe listOf("", "x")
    }

    @Test
    fun `S024 RF-21 los numeros usan el signo decimal elegido y no se redondean`() {
        val rows = csv(
            listOf(point("1", arq)),
            CsvOptions(separator = ColumnSeparator.SEMICOLON, decimal = DecimalSeparator.COMMA),
        )

        rows[1].last() shouldBe "0,123456789012345"
    }

    @Test
    fun `S024 RF-21 un texto con el separador va entre comillas`() {
        val out = StringBuilder()
        val points = listOf(point("1", code("A", PointAttribute("Nota", AttributeType.TEXT, "junto, al muro"))))

        CsvExport.write(points.asSequence(), project, CsvOptions(), out, attributes = AttributeColumns.of(points))

        out.toString().trimEnd().lines().last().endsWith("\"junto, al muro\"") shouldBe true
    }

    @Test
    fun `S024 RF-21 los formatos PENZD y PNEZD no llevan codigo ni atributos`() {
        val rows = csv(listOf(point("1", arq)), CsvOptions(format = ExportFormat.PENZD))

        rows.first() shouldBe listOf("Name", "Easting", "Northing", "Elevation", "Description")
    }

    @Test
    fun `S024 RF-21 en el personalizado el codigo se elige como cualquier columna y los atributos no se escriben`() {
        val rows = csv(
            listOf(point("1", arq)),
            CsvOptions(format = ExportFormat.CUSTOM, customColumns = setOf(ExportColumn.NAME, ExportColumn.CODE)),
        )

        rows shouldBe listOf(listOf("Name", "Code"), listOf("1", "ARQ"))
    }

    @Test
    fun `S024 RF-21 las columnas de atributos salen en el orden de primera aparicion`() {
        val columns = AttributeColumns.of(listOf(point("1", poz), point("2", arq)))

        columns.map { it.header } shouldBe listOf("Diametro", "Profundidad", "Material")
    }
}
