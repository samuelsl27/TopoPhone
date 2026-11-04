package es.topophone.core.model.line

import es.topophone.core.model.codes.AttributeType
import es.topophone.core.model.codes.CodeSymbol
import es.topophone.core.model.codes.PointAttribute
import es.topophone.core.model.codes.PointCode
import es.topophone.core.model.point.AttributeChange
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** El historial de una línea en palabras y la búsqueda por nombre o código (S025/RF-18, RF-21, RF-25). */
class LineHistoryTest {

    private fun v(vararg names: String) = names.map { HistoryVertex("id$it", it) }

    private val bord = PointCode("BORD", "", 0, CodeSymbol.CIRCLE, listOf(PointAttribute("Material", AttributeType.TEXT, "A")))
    private val now = LineSnapshot("BORD_1", bord, closed = false, vertices = v("1", "2", "3"))

    @Test
    fun `S025 RF-18 un cambio de nombre muestra el valor anterior y el nuevo`() {
        val entries = listOf(LineHistoryEntry(10, LineChange.NAME, now.copy(name = "ACERA")))

        LineHistory.items(entries, now).single().lines shouldBe listOf(LineHistoryLine.Name("ACERA", "BORD_1"))
    }

    @Test
    fun `S025 RF-18 un cambio de vertices dice los puntos quitados y anadidos`() {
        val entries = listOf(LineHistoryEntry(10, LineChange.VERTICES, now.copy(vertices = v("1", "2", "9"))))

        val line = LineHistory.items(entries, now).single().lines.single() as LineHistoryLine.Vertices

        line.removed shouldBe listOf("9")
        line.added shouldBe listOf("3")
    }

    @Test
    fun `S025 RF-10 cerrar y abrir quedan en el historial`() {
        val entries = listOf(
            LineHistoryEntry(20, LineChange.CLOSED, now.copy(closed = true)),
            LineHistoryEntry(10, LineChange.CLOSED, now),
        )

        val items = LineHistory.items(entries, now)

        items[0].lines shouldBe listOf(LineHistoryLine.Closed(true, false))
        items[1].lines shouldBe listOf(LineHistoryLine.Closed(false, true))
    }

    @Test
    fun `S025 RF-18 un cambio de atributos muestra el valor anterior y el nuevo`() {
        val before = bord.copy(attributes = listOf(PointAttribute("Material", AttributeType.TEXT, "B")))
        val entries = listOf(LineHistoryEntry(10, LineChange.ATTRIBUTES, now.copy(code = before)))

        LineHistory.items(entries, now).single().lines shouldBe
            listOf(LineHistoryLine.Attributes(listOf(AttributeChange("Material", "B", "A"))))
    }

    @Test
    fun `S025 RF-25 un vertice quitado al borrar definitivamente un punto se anota con su nombre`() {
        val after = now.copy(vertices = v("1", "3"))
        val entries = listOf(LineHistoryEntry(10, LineChange.VERTEX_REMOVED, now))

        val line = LineHistory.items(entries, after).single().lines.single() as LineHistoryLine.Vertices

        line.removed shouldBe listOf("2")
    }

    @Test
    fun `S025 RF-21 la busqueda encuentra por nombre y por codigo sin distinguir acentos`() {
        val summary = LineSummary("l1", "Acera norte", bord, false, false, false, 3)

        LineSearch.matches(summary, "ACERA") shouldBe true
        LineSearch.matches(summary, "bord") shouldBe true
        LineSearch.matches(summary, "tapia") shouldBe false
        LineSearch.matches(summary, "") shouldBe true
    }
}
