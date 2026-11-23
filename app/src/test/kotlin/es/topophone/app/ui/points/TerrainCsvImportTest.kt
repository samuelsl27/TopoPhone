package es.topophone.app.ui.points

import es.topophone.app.ui.projects.toGroundTransform
import es.topophone.core.formats.csv.CsvExport
import es.topophone.core.formats.csv.CsvOptions
import es.topophone.core.formats.csv.DelimitedReader
import es.topophone.core.formats.csv.ExportFormat
import es.topophone.core.model.point.ColumnMapping
import es.topophone.core.model.point.ImportOptions
import es.topophone.core.model.point.ImportSeparator
import es.topophone.core.model.point.Point
import es.topophone.core.model.project.AngularUnit
import es.topophone.core.model.project.CorrectionsDatum
import es.topophone.core.model.project.HeightSystem
import es.topophone.core.model.project.LinearUnit
import es.topophone.core.model.project.Project
import es.topophone.core.model.project.ProjectCrs
import es.topophone.core.model.project.TerrainPlane
import es.topophone.core.survey.ImportAnalyzer
import es.topophone.core.survey.TerrainView
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import java.io.StringReader
import org.junit.Test

/** Importar un CSV en un proyecto en terreno: lo escrito es de terreno y los puntos quedan en cuadrícula; el CSV exportado vuelve igual (S058/RF-12). */
class TerrainCsvImportTest {
    private val crs = ProjectCrs.ETRS89_UTM30
    private val plane = TerrainPlane(40.4, -3.7, 1.0004)
    private val project = Project(
        "p1", "Obra", "", "", crs, LinearUnit.METRE, AngularUnit.DMS, CorrectionsDatum.ETRS89, null, 0, 0, null, null,
        HeightSystem.ORTHOMETRIC, null, plane,
    )
    private val view = TerrainView.of(plane, crs).shouldNotBeNull()
    private val analyzer = ImportAnalyzer()

    private fun import(text: String, hasHeader: Boolean, terrain: TerrainView): List<Point> {
        var next = 0
        val options = ImportOptions(
            separator = ImportSeparator.COMMA,
            hasHeader = hasHeader,
            mapping = ColumnMapping.penzd(5),
        )
        return analyzer.collect(
            rows = DelimitedReader.rows(StringReader(text), options.separator),
            options = options,
            crs = crs,
            heightSystem = HeightSystem.ORTHOMETRIC,
            takenNames = emptySet(),
            projectId = "p1",
            importId = "imp",
            createdAtMs = 1L,
            newId = { "id-${++next}" },
            terrain = terrain,
        ).points
    }

    @Test
    fun `S058 RF-12 un CSV de terreno queda en cuadricula al importarlo`() {
        val points = import("A,440300.1200,4474250.1000,650.0,pozo\r\n", hasHeader = false, terrain = view)

        val point = points.single()
        val grid = view.toGrid(440_300.12, 4_474_250.10)
        point.coordinates.easting.shouldNotBeNull() shouldBe (grid.easting plusOrMinus 1e-9)
        point.coordinates.northing.shouldNotBeNull() shouldBe (grid.northing plusOrMinus 1e-9)
        point.coordinates.height.shouldNotBeNull() shouldBe (650.0 plusOrMinus 1e-9)
    }

    @Test
    fun `S058 RF-12 el mismo CSV en un proyecto en cuadricula se entiende como de cuadricula`() {
        val points = import("A,440300.1200,4474250.1000,650.0,pozo\r\n", hasHeader = false, terrain = TerrainView.GRID)

        points.single().coordinates.easting.shouldNotBeNull() shouldBe (440_300.12 plusOrMinus 1e-9)
    }

    @Test
    fun `S058 RF-12 el CSV exportado en terreno vuelve a importarse en el mismo proyecto con los mismos puntos`() {
        val original = import("A,440300.0000,4474250.0000,650.0,a\r\nB,440812.5000,4474901.2500,655.5,b\r\n", false, TerrainView.GRID)
        val out = StringBuilder()
        CsvExport.write(
            original.asSequence(),
            project,
            CsvOptions(format = ExportFormat.PENZD),
            out,
            ground = view.toGroundTransform(),
        )

        val again = import(out.toString(), hasHeader = true, terrain = view)

        again.map { it.name } shouldBe listOf("A", "B")
        again.zip(original).forEach { (a, b) ->
            a.coordinates.easting.shouldNotBeNull() shouldBe (b.coordinates.easting.shouldNotBeNull() plusOrMinus 1e-3)
            a.coordinates.northing.shouldNotBeNull() shouldBe (b.coordinates.northing.shouldNotBeNull() plusOrMinus 1e-3)
        }
    }

    @Test
    fun `S058 RF-12 el comentario de cabecera no cuenta como fila y las lineas siguen su cuenta`() {
        val text = "# TopoPhone ground coordinates origin easting=1 northing=2 grid units=m factor=1.0004000000\r\nName,Easting\r\nA,1\r\n"

        val rows = DelimitedReader.rows(StringReader(text), ImportSeparator.COMMA).toList()

        rows.map { it.line } shouldBe listOf(2, 3)
        rows[0].cells shouldBe listOf("Name", "Easting")
    }

    @Test
    fun `S058 RF-12 una primera fila que empieza por almohadilla pero no es el comentario se lee como siempre`() {
        val rows = DelimitedReader.rows(StringReader("#1,440300,4474250\r\n"), ImportSeparator.COMMA).toList()

        rows.single().cells shouldBe listOf("#1", "440300", "4474250")
    }
}
