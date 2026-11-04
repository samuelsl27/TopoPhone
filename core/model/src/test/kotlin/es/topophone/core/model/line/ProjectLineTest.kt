package es.topophone.core.model.line

import es.topophone.core.model.codes.CodeGeometry
import es.topophone.core.model.codes.CodeSymbol
import es.topophone.core.model.codes.PointCode
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** El modelo de la línea del proyecto y su resumen para la lista (S025/RF-21). */
class ProjectLineTest {

    private val code = PointCode("BORD", "Bordillo", 0x1E88E5, CodeSymbol.CIRCLE)
    private val line = ProjectLine(
        id = "l1",
        projectId = "p1",
        name = "BORD_1",
        code = code,
        geometry = CodeGeometry.LINE,
        closed = false,
        hidden = false,
        active = true,
        vertices = listOf(LineVertex("a"), LineVertex("b"), LineVertex("c")),
        createdAtMs = 0,
    )

    @Test
    fun `S025 RF-21 el resumen lleva nombre, codigo, numero de vertices y estado`() {
        val summary = line.summary()

        summary.name shouldBe "BORD_1"
        summary.code shouldBe code
        summary.vertexCount shouldBe 3
        summary.closed shouldBe false
        summary.active shouldBe true
    }

    @Test
    fun `S025 RF-21 los puntos de los vertices salen en orden`() {
        line.pointIds shouldBe listOf("a", "b", "c")
    }

    @Test
    fun `S025 RF-21 un poligono es una linea cerrada y no repite su primer vertice`() {
        val polygon = line.copy(closed = true)

        polygon.summary().closed shouldBe true
        polygon.vertices.size shouldBe 3
    }
}
