package es.topophone.core.formats.gpkg

import es.topophone.core.formats.csv.AttributeColumn
import es.topophone.core.formats.csv.AttributeColumns
import es.topophone.core.model.codes.AttributeType
import es.topophone.core.model.codes.CodeSymbol
import es.topophone.core.model.codes.PointAttribute
import es.topophone.core.model.codes.PointCode
import es.topophone.core.model.point.GeodeticPosition
import es.topophone.core.model.point.Point
import es.topophone.core.model.point.PointCoordinates
import es.topophone.core.model.point.PointOrigin
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test

/** Los atributos de los codigos como columnas tipadas de la capa de puntos del GeoPackage (S024/RF-21). */
class GpkgCodeAttributesTest {

    private fun point(code: PointCode?) = Point(
        id = "id",
        projectId = "p1",
        name = "1",
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

    private fun code(vararg attributes: PointAttribute) = PointCode("ARQ", "", 0x1E88E5, CodeSymbol.CIRCLE, attributes.toList())

    private val points = listOf(
        point(
            code(
                PointAttribute("Material", AttributeType.LIST, "PVC"),
                PointAttribute("Diametro", AttributeType.NUMBER, "0.123456789012345"),
                PointAttribute("Unidades", AttributeType.INTEGER, "3"),
                PointAttribute("Nota", AttributeType.TEXT, ""),
            ),
        ),
        point(null),
    )

    private val columns = AttributeColumns.of(points)

    @Test
    fun `S024 RF-21 cada atributo es una columna con su tipo SQL, lista y texto TEXT, entero INTEGER y numero REAL`() {
        columns.map { GpkgPointAttributes.typeOf(it) } shouldBe
            listOf(GpkgAttributeType.TEXT, GpkgAttributeType.REAL, GpkgAttributeType.INTEGER, GpkgAttributeType.TEXT)
        val sql = GpkgExportSchema.createPointsTable(columns)
        sql shouldContain "\"Material\" TEXT"
        sql shouldContain "\"Diametro\" REAL"
        sql shouldContain "\"Unidades\" INTEGER"
        sql shouldContain "\"Nota\" TEXT"
    }

    @Test
    fun `S024 RF-21 la insercion lleva un parametro mas por cada atributo`() {
        val base = GpkgExportSchema.insertPoint().count { it == '?' }

        GpkgExportSchema.insertPoint(columns).count { it == '?' } shouldBe base + columns.size
    }

    @Test
    fun `S024 RF-21 el valor sale del tipo de su columna sin redondear y un valor vacio es nulo`() {
        val values = columns.map { GpkgPointAttributes.attributeValue(points[0], it) }

        values shouldBe listOf("PVC", 0.123456789012345, 3L, null)
        columns.map { GpkgPointAttributes.attributeValue(points[1], it) } shouldBe List(4) { null }
    }

    @Test
    fun `S024 RF-21 un atributo cuyo nombre choca con otra columna recibe un nombre distinto`() {
        val clashing = listOf(
            AttributeColumn("Name", "name", AttributeType.TEXT),
            AttributeColumn("FID", "fid", AttributeType.INTEGER),
            AttributeColumn("code", "code", AttributeType.TEXT),
            AttributeColumn("Valor", "valor", AttributeType.TEXT),
            AttributeColumn("valor", "valor2", AttributeType.NUMBER),
        )

        val names = GpkgExportSchema.attributeNames(clashing)

        names.map { it.lowercase() }.toSet().size shouldBe names.size
        names[3] shouldBe "Valor"
        (names[0] != "Name") shouldBe true
        (names[1] != "FID") shouldBe true
    }

    @Test
    fun `S024 RF-21 sin atributos el esquema es el de siempre`() {
        GpkgExportSchema.createPointsTable(emptyList()) shouldBe GpkgExportSchema.createPointsTable()
        GpkgExportSchema.insertPoint(emptyList()) shouldBe GpkgExportSchema.insertPoint()
    }
}
