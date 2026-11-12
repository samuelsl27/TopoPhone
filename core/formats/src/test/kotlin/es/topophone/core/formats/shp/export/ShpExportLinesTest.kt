package es.topophone.core.formats.shp.export

import es.topophone.core.formats.shp.export.ShpExportSamples.attribute
import es.topophone.core.formats.shp.export.ShpExportSamples.code
import es.topophone.core.formats.shp.export.ShpExportSamples.line
import es.topophone.core.formats.shp.export.ShpExportSamples.point
import es.topophone.core.formats.shp.export.ShpExportSamples.project
import es.topophone.core.model.codes.AttributeType
import es.topophone.core.model.line.ProjectLine
import es.topophone.core.model.project.ProjectCrs
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** La preparación de las capas de líneas y polígonos de la exportación a Shapefile, y de la selección con líneas completas (S055/RF-2, RF-3, RF-7, RF-22). */
class ShpExportLinesTest {
    private val builder = ShpExportBuilder()

    private val points = listOf(
        point("1", easting = 440_000.0, northing = 4_474_000.0, height = 648.0),
        point("2", easting = 440_010.0, northing = 4_474_000.0, height = 649.0),
        point("3", easting = 440_010.0, northing = 4_474_010.0, height = null),
        point("4", easting = 440_000.0, northing = 4_474_010.0, height = 651.0),
        point("5", easting = null, northing = null),
    )

    private fun ShpExport.layer(kind: ShpLayerKind): ShpLayer = layers.single { it.kind == kind }

    private fun ShpLayer.value(row: Int, name: String): Any? = rows[row][columns.indexOfFirst { it.name == name }]

    private fun build(vararg lines: ProjectLine, language: ShpNameLanguage = ShpNameLanguage.ES) =
        builder.build(points, project(), language = language, lines = lines.toList())

    // ------------------------------------------------------------------------------------------------------ las capas
    @Test
    fun `S055 RF-3 un proyecto sin lineas ni poligonos solo tiene la capa de puntos`() {
        build().layers.map { it.kind } shouldBe listOf(ShpLayerKind.POINTS)
    }

    @Test
    fun `S055 RF-3 con lineas abiertas hay una capa de lineas PolyLineZ y sin poligonos no hay capa de poligonos`() {
        val export = build(line("l1", listOf("1", "2")))

        export.layers.map { it.kind } shouldBe listOf(ShpLayerKind.POINTS, ShpLayerKind.LINES)
        ShpLayerKind.LINES.type shouldBe ShpType.POLYLINE_Z
        export.summary.lines shouldBe 1
        export.summary.polygons shouldBe 0
    }

    @Test
    fun `S055 RF-3 con poligonos hay una capa de poligonos PolygonZ y sin lineas abiertas no hay capa de lineas`() {
        val export = build(line("p1", listOf("1", "2", "3"), closed = true))

        export.layers.map { it.kind } shouldBe listOf(ShpLayerKind.POINTS, ShpLayerKind.POLYGONS)
        ShpLayerKind.POLYGONS.type shouldBe ShpType.POLYGON_Z
        export.summary.lines shouldBe 0
        export.summary.polygons shouldBe 1
    }

    @Test
    fun `S055 RF-3 con las dos hay tres capas en el orden puntos, lineas y poligonos y ninguna vacia`() {
        val export = build(line("p1", listOf("1", "2", "3"), closed = true), line("l1", listOf("1", "2")))

        export.layers.map { it.kind } shouldBe listOf(ShpLayerKind.POINTS, ShpLayerKind.LINES, ShpLayerKind.POLYGONS)
        export.layers.all { it.rows.isNotEmpty() && it.rows.size == it.geometries.size } shouldBe true
    }

    @Test
    fun `S055 RF-3 las capas de lineas y poligonos llevan el prj de la de puntos`() {
        val export = build(line("l1", listOf("1", "2")), line("p1", listOf("1", "2", "3"), closed = true))

        export.layers.map { it.prj }.toSet().size shouldBe 1
        export.layers.first().prj shouldBe ShpPrjDefinitions().of(ProjectCrs.ETRS89_UTM30)
    }

    // ----------------------------------------------------------------------------------------------------- las columnas
    @Test
    fun `S055 RF-7 las lineas llevan nombre, codigo y vertices en el idioma de la interfaz`() {
        val es = build(line("l1", listOf("1", "2")), language = ShpNameLanguage.ES).layer(ShpLayerKind.LINES)
        val en = build(line("l1", listOf("1", "2")), language = ShpNameLanguage.EN).layer(ShpLayerKind.LINES)

        es.columns.map { it.name } shouldBe listOf("NOMBRE", "CODIGO", "VERTICES")
        en.columns.map { it.name } shouldBe listOf("NAME", "CODE", "VERTICES")
        es.columns.map { it.type } shouldBe listOf(DbfType.TEXT, DbfType.TEXT, DbfType.INTEGER)
    }

