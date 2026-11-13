package es.topophone.core.formats.kml.export

import es.topophone.core.formats.csv.ExportColumn
import es.topophone.core.formats.shp.export.ShpExportSamples.attribute
import es.topophone.core.formats.shp.export.ShpExportSamples.code
import es.topophone.core.formats.shp.export.ShpExportSamples.line
import es.topophone.core.formats.shp.export.ShpExportSamples.point
import es.topophone.core.formats.shp.export.ShpExportSamples.project
import es.topophone.core.model.codes.AttributeType
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test

/** Preparar la exportación a KML: puntos, líneas y polígonos con sus carpetas, estilos, datos extendidos, altura y resumen (S031/RF-20 a RF-27). */
class KmlExportBuilderTest {

    private val builder = KmlExportBuilder()
    private val tree = code("ARBOL", attribute("Diámetro", AttributeType.NUMBER, "0.35"), attribute("Ramas", AttributeType.INTEGER, "4"))
    private val wall = code("MURO")

    private fun build(
        points: List<es.topophone.core.model.point.Point>,
        options: KmlExportOptions = KmlExportOptions(),
        lines: List<es.topophone.core.model.line.ProjectLine> = emptyList(),
    ) = builder.build(points, project(), options, lines = lines, allPoints = points)

    private fun marks(export: KmlExport) = export.document.folders.flatMap { it.marks }

    // ---------- puntos ----------

    @Test
    fun `S031 RF-21 una marca por punto con su nombre y su descripcion`() {
        val export = build(listOf(point("1", description = "Una farola"), point("2")))

        marks(export).map { it.name to it.description } shouldBe listOf("P-1" to "Una farola", "P-2" to "")
        export.summary.points shouldBe 2
        export.isEmpty shouldBe false
    }

    @Test
    fun `S031 RF-21 los datos extendidos son las columnas de Todas las columnas, la del codigo y una por atributo`() {
        val export = build(listOf(point("1", code = tree), point("2")))

        val first = marks(export).first().data
        first.map { it.first } shouldBe ExportColumn.entries.map { it.header } + listOf("Diámetro", "Ramas")
        first.first { it.first == "Name" }.second shouldBe "P-1"
        first.first { it.first == "Code" }.second shouldBe "ARBOL"
        first.first { it.first == "Easting" }.second shouldBe "440000.123456789"
        first.first { it.first == "Diámetro" }.second shouldBe "0.35"
        first.first { it.first == "Ramas" }.second shouldBe "4"
    }

    @Test
    fun `S031 RF-21 lo que el punto no tiene va vacio`() {
        val export = build(listOf(point("1", height = null, ellipsoidal = null), point("2", code = tree)))

        val withoutCode = marks(export).first { it.name == "P-1" }.data
        withoutCode.first { it.first == "Elevation" }.second shouldBe ""
        withoutCode.first { it.first == "Code" }.second shouldBe ""
        withoutCode.first { it.first == "Diámetro" }.second shouldBe ""
    }

    @Test
    fun `S031 RF-24 la longitud y la latitud son las del punto, las del datum de los calculos`() {
        val export = build(listOf(point("1", latitude = 40.4168, longitude = -3.7038)))

        val vertex = marks(export).single().geometry.shouldBeInstanceOf<KmlGeometry.Point>().vertex
        vertex.lon shouldBe -3.7038
        vertex.lat shouldBe 40.4168
    }

    @Test
    fun `S031 RF-25 un punto sin posicion no se escribe y se cuenta`() {
        val export = build(listOf(point("1"), point("2", latitude = Double.NaN, longitude = Double.NaN)))

        marks(export).map { it.name } shouldBe listOf("P-1")
        export.summary.points shouldBe 1
        export.summary.pointsWithoutPosition shouldBe 1
    }

    @Test
    fun `S031 RF-20 sin puntos no hay nada que escribir`() {
        val export = build(emptyList())

        export.isEmpty shouldBe true
        export.summary shouldBe KmlExportSummary()
    }

