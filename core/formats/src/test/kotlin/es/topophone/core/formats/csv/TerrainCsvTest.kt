package es.topophone.core.formats.csv

import es.topophone.core.formats.GroundTransform
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

/** El CSV de un proyecto en terreno: coordenadas de terreno y primera línea de comentario (S058/RF-13). */
class TerrainCsvTest {
    private val project = Project(
        "p1", "Obra", "", "", ProjectCrs.ETRS89_UTM30, LinearUnit.METRE, AngularUnit.DMS, CorrectionsDatum.ETRS89,
        null, 0, 0, null, null,
    )
    private val point = Point(
        id = "id-1",
        projectId = "p1",
        name = "1",
        description = "",
        origin = PointOrigin.MANUAL,
        coordinates = PointCoordinates(GeodeticPosition(40.4168, -3.7036, null), 440_300.0, 4_474_250.0, 650.0),
        measurement = null,
        typed = null,
        edited = false,
        hidden = false,
        copyOf = null,
        createdAtMs = 0,
        deletedAtMs = null,
    )
    private val ground = GroundTransform(440_000.0, 4_474_000.0, 1.0004)
    private val penzd = CsvOptions(format = ExportFormat.PENZD)

    private fun export(withGround: GroundTransform?): List<String> {
        val out = StringBuilder()
        CsvExport.write(sequenceOf(point), project, penzd, out, ground = withGround)
        return out.toString().split("\r\n").filter { it.isNotEmpty() }
    }

    @Test
    fun `S058 RF-13 sin plano el CSV es el de siempre, sin linea de comentario`() {
        val lines = export(null)

        lines shouldBe listOf("Name,Easting,Northing,Elevation,Description", "1,440300.0000,4474250.0000,650.0000,")
    }

    @Test
    fun `S058 RF-13 con plano la primera linea es un comentario con el origen y el factor`() {
        val lines = export(ground)

        lines[0] shouldBe
            "# TopoPhone ground coordinates origin easting=440000.0000 northing=4474000.0000 grid units=m factor=1.0004000000"
    }

    @Test
    fun `S058 RF-13 con plano las coordenadas son de terreno y la altura no cambia`() {
        val lines = export(ground)

        // Terreno: origen + k·(punto − origen) = 440000 + 1,0004·300 y 4474000 + 1,0004·250.
        lines[2] shouldBe "1,440300.1200,4474250.1000,650.0000,"
    }

    @Test
    fun `S058 RF-13 los nombres de las columnas no cambian con el plano`() {
        export(ground)[1] shouldBe export(null)[0]
    }

    @Test
    fun `S058 RF-13 el comentario no lleva separadores aunque el decimal sea la coma`() {
        val out = StringBuilder()
        CsvExport.write(
            sequenceOf(point),
            project,
            CsvOptions(format = ExportFormat.PENZD, separator = ColumnSeparator.SEMICOLON, decimal = DecimalSeparator.COMMA),
            out,
            ground = ground,
        )

        val comment = out.toString().lines()[0]
        comment.startsWith("# ") shouldBe true
        comment.any { it == ';' || it == ',' || it == '\t' } shouldBe false
    }
}