    @Test
    fun `S055 RF-7 los valores son el nombre, el codigo y el numero de vertices con posicion`() {
        val bord = code("BORD")
        val export = build(line("l1", listOf("1", "2", "3"), code = bord, name = "Bordillo norte"), line("l2", listOf("1", "2")))
        val layer = export.layer(ShpLayerKind.LINES)

        layer.value(0, "NOMBRE") shouldBe "Bordillo norte"
        layer.value(0, "CODIGO") shouldBe "BORD"
        layer.value(0, "VERTICES") shouldBe 3L
        layer.value(1, "CODIGO") shouldBe null // una línea sin código lo deja vacío
        layer.value(1, "VERTICES") shouldBe 2L
    }

    @Test
    fun `S055 RF-7 un poligono cuenta sus vertices sin repetir el primero`() {
        val layer = build(line("p1", listOf("1", "2", "3", "4"), closed = true)).layer(ShpLayerKind.POLYGONS)

        layer.value(0, "VERTICES") shouldBe 4L
        (layer.geometries.single() as ShpGeometry.Polygon).vertices.size shouldBe 4
    }

    @Test
    fun `S055 RF-7 los atributos del codigo de cada linea son columnas tipadas de su capa`() {
        val muro = code("MURO", attribute("Ancho", AttributeType.NUMBER, "0.3"), attribute("Material", AttributeType.TEXT, "Hormigón"))
        val export = build(line("l1", listOf("1", "2"), code = muro), line("l2", listOf("2", "3")))
        val layer = export.layer(ShpLayerKind.LINES)

        layer.columns.drop(3).map { it.name to it.type } shouldBe listOf("ANCHO" to DbfType.DECIMAL, "MATERIAL" to DbfType.TEXT)
        layer.value(0, "ANCHO") shouldBe 0.3
        layer.value(0, "MATERIAL") shouldBe "Hormigón"
        layer.value(1, "ANCHO") shouldBe null
    }

    @Test
    fun `S055 RF-7 cada capa lleva solo los atributos de los codigos de sus objetos`() {
        val muro = code("MURO", attribute("Ancho", AttributeType.NUMBER, "0.3"))
        val parcela = code("PARC", attribute("Referencia", AttributeType.TEXT, "A-1"))
        val export = build(
            line("l1", listOf("1", "2"), code = muro),
            line("p1", listOf("1", "2", "3"), closed = true, code = parcela),
        )

        export.layer(ShpLayerKind.LINES).columns.drop(3).map { it.name } shouldBe listOf("ANCHO")
        export.layer(ShpLayerKind.POLYGONS).columns.drop(3).map { it.name } shouldBe listOf("REFERENCIA")
    }

    @Test
    fun `S055 RF-9 los atributos de las lineas que chocan con una columna fija se renombran y salen en el resumen`() {
        val raro = code("X", attribute("Nombre", AttributeType.TEXT, "a"), attribute("Ancho vía", AttributeType.NUMBER, "1.5"))
        val export = build(line("l1", listOf("1", "2"), code = raro))

        export.layer(ShpLayerKind.LINES).columns.drop(3).map { it.name } shouldBe listOf("NOMBRE1", "ANCHO_VIA")
        export.summary.renamedAttributes shouldBe listOf(ShpRenamedColumn("Nombre", "NOMBRE1"), ShpRenamedColumn("Ancho vía", "ANCHO_VIA"))
    }

    @Test
    fun `S055 RF-9 un atributo renombrado igual en dos capas sale una sola vez en el resumen`() {
        val raro = code("X", attribute("Ancho vía", AttributeType.NUMBER, "1.5"))
        val export = build(line("l1", listOf("1", "2"), code = raro), line("p1", listOf("1", "2", "3"), closed = true, code = raro))

        export.summary.renamedAttributes shouldBe listOf(ShpRenamedColumn("Ancho vía", "ANCHO_VIA"))
    }

    // --------------------------------------------------------------------------------------------------- la geometria
    @Test
    fun `S055 RF-12 los vertices de una linea son los de sus puntos, sin redondear, con la Z del proyecto`() {
        val layer = build(line("l1", listOf("1", "2", "4"))).layer(ShpLayerKind.LINES)

        (layer.geometries.single() as ShpGeometry.Line).vertices shouldBe listOf(
            ShpVertex(440_000.0, 4_474_000.0, 648.0),
            ShpVertex(440_010.0, 4_474_000.0, 649.0),
            ShpVertex(440_000.0, 4_474_010.0, 651.0),
        )
    }

    @Test
    fun `S055 RF-13 con geograficas los vertices son la longitud, la latitud y la altura elipsoidal`() {
        val options = ShpExportOptions(ShpCoordinates.GEOGRAPHIC)
        val export = builder.build(points, project(), options, lines = listOf(line("l1", listOf("1", "2"))))

        (export.layer(ShpLayerKind.LINES).geometries.single() as ShpGeometry.Line).vertices shouldBe
            List(2) { ShpVertex(-3.7038, 40.4168, 700.5) }
    }

