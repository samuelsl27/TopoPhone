package es.topophone.core.model.line

import es.topophone.core.model.codes.CodeGeometry
import es.topophone.core.model.codes.CodeSymbol
import es.topophone.core.model.codes.PointCode
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** Qué pasa con las líneas al medir un punto (S025/RF-2, RF-3, RF-7, RF-8, RF-8b). */
class LineTargetTest {

    private val bord = PointCode("BORD", "", 0x1E88E5, CodeSymbol.CIRCLE)
    private val tapia = PointCode("TAPIA", "", 0xD32F2F, CodeSymbol.CIRCLE)
    private val arq = PointCode("ARQ", "", 0x00FF00, CodeSymbol.CIRCLE)

    private fun line(code: PointCode = bord, vararg ids: String) = ProjectLine(
        id = "l1",
        projectId = "p1",
        name = "BORD_1",
        code = code,
        geometry = CodeGeometry.LINE,
        closed = false,
        hidden = false,
        active = true,
        vertices = ids.map { LineVertex(it) },
        createdAtMs = 0,
    )

    @Test
    fun `S025 RF-2 sin linea activa un codigo de linea crea una linea`() {
        LineTarget.decide(null, bord, CodeGeometry.LINE, "p1") shouldBe LineAction.Create(CodeGeometry.LINE)
        LineTarget.decide(null, bord, CodeGeometry.POLYGON, "p1") shouldBe LineAction.Create(CodeGeometry.POLYGON)
    }

    @Test
    fun `S025 RF-3 con linea activa del mismo codigo el punto se anade`() {
        LineTarget.decide(line(bord, "a"), bord, CodeGeometry.LINE, "b") shouldBe LineAction.Append("l1")
    }

    @Test
    fun `S025 RF-3 el codigo se compara sin distinguir mayusculas`() {
        LineTarget.decide(line(bord, "a"), bord.copy(code = "bord"), CodeGeometry.LINE, "b") shouldBe LineAction.Append("l1")
    }

    @Test
    fun `S025 RF-7 el mismo punto que el ultimo vertice no se repite`() {
        LineTarget.decide(line(bord, "a", "b"), bord, CodeGeometry.LINE, "b") shouldBe LineAction.Nothing
    }

    @Test
    fun `S025 RF-8 un punto con codigo de punto o sin codigo no toca la linea activa`() {
        LineTarget.decide(line(bord, "a"), arq, CodeGeometry.POINT, "b") shouldBe LineAction.Nothing
        LineTarget.decide(line(bord, "a"), null, null, "b") shouldBe LineAction.Nothing
    }

    @Test
    fun `S025 RF-8 un codigo de linea distinto crea otra linea`() {
        LineTarget.decide(line(bord, "a"), tapia, CodeGeometry.LINE, "b") shouldBe LineAction.Create(CodeGeometry.LINE)
    }

    @Test
    fun `S025 RF-8b los atributos se piden solo cuando medir crearia una linea`() {
        LineTarget.wouldCreate(null, bord, CodeGeometry.LINE) shouldBe true
        LineTarget.wouldCreate(line(bord, "a"), bord, CodeGeometry.LINE) shouldBe false
        LineTarget.wouldCreate(line(bord, "a"), tapia, CodeGeometry.LINE) shouldBe true
        LineTarget.wouldCreate(line(bord, "a"), arq, CodeGeometry.POINT) shouldBe false
    }
}
