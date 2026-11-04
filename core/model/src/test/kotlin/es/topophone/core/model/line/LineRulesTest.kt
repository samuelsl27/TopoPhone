package es.topophone.core.model.line

import es.topophone.core.model.codes.CodeGeometry
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** Mínimos de vértices, cierre, repetidos seguidos y nombres propuestos (S025/RF-2, RF-4, RF-10, RF-11, RF-15, RF-19). */
class LineRulesTest {

    @Test
    fun `S025 RF-19 una linea necesita 2 vertices y un poligono 3`() {
        LineRules.validate(listOf("a"), closed = false) shouldBe LineIssue.TOO_FEW_VERTICES
        LineRules.validate(listOf("a", "b"), closed = false).shouldBeNull()
        LineRules.validate(listOf("a", "b"), closed = true) shouldBe LineIssue.TOO_FEW_VERTICES
        LineRules.validate(listOf("a", "b", "c"), closed = true).shouldBeNull()
    }

    @Test
    fun `S025 RF-15 dos puntos iguales seguidos no se admiten, pero repetidos separados si`() {
        LineRules.validate(listOf("a", "b", "b"), closed = false) shouldBe LineIssue.REPEATED_VERTEX
        LineRules.validate(listOf("a", "b", "a"), closed = false).shouldBeNull()
    }

    @Test
    fun `S025 RF-15 en un poligono el ultimo y el primero tambien son seguidos`() {
        LineRules.validate(listOf("a", "b", "a"), closed = true) shouldBe LineIssue.REPEATED_VERTEX
    }

    @Test
    fun `S025 RF-11 solo se cierra una linea de 3 o mas vertices`() {
        LineRules.canClose(2) shouldBe false
        LineRules.canClose(3) shouldBe true
    }

    @Test
    fun `S025 RF-4 terminar cierra solo un codigo de poligono con 3 vertices o mas`() {
        LineRules.closedOnFinish(CodeGeometry.POLYGON, 3, alreadyClosed = false) shouldBe true
        LineRules.closedOnFinish(CodeGeometry.POLYGON, 2, alreadyClosed = false) shouldBe false
        LineRules.closedOnFinish(CodeGeometry.LINE, 5, alreadyClosed = false) shouldBe false
    }

    @Test
    fun `S025 RF-2 el nombre propuesto es el codigo y el mayor numero usado mas uno`() {
        LineNaming.next("BORD", emptyList()) shouldBe "BORD_1"
        LineNaming.next("BORD", listOf("BORD_1", "BORD_3", "TAPIA_9", "BORD_x")) shouldBe "BORD_4"
        LineNaming.next("bord", listOf("BORD_2")) shouldBe "bord_3"
    }

    @Test
    fun `S025 RF-14 sin codigo el nombre propuesto usa un prefijo por defecto`() {
        LineNaming.next(null, listOf("LINE_2"), fallback = "LINE") shouldBe "LINE_3"
    }
}