    @Test
    fun `S031 RF-25 si ningun punto tiene posicion tampoco hay carpetas`() {
        val export = build(listOf(point("1", latitude = Double.NaN, longitude = 0.0)))

        export.isEmpty shouldBe true
        export.summary.pointsWithoutPosition shouldBe 1
    }

    // ---------- altura ----------

    @Test
    fun `S031 RF-26 sin Incluir altura todas las marcas van pegadas al suelo`() {
        val export = build(listOf(point("1", height = 648.25), point("2", height = null)), KmlExportOptions(includeHeight = false))

        marks(export).map { it.absolute } shouldBe listOf(false, false)
        export.summary.marksWithoutHeight shouldBe 0
    }

    @Test
    fun `S031 RF-26 con Incluir altura la marca con altura va absoluta con la altura del proyecto`() {
        val export = build(listOf(point("1", height = 648.25)), KmlExportOptions(includeHeight = true))

        val mark = marks(export).single()
        mark.absolute shouldBe true
        mark.geometry.shouldBeInstanceOf<KmlGeometry.Point>().vertex.height shouldBe 648.25
    }

    @Test
    fun `S031 RF-27 con Incluir altura la marca sin altura va pegada al suelo y se cuenta`() {
        val export = build(
            listOf(point("1", height = 648.25), point("2", height = null), point("3", height = Double.NaN)),
            KmlExportOptions(includeHeight = true),
        )

        marks(export).map { it.absolute } shouldBe listOf(true, false, false)
        export.summary.marksWithoutHeight shouldBe 2
    }

    // ---------- carpetas y estilos ----------

    @Test
    fun `S031 RF-23 las marcas se agrupan en una carpeta por codigo con el color de su codigo`() {
        val export = build(listOf(point("1", code = tree), point("2", code = wall), point("3", code = tree)))

        export.document.folders.map { it.name to it.marks.map { m -> m.name } } shouldBe
            listOf("ARBOL" to listOf("P-1", "P-3"), "MURO" to listOf("P-2"))
        export.document.styles.map { it.id to it.argb } shouldBe listOf("ARBOL" to 0xFF1E88E5.toInt(), "MURO" to 0xFF1E88E5.toInt())
        marks(export).map { it.styleId } shouldBe listOf("ARBOL", "ARBOL", "MURO")
    }

    @Test
    fun `S031 RF-23 los puntos sin codigo van a una carpeta con el nombre del proyecto, sin estilo, al final`() {
        val export = build(listOf(point("1"), point("2", code = tree)))

        export.document.folders.map { it.name } shouldBe listOf("ARBOL", "Calle Mayor 12")
        marks(export).first { it.name == "P-1" }.styleId shouldBe null
        export.document.styles.map { it.id } shouldBe listOf("ARBOL")
    }

    @Test
    fun `S031 RF-23 sin ningun codigo hay una sola carpeta con el nombre del proyecto y ningun estilo`() {
        val export = build(listOf(point("1"), point("2")))

        export.document.folders.map { it.name } shouldBe listOf("Calle Mayor 12")
        export.document.styles shouldBe emptyList()
        export.summary.folders shouldBe 1
    }

    @Test
    fun `S031 RF-23 el identificador del estilo es el codigo con solo letras, cifras, guion y guion bajo y no se repite`() {
        val export = build(listOf(point("1", code = code("A B")), point("2", code = code("A_B")), point("3", code = code("Árbol/1"))))

        export.document.styles.map { it.id } shouldBe listOf("A_B", "A_B_2", "_rbol_1")
        export.document.folders.map { it.name } shouldBe listOf("A B", "A_B", "Árbol/1")
    }

    @Test
    fun `S031 RF-23 el color del estilo es el del codigo, opaco`() {
        val export = build(listOf(point("1", code = tree)))

        export.document.styles.single().argb shouldBe (0xFF000000.toInt() or 0x1E88E5)
    }

    @Test
    fun `S031 RF-28 una marca lleva los nombres de las fotos que le da quien exporta`() {
        val points = listOf(point("1"), point("2"))
        val export = builder.build(points, project(), photoNames = { if (it.id == "1") listOf("P-1_1.jpg", "P-1_2.jpg") else emptyList() })

        marks(export).map { it.photos } shouldBe listOf(listOf("P-1_1.jpg", "P-1_2.jpg"), emptyList())
    }