    @Test
    fun `S055 RF-14 un vertice sin altura se escribe con Z igual a 0 y se cuenta`() {
        val export = build(line("l1", listOf("2", "3")))

        (export.layer(ShpLayerKind.LINES).geometries.single() as ShpGeometry.Line).vertices.map { it.z } shouldBe listOf(649.0, 0.0)
        export.summary.verticesWithoutHeight shouldBe 2 // el punto 3 en la capa de puntos y su vértice en la línea
    }

    @Test
    fun `S055 RF-14 los vertices sin altura de lineas y poligonos se suman a los de los puntos`() {
        val export = build(line("l1", listOf("2", "3")), line("p1", listOf("1", "2", "3"), closed = true))

        // el punto 3 está en el .shp de puntos, en la línea y en el polígono: cada uno es un caso
        export.summary.verticesWithoutHeight shouldBe 3
    }

    @Test
    fun `S055 RF-14b los puntos de una linea sin posicion no son vertices`() {
        val export = build(line("l1", listOf("1", "5", "2")))

        (export.layer(ShpLayerKind.LINES).geometries.single() as ShpGeometry.Line).vertices.size shouldBe 2
        export.layer(ShpLayerKind.LINES).value(0, "VERTICES") shouldBe 2L
    }

    // -------------------------------------------------------------------------------------------- lo que no se escribe
    @Test
    fun `S055 RF-3 una linea con menos de 2 vertices con posicion no se escribe`() {
        val export = build(line("l1", listOf("1", "5")), line("l2", listOf("1")), line("l3", emptyList()))

        export.layers.map { it.kind } shouldBe listOf(ShpLayerKind.POINTS)
        export.summary.lines shouldBe 0
    }

    @Test
    fun `S055 RF-3 un poligono con menos de 3 vertices con posicion no se escribe`() {
        val export = build(line("p1", listOf("1", "2"), closed = true), line("p2", listOf("1", "2", "5"), closed = true))

        export.layers.map { it.kind } shouldBe listOf(ShpLayerKind.POINTS)
        export.summary.polygons shouldBe 0
    }

    @Test
    fun `S055 RF-3 una linea que apunta a un punto que ya no esta no cuenta ese vertice`() {
        val export = build(line("l1", listOf("1", "borrado", "2")))

        (export.layer(ShpLayerKind.LINES).geometries.single() as ShpGeometry.Line).vertices.size shouldBe 2
    }

    @Test
    fun `S055 RF-19 sin puntos no hay capas aunque haya lineas`() {
        val export = builder.build(emptyList(), project(), lines = listOf(line("l1", listOf("1", "2"))), allPoints = points)

        export.isEmpty shouldBe true
        export.summary.lines shouldBe 0
    }

    // ---------------------------------------------------------------------------------------------------- la seleccion
    @Test
    fun `S055 RF-2 con una seleccion solo van las lineas con todos sus vertices entre los puntos elegidos`() {
        val all = listOf(line("l1", listOf("1", "2")), line("l2", listOf("2", "3")), line("p1", listOf("1", "2", "3"), closed = true))

        ShpExportBuilder.linesOf(all, setOf("1")).map { it.id } shouldBe emptyList()
        ShpExportBuilder.linesOf(all, setOf("1", "2")).map { it.id } shouldBe listOf("l1")
        ShpExportBuilder.linesOf(all, setOf("1", "2", "3", "4")).map { it.id } shouldBe listOf("l1", "l2", "p1")
    }

    @Test
    fun `S055 RF-2 los vertices de una linea de la seleccion salen de todos los puntos del proyecto`() {
        val selected = points.take(2)
        val all = listOf(line("l1", listOf("1", "2")), line("l2", listOf("2", "3")))
        val chosen = ShpExportBuilder.linesOf(all, selected.map { it.id }.toSet())

        val export = builder.build(selected, project(), lines = chosen, allPoints = points)

        export.summary.points shouldBe 2
        export.summary.lines shouldBe 1
        (export.layer(ShpLayerKind.LINES).geometries.single() as ShpGeometry.Line).vertices.size shouldBe 2
    }

    @Test
    fun `S055 RF-3 contar las lineas de antemano da lo mismo que construir las capas`() {
        val lines = listOf(
            line("l1", listOf("1", "2")),
            line("l2", listOf("1")),
            line("p1", listOf("1", "2", "3"), closed = true),
            line("p2", listOf("1", "2", "5"), closed = true),
        )
        val options = ShpExportOptions()

        val counted = builder.countLines(points, project(), options, lines)
        val built = builder.build(points, project(), options, lines = lines).summary

        counted shouldBe (built.lines to built.polygons)
        counted shouldBe (1 to 1)
        builder.countLines(emptyList(), project(), options, lines, points) shouldBe (0 to 0)
    }

    @Test
    fun `S055 RF-22 el resumen cuenta los puntos, las lineas y los poligonos escritos`() {
        val export = build(line("l1", listOf("1", "2")), line("l2", listOf("2", "3")), line("p1", listOf("1", "2", "3"), closed = true))

        listOf(export.summary.points, export.summary.lines, export.summary.polygons) shouldBe listOf(5, 2, 1)
    }
}
