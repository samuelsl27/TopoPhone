package es.topophone.core.model.point

import es.topophone.core.model.codes.AttributeType
import es.topophone.core.model.codes.CodeSymbol
import es.topophone.core.model.codes.PointAttribute
import es.topophone.core.model.codes.PointCode
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** El código y los atributos en el historial y en la búsqueda de puntos (S024/RF-12, RF-14, RF-18). */
class PointHistoryCodeTest {

    private val ground = PointCoordinates(GeodeticPosition(40.41668, -3.70371, 699.423), 440_297.988, 4_474_244.001, 648.311)

    private fun point(code: PointCode?) = Point(
        id = "id-1",
        projectId = "p1",
        name = "P1",
        description = "",
        origin = PointOrigin.MANUAL,
        coordinates = ground,
        measurement = null,
        typed = null,
        edited = false,
        hidden = false,
        copyOf = null,
        createdAtMs = 1_000,
        deletedAtMs = null,
        code = code,
    )

    private fun code(name: String, vararg attributes: PointAttribute) =
        PointCode(name, "", 0x1E88E5, CodeSymbol.CIRCLE, attributes.toList())

    private fun entry(at: Long, change: PointChange, previous: Point) =
        PointHistoryEntry("h$at", "id-1", at, change, PointSnapshot.of(previous))

    @Test
    fun `S024 RF-14 la instantanea lleva el codigo y los atributos`() {
        val c = code("ARQ", PointAttribute("Diametro", AttributeType.NUMBER, "0.3"))

        PointSnapshot.of(point(c)).code shouldBe c
        PointSnapshot.of(point(null)).code shouldBe null
    }

    @Test
    fun `S024 RF-14 cambiar el codigo deja una linea con el codigo anterior y el nuevo`() {
        val before = point(code("ARQ"))
        val after = point(code("POZ"))

        val items = PointHistory.items(listOf(entry(2_000, PointChange.CODE, before)), after)

        items.single().lines shouldBe listOf(HistoryLine.Code("ARQ", "POZ"))
    }

    @Test
    fun `S024 RF-14 pasar de sin codigo a un codigo y al reves`() {
        PointHistory.items(listOf(entry(2_000, PointChange.CODE, point(null))), point(code("ARQ"))).single().lines shouldBe
            listOf(HistoryLine.Code(null, "ARQ"))
        PointHistory.items(listOf(entry(2_000, PointChange.CODE, point(code("ARQ")))), point(null)).single().lines shouldBe
            listOf(HistoryLine.Code("ARQ", null))
    }

    @Test
    fun `S024 RF-12 corregir un atributo deja una linea con el valor anterior y el nuevo`() {
        val before =
            point(code("ARQ", PointAttribute("Material", AttributeType.TEXT, "PVC"), PointAttribute("Cota", AttributeType.NUMBER, "1.5")))
        val after =
            point(
                code(
                    "ARQ",
                    PointAttribute("Material", AttributeType.TEXT, "Hormigon"),
                    PointAttribute("Cota", AttributeType.NUMBER, "1.5"),
                ),
            )

        val lines = PointHistory.items(listOf(entry(2_000, PointChange.EDIT, before)), after).single().lines

        lines shouldBe listOf(HistoryLine.Attributes(listOf(AttributeChange("Material", "PVC", "Hormigon"))))
    }

    @Test
    fun `S024 RF-18 la busqueda de la lista encuentra por codigo sin mayusculas ni acentos`() {
        val summary = point(code("Árbol")).summary()

        PointSearch.matches(summary, "arbol") shouldBe true
        PointSearch.matches(summary, "poste") shouldBe false
        PointSearch.matches(point(null).summary(), "arbol") shouldBe false
    }
}