    @Test
    fun `S031 RF-28 los nombres de las fotos solo se piden para las marcas que se escriben`() {
        val points = listOf(point("1"), point("2", latitude = Double.NaN, longitude = Double.NaN))
        val asked = ArrayList<String>()

        builder.build(points, project(), photoNames = { asked.add(it.id).let { emptyList() } })

        asked shouldBe listOf("1")
    }

    // ---------- lineas y poligonos ----------

    private val corners = listOf(
        point("a", latitude = 40.0, longitude = -3.0, height = 10.0),
        point("b", latitude = 40.0, longitude = -2.9, height = 10.0),
        point("c", latitude = 40.1, longitude = -2.9, height = 10.0),
        point("d", latitude = 40.1, longitude = -3.0, height = 10.0),
    )

    @Test
    fun `S031 RF-22 una linea se escribe como una linea con los vertices de sus puntos`() {
        val export = build(corners, lines = listOf(line("l1", listOf("a", "b", "c"), code = wall, name = "Muro 1")))

        val mark = marks(export).first { it.name == "Muro 1" }
        val geometry = mark.geometry.shouldBeInstanceOf<KmlGeometry.Line>()
        geometry.vertices.map { it.lon to it.lat } shouldBe listOf(-3.0 to 40.0, -2.9 to 40.0, -2.9 to 40.1)
        export.summary.lines shouldBe 1
    }

    @Test
    fun `S031 RF-22 la linea lleva nombre, codigo, numero de vertices y los atributos de su codigo como datos`() {
        val coded = code("TUBO", attribute("Diámetro", AttributeType.NUMBER, "0.2"))
        val export = build(corners, lines = listOf(line("l1", listOf("a", "b"), code = coded, name = "Tubo 1")))

        marks(export).first { it.name == "Tubo 1" }.data shouldBe
            listOf("Name" to "Tubo 1", "Code" to "TUBO", "Vertices" to "2", "Diámetro" to "0.2")
    }

    @Test
    fun `S031 RF-22 una linea va a la carpeta de su codigo despues de los puntos de ese codigo`() {
        val points = corners.map { it }.toMutableList().also { it[0] = point("a", latitude = 40.0, longitude = -3.0, code = wall) }
        val export = build(points, lines = listOf(line("l1", listOf("a", "b"), code = wall, name = "Muro 1")))

        export.document.folders.first { it.name == "MURO" }.marks.map { it.name } shouldBe listOf("P-a", "Muro 1")
    }

    @Test
    fun `S031 RF-22 una linea sin codigo va a la carpeta del proyecto`() {
        val export = build(corners, lines = listOf(line("l1", listOf("a", "b"), name = "Suelta")))

        export.document.folders.last().name shouldBe "Calle Mayor 12"
        export.document.folders.last().marks.last().name shouldBe "Suelta"
    }

    @Test
    fun `S031 RF-22 un poligono en sentido horario se invierte conservando su primer vertice y se cierra`() {
        // a (40,-3) -> d (40.1,-3) -> c (40.1,-2.9) -> b (40,-2.9): norte, este, sur: sentido horario
        val export = build(corners, lines = listOf(line("p1", listOf("a", "d", "c", "b"), closed = true, name = "Parcela")))

        val ring = marks(export).first { it.name == "Parcela" }.geometry.shouldBeInstanceOf<KmlGeometry.Polygon>().ring
        ring.map { it.lon to it.lat } shouldBe listOf(-3.0 to 40.0, -2.9 to 40.0, -2.9 to 40.1, -3.0 to 40.1, -3.0 to 40.0)
        export.summary.polygons shouldBe 1
    }

    @Test
    fun `S031 RF-22 un poligono ya antihorario se conserva y se cierra repitiendo su primer vertice`() {
        val export = build(corners, lines = listOf(line("p1", listOf("a", "b", "c", "d"), closed = true, name = "Parcela")))

        val ring = marks(export).first { it.name == "Parcela" }.geometry.shouldBeInstanceOf<KmlGeometry.Polygon>().ring
        ring.map { it.lon to it.lat } shouldBe listOf(-3.0 to 40.0, -2.9 to 40.0, -2.9 to 40.1, -3.0 to 40.1, -3.0 to 40.0)
    }

