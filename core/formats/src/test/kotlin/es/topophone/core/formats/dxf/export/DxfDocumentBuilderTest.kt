package es.topophone.core.formats.dxf.export

import es.topophone.core.model.project.LinearUnit
import es.topophone.core.model.project.ProjectCrs
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import java.io.ByteArrayOutputStream
import org.junit.jupiter.api.Test

/** El documento y el resumen de la exportación a DXF (S029/RF-1 a RF-4, RF-7 a RF-9, RF-11 a RF-14, RF-20, RF-21). */
class DxfDocumentBuilderTest {
    private val green = DxfTestData.code("ARBOL", 0x008000)
    private val red = DxfTestData.code("BORDE_1", 0xFF0000)
    private val all = DxfExportOptions(labels = DxfLabel.entries.toSet())

    private val p1 = DxfTestData.point(
        "P1",
        440_000.1234567,
        4_474_000.7654321,
        650.1236,
        green,
        "Roble",
        ellipsoidal = 701.3576,
    )
    private val p2 = DxfTestData.point("P2", 440_010.5, 4_474_010.25, null, red, "", ellipsoidal = null)
    private val p3 = DxfTestData.point("P3", 440_020.0, 4_474_000.0, 651.0, null, "Esquina", ellipsoidal = 702.234)
    private val points = listOf(p1, p2, p3)
    private val lines = listOf(
        DxfTestData.line("L1", false, red, p1, p2, p3),
        DxfTestData.line("L2", true, red, p1, p2, p3),
    )

    private fun bytes(build: DxfBuild): ByteArray = ByteArrayOutputStream().also { DxfWriter.write(build.document, it) }.toByteArray()

    private fun build(
        options: DxfExportOptions = all,
        project: es.topophone.core.model.project.Project = DxfTestData.project(),
        pts: List<es.topophone.core.model.point.Point> = points,
        lns: List<es.topophone.core.model.line.ProjectLine> = lines,
    ) = DxfDocumentBuilder.build(pts, lns, points, project, options)

    @Test
    fun `S029 RF-1 RF-3 RF-4 RF-7 RF-9 RF-11 en locales el fichero es el de referencia de tools`() {
        val result = build()

        bytes(result).toList() shouldBe DxfExportFixtures.bytes("referencia-locales.dxf").toList()
    }

    @Test
    fun `S029 RF-12 en globales longitud latitud y altura elipsoidal dan el fichero de referencia de tools`() {
        val golden = DxfExportFixtures.pairs("referencia-globales.dxf")
        val coordinates = golden.withIndex().filter { it.value == "0" to "POINT" }.map { (i, _) ->
            Triple(golden[i + 2].second.toDouble(), golden[i + 3].second.toDouble(), golden[i + 4].second.toDouble())
        }
        val global = listOf(p1, p2, p3).mapIndexed { i, p ->
            DxfTestData.point(
                p.name,
                p.coordinates.easting,
                p.coordinates.northing,
                p.coordinates.height,
                p.code,
                p.description,
                lat = coordinates[i].second,
                lon = coordinates[i].first,
                ellipsoidal = p.coordinates.ground.ellipsoidalHeightM,
            )
        }

        val result = DxfDocumentBuilder.build(global, lines, global, DxfTestData.project(), all.copy(coordinates = DxfCoordinates.GLOBAL))

        bytes(result).toList() shouldBe DxfExportFixtures.bytes("referencia-globales.dxf").toList()
        result.summary.unit shouldBe DxfUnit.DEGREE
    }

    @Test
    fun `S029 RF-4 los puntos sin codigo van a SIN_CODIGO y las capas siguen el orden pedido`() {
        val layers = build().document.layers

        layers.map { it.name } shouldBe listOf("0", "ARBOL", "BORDE_1", "SIN_CODIGO", "NOMBRES", "COTAS", "DESCRIPCIONES")
        layers.first { it.name == "BORDE_1" }.color shouldBe 1
        layers.first { it.name == "SIN_CODIGO" }.color shouldBe 7
    }

    @Test
    fun `S029 RF-7 solo las etiquetas marcadas y solo sus capas`() {
        val result = build(DxfExportOptions(labels = setOf(DxfLabel.NAME)))

        result.document.entities.filterIsInstance<DxfTextEntity>().map { it.text } shouldBe listOf("P1", "P2", "P3")
        result.document.layers.map { it.name } shouldBe listOf("0", "ARBOL", "BORDE_1", "SIN_CODIGO", "NOMBRES")
        build(DxfExportOptions(labels = emptySet())).document.entities.filterIsInstance<DxfTextEntity>() shouldBe emptyList()
    }

    @Test
    fun `S029 RF-7 por defecto nombre y cota y sin descripcion no hay texto de descripcion`() {
        val texts = build(DxfExportOptions()).document.entities.filterIsInstance<DxfTextEntity>()

        texts.map { it.layer to it.text } shouldBe
            listOf("NOMBRES" to "P1", "COTAS" to "650.124", "NOMBRES" to "P2", "NOMBRES" to "P3", "COTAS" to "651.000")
        build(all, pts = listOf(p2)).document.entities.filterIsInstance<DxfTextEntity>().map { it.layer } shouldBe listOf("NOMBRES")
    }

