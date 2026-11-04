package es.topophone.core.formats.gpkg

import es.topophone.core.formats.csv.AttributeColumns
import es.topophone.core.model.codes.AttributeType
import es.topophone.core.model.codes.CodeSymbol
import es.topophone.core.model.codes.PointAttribute
import es.topophone.core.model.codes.PointCode
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.jupiter.api.Test

/** Las capas de líneas y de polígonos del GeoPackage: geometría WKB y esquema de columnas (S025/RF-26). */
class GpkgLinesTest {

    private val code = PointCode(
        "BORD",
        "",
        0,
        CodeSymbol.CIRCLE,
        listOf(PointAttribute("Alto", AttributeType.NUMBER, "0.15"), PointAttribute("Nombre", AttributeType.TEXT, "x")),
    )
    private val columns = AttributeColumns.ofCodes(listOf(code))

    private fun wkb(blob: ByteArray): ByteBuffer = ByteBuffer.wrap(blob, 8, blob.size - 8).order(ByteOrder.LITTLE_ENDIAN)

    private fun vertex(x: Double, y: Double, z: Double?) = GpkgGeometryWriter.Vertex(x, y, z)

    @Test
    fun `S025 RF-26 una linea con altura en todos sus vertices es LineString Z con sus coordenadas`() {
        val blob = GpkgGeometryWriter.lineString(25830, listOf(vertex(1.0, 2.0, 3.0), vertex(4.0, 5.0, 6.0)))

        val buffer = wkb(blob)
        buffer.get() shouldBe 1.toByte()
        buffer.getInt() shouldBe 1002
        buffer.getInt() shouldBe 2
        listOf(buffer.getDouble(), buffer.getDouble(), buffer.getDouble()) shouldBe listOf(1.0, 2.0, 3.0)
        listOf(buffer.getDouble(), buffer.getDouble(), buffer.getDouble()) shouldBe listOf(4.0, 5.0, 6.0)
        ByteBuffer.wrap(blob).order(ByteOrder.LITTLE_ENDIAN).getInt(4) shouldBe 25830
    }

    @Test
    fun `S025 RF-26 si algun vertice no tiene altura la linea va sin altura`() {
        val buffer = wkb(GpkgGeometryWriter.lineString(25830, listOf(vertex(1.0, 2.0, 3.0), vertex(4.0, 5.0, null))))

        buffer.get()
        buffer.getInt() shouldBe 2
    }

    @Test
    fun `S025 RF-26 un poligono lleva un anillo cerrado repitiendo el primer vertice`() {
        val buffer =
            wkb(GpkgGeometryWriter.polygon(25830, listOf(vertex(0.0, 0.0, null), vertex(10.0, 0.0, null), vertex(10.0, 10.0, null))))

        buffer.get()
        buffer.getInt() shouldBe 3
        buffer.getInt() shouldBe 1
        buffer.getInt() shouldBe 4
        val xy = List(4) { buffer.getDouble() to buffer.getDouble() }
        xy.first() shouldBe xy.last()
    }

    @Test
    fun `S025 RF-26 cada capa lleva nombre, codigo, descripcion, vertices y una columna tipada por atributo`() {
        val sql = GpkgLineSchema.createTable(GpkgLineSchema.LINES_TABLE, "LINESTRING", columns)

        sql shouldContain "\"lines\""
        sql shouldContain "LINESTRING"
        sql shouldContain "\"Name\" TEXT"
        sql shouldContain "\"Code\" TEXT"
        sql shouldContain "\"Vertices\" INTEGER"
        sql shouldContain "\"Alto\" REAL"
        GpkgLineSchema.createTable(GpkgLineSchema.POLYGONS_TABLE, "POLYGON") shouldContain "POLYGON"
    }

    @Test
    fun `S025 RF-26 la insercion lleva un parametro mas por cada atributo`() {
        val base = GpkgLineSchema.insert(GpkgLineSchema.LINES_TABLE).count { it == '?' }

        GpkgLineSchema.insert(GpkgLineSchema.LINES_TABLE, columns).count { it == '?' } shouldBe base + columns.size
    }

    @Test
    fun `S025 RF-26 un atributo que se llama como una columna fija recibe un nombre distinto`() {
        val clashing = AttributeColumns.ofCodes(
            listOf(PointCode("X", "", 0, CodeSymbol.CIRCLE, listOf(PointAttribute("name", AttributeType.TEXT, "a")))),
        )

        GpkgLineSchema.attributeNames(clashing) shouldBe listOf("name (atributo)")
    }

    @Test
    fun `S025 RF-26 el valor de un atributo de la linea sale del tipo de su columna`() {
        columns.map { GpkgPointAttributes.attributeValue(code, it) } shouldBe listOf(0.15, "x")
        columns.map { GpkgPointAttributes.attributeValue(null as PointCode?, it) } shouldBe listOf(null, null)
    }
}