    @Test
    fun `S031 RF-22 una linea con menos de 2 vertices o un poligono con menos de 3 no se escribe`() {
        val export = build(
            corners,
            lines = listOf(
                line("l1", listOf("a"), name = "Corta"),
                line("p1", listOf("a", "b"), closed = true, name = "Poco"),
                line("l2", listOf("a", "b"), name = "Buena"),
            ),
        )

        marks(export).map { it.name }.filter { !it.startsWith("P-") } shouldBe listOf("Buena")
        export.summary.lines shouldBe 1
        export.summary.polygons shouldBe 0
    }

    @Test
    fun `S031 RF-22 los vertices que no existen o no tienen posicion no cuentan`() {
        val points = corners + point("e", latitude = Double.NaN, longitude = Double.NaN)
        val export = build(points, lines = listOf(line("l1", listOf("a", "e", "zz", "b"), name = "Con huecos")))

        val geometry = marks(export).first { it.name == "Con huecos" }.geometry.shouldBeInstanceOf<KmlGeometry.Line>()
        geometry.vertices.size shouldBe 2
        marks(export).first { it.name == "Con huecos" }.data.first { it.first == "Vertices" }.second shouldBe "2"
    }

    @Test
    fun `S031 RF-26 una linea con altura en todos sus vertices va absoluta con Incluir altura`() {
        val export = build(corners, KmlExportOptions(includeHeight = true), lines = listOf(line("l1", listOf("a", "b", "c"), name = "L")))

        val mark = marks(export).first { it.name == "L" }
        mark.absolute shouldBe true
        mark.geometry.shouldBeInstanceOf<KmlGeometry.Line>().vertices.all { it.height == 10.0 } shouldBe true
        export.summary.marksWithoutHeight shouldBe 0
    }

    @Test
    fun `S031 RF-27 una linea con un vertice sin altura va pegada al suelo y se cuenta`() {
        val points = corners.toMutableList().also { it[1] = point("b", latitude = 40.0, longitude = -2.9, height = null) }
        val export = build(points, KmlExportOptions(includeHeight = true), lines = listOf(line("l1", listOf("a", "b", "c"), name = "L")))

        marks(export).first { it.name == "L" }.absolute shouldBe false
        export.summary.marksWithoutHeight shouldBe 2 // el punto b, que también es una marca, y la línea
    }

    @Test
    fun `S031 RF-20 las lineas no se escriben si no hay puntos`() {
        val export = builder.build(emptyList(), project(), lines = listOf(line("l1", listOf("a", "b"))), allPoints = corners)

        export.isEmpty shouldBe true
    }

    @Test
    fun `S031 RF-22 se cuentan las lineas y los poligonos que se escribirian sin construir el documento`() {
        val lines = listOf(
            line("l1", listOf("a", "b")),
            line("l2", listOf("a")), // una línea con un solo vértice no se escribe
            line("p1", listOf("a", "b", "c"), closed = true),
            line("p2", listOf("a", "b"), closed = true), // un polígono con dos tampoco
        )

        builder.countLines(corners, lines) shouldBe (1 to 1)
        builder.countLines(emptyList(), lines, corners) shouldBe (0 to 0)
        builder.countLines(corners.take(2), lines, corners) shouldBe (1 to 1)
    }

    @Test
    fun `S031 RF-32 el resumen cuenta las marcas, las carpetas y los avisos`() {
        val points = corners + point("e", latitude = Double.NaN, longitude = Double.NaN, code = tree)
        val export = build(
            points,
            KmlExportOptions(includeHeight = true),
            lines = listOf(line("l1", listOf("a", "b"), code = wall), line("p1", listOf("a", "b", "c"), closed = true)),
        )

        export.summary shouldBe KmlExportSummary(
            points = 4,
            lines = 1,
            polygons = 1,
            folders = 2,
            pointsWithoutPosition = 1,
            marksWithoutHeight = 0,
        )
    }
}