    @Test
    fun `S029 RF-8 la altura del texto va en la unidad del dibujo y se limita entre 0 01 y 100 m`() {
        fun heightOf(options: DxfExportOptions, unit: LinearUnit = LinearUnit.METRE) =
            build(options, DxfTestData.project(unit = unit), listOf(p1)).document.entities.filterIsInstance<DxfTextEntity>().first().height

        heightOf(DxfExportOptions(textHeightM = 2.0)) shouldBe 2.0
        heightOf(DxfExportOptions(textHeightM = 1.0), LinearUnit.INTERNATIONAL_FOOT) shouldBe (1.0 / 0.3048 plusOrMinus 1e-12)
        heightOf(DxfExportOptions(textHeightM = 0.001)) shouldBe 0.01
        heightOf(DxfExportOptions(textHeightM = 500.0)) shouldBe 100.0
        heightOf(DxfExportOptions(textHeightM = 11.132, coordinates = DxfCoordinates.GLOBAL)) shouldBe (1e-4 plusOrMinus 1e-15)
    }

    @Test
    fun `S029 RF-9 un punto o vertice sin altura lleva Z cero, se cuenta y no tiene etiqueta de cota`() {
        val result = build()

        val point = result.document.entities.filterIsInstance<DxfPointEntity>().first { it.layer == "BORDE_1" }
        point.z shouldBe 0.0
        result.summary.pointsWithoutHeight shouldBe 1
        result.summary.verticesWithoutHeight shouldBe 2 // P2 en la línea y en el polígono
        result.document.entities.filterIsInstance<DxfTextEntity>().filter { it.layer == "COTAS" }.size shouldBe 2
    }

    @Test
    fun `S029 RF-11 RF-14 un proyecto en pies escribe pies y el resumen dice la unidad`() {
        val feet = DxfTestData.project(unit = LinearUnit.US_SURVEY_FOOT)

        val result = build(project = feet, pts = listOf(p3))

        val point = result.document.entities.filterIsInstance<DxfPointEntity>().single()
        point.x shouldBe (440_020.0 / (1200.0 / 3937.0) plusOrMinus 1e-6)
        point.z shouldBe (651.0 / (1200.0 / 3937.0) plusOrMinus 1e-9)
        result.summary.unit shouldBe DxfUnit.US_SURVEY_FOOT
        build(project = DxfTestData.project(unit = LinearUnit.INTERNATIONAL_FOOT)).summary.unit shouldBe DxfUnit.INTERNATIONAL_FOOT
        build().summary.unit shouldBe DxfUnit.METRE
    }

    @Test
    fun `S029 RF-13 un proyecto geografico solo escribe globales aunque se pidan locales`() {
        val geographic = DxfTestData.project(crs = ProjectCrs.ETRS89_GEOGRAPHIC)

        val result = build(all.copy(coordinates = DxfCoordinates.LOCAL), geographic, listOf(p1))

        result.document.coordinates shouldBe DxfCoordinates.GLOBAL
        val point = result.document.entities.filterIsInstance<DxfPointEntity>().single()
        point.x shouldBe -3.0
        point.y shouldBe 40.0
        point.z shouldBe 701.3576
        result.summary.unit shouldBe DxfUnit.DEGREE
    }

    @Test
    fun `S029 RF-12 en globales un punto sin altura elipsoidal lleva Z cero y no tiene cota`() {
        val result = build(all.copy(coordinates = DxfCoordinates.GLOBAL))

        result.summary.pointsWithoutHeight shouldBe 1
        result.document.entities.filterIsInstance<DxfPointEntity>().first { it.layer == "BORDE_1" }.z shouldBe 0.0
    }

    @Test
    fun `S029 RF-3 las lineas y poligonos con pocos vertices con posicion no se escriben`() {
        val noPosition = DxfTestData.point("N", east = null, north = null)
        val short = DxfTestData.line("S", false, red, p1, noPosition)
        val poly = DxfTestData.line("T", true, red, p1, p2, noPosition)
        val ok = DxfTestData.line("OK", false, red, p1, noPosition, p3)

        val result = DxfDocumentBuilder.build(points, listOf(short, poly, ok), points + noPosition, DxfTestData.project(), all)

        result.summary.lines shouldBe 1
        result.summary.polygons shouldBe 0
        result.document.entities.filterIsInstance<DxfPolylineEntity>().single().vertices.size shouldBe 2
    }

    @Test
    fun `S029 RF-20 el resumen cuenta puntos lineas poligonos y capas sin la cero`() {
        val summary = build().summary

        summary.points shouldBe 3
        summary.lines shouldBe 1
        summary.polygons shouldBe 1
        summary.layers shouldBe 6
        summary.pointsWithoutPosition shouldBe 0
        summary.replacedChars shouldBe 0
    }

    @Test
    fun `S029 RF-11 un punto sin Este y Norte no se escribe y se cuenta`() {
        val noPosition = DxfTestData.point("N", east = null, north = 4.0)

        val result = DxfDocumentBuilder.build(points + noPosition, emptyList(), points, DxfTestData.project(), all)

        result.summary.points shouldBe 3
        result.summary.pointsWithoutPosition shouldBe 1
        result.document.entities.filterIsInstance<DxfTextEntity>().none { it.text == "N" } shouldBe true
    }

    @Test
    fun `S029 RF-1 un proyecto sin puntos da un documento sin entidades`() {
        val result = build(pts = emptyList(), lns = emptyList())

        result.document.entities shouldBe emptyList()
        result.document.layers.map { it.name } shouldBe listOf("0")
        result.summary.points shouldBe 0
    }

    @Test
    fun `S029 RF-21 construir el documento no cambia ni los puntos ni las lineas`() {
        val beforePoints = points.toList()
        val beforeLines = lines.toList()

        build()

        points shouldBe beforePoints
        lines shouldBe beforeLines
        points.map { it.coordinates } shouldBe beforePoints.map { it.coordinates }
    }
}
